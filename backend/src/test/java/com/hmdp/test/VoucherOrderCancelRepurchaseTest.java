package com.hmdp.test;

import com.hmdp.dto.CreateVoucherOrderResultDTO;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.service.IVoucherOrderService;
import com.hmdp.test.support.WebIntegrationTestBase;
import com.hmdp.utils.OrderStatus;
import com.hmdp.utils.RedisConstants;
import com.hmdp.utils.UserHolder;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;

import jakarta.annotation.Resource;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 取消 / 退款后「一人一单」名额释放集成测试（P1-4，兼测 P1-3 的 Redis 补偿端到端）
 *
 * <p>
 * <b>修复前的行为</b>：{@code uk_user_voucher(user_id, voucher_id)} 不区分订单状态，
 * 取消/退款后库存与 Redis 占位已回补，但 DB 名额仍被终态订单占着，用户再次购买拿到
 * {@code ORDER_DUPLICATE}（30002）——与 {@code rollbackStock} 注释「保证用户可再次抢购」
 * 直接矛盾，且前端取消提示明写「取消后将释放优惠券库存」。
 * </p>
 *
 * <p>
 * <b>修复后</b>：V17 把唯一键收敛为生成列 {@code active_voucher_id}（取消/退款终态为
 * NULL，MySQL 唯一索引允许多个 NULL），三处一人一单 count 查询同步只统计活跃订单；
 * 订单记录本身保留（终态仍可在「已取消 / 已退款」列表与对账中可见）。
 * </p>
 *
 * <p>
 * <b>前置条件</b>：MySQL / Redis 已启动（{@code mvn test} 的既定前提，CI 由
 * {@code backend-ci.yml} 的 compose 提供）。本类覆盖：
 * </p>
 * <ul>
 *   <li>HTTP 层：普通券 购买→取消→重买 / 购买→支付→退款→重买，重买必须成功；</li>
 *   <li>Service 层：秒杀券 抢购→取消→重抢，并顺带断言 P1-3 的 Redis 补偿</li>
 * （库存 +1、占位移除、幂等标记）确实在取消后生效。</li>
 * </ul>
 */
@DisplayName("取消/退款后一人一单名额释放（P1-4）")
class VoucherOrderCancelRepurchaseTest extends WebIntegrationTestBase {

    private static final String NICK_PREFIX = "TestRepurchase_";
    /** 普通券 id（远离种子券，V9 种子从 10 起） */
    private static final long NORMAL_VOUCHER_ID = 9_300_001L;
    /** 秒杀券 id */
    private static final long SECKILL_VOUCHER_ID = 9_300_002L;

    @Resource
    private IVoucherOrderService voucherOrderService;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    private Long userId;
    private String token;

    @BeforeEach
    void setUp() {
        userId = createTestUser(NICK_PREFIX, "buyer");
        token = loginAs(userId, NICK_PREFIX + "buyer");

        // 普通券：type=0 上架，无 tb_seckill_voucher 行（createVoucherOrder 要求）
        jdbcTemplate.update(
                "INSERT INTO tb_voucher (id, shop_id, title, sub_title, rules, pay_value, actual_value, type, status) "
                        + "VALUES (?, 1, 'P1-4 重买测试普通券', 'sub', 'rules', 100, 200, 0, 1)",
                NORMAL_VOUCHER_ID);

        // 秒杀券：tb_voucher(type=1, status=1) + tb_seckill_voucher，时间窗覆盖现在
        jdbcTemplate.update(
                "INSERT INTO tb_voucher (id, shop_id, title, sub_title, rules, pay_value, actual_value, type, status) "
                        + "VALUES (?, 1, 'P1-4 重买测试秒杀券', 'sub', 'rules', 100, 200, 1, 1)",
                SECKILL_VOUCHER_ID);
        jdbcTemplate.update(
                "INSERT INTO tb_seckill_voucher (voucher_id, stock, begin_time, end_time) VALUES (?, ?, ?, ?)",
                SECKILL_VOUCHER_ID, 5, LocalDateTime.now().minusMinutes(1), LocalDateTime.now().plusHours(2));
        stringRedisTemplate.opsForValue().set(
                RedisConstants.SECKILL_STOCK_KEY + SECKILL_VOUCHER_ID, "5");
        stringRedisTemplate.delete(RedisConstants.SECKILL_ORDER_KEY + SECKILL_VOUCHER_ID);
    }

    @AfterEach
    void tearDown() {
        logout(token);
        jdbcTemplate.update("DELETE FROM tb_voucher_order WHERE voucher_id IN (?, ?)",
                NORMAL_VOUCHER_ID, SECKILL_VOUCHER_ID);
        jdbcTemplate.update("DELETE FROM tb_seckill_voucher WHERE voucher_id = ?", SECKILL_VOUCHER_ID);
        jdbcTemplate.update("DELETE FROM tb_voucher WHERE id IN (?, ?)",
                NORMAL_VOUCHER_ID, SECKILL_VOUCHER_ID);
        jdbcTemplate.update("DELETE FROM tb_user_info WHERE user_id = ?", userId);
        jdbcTemplate.update("DELETE FROM tb_user WHERE id = ?", userId);
        stringRedisTemplate.delete(RedisConstants.SECKILL_STOCK_KEY + SECKILL_VOUCHER_ID);
        stringRedisTemplate.delete(RedisConstants.SECKILL_ORDER_KEY + SECKILL_VOUCHER_ID);
    }

    // ---------------------------------------------------------------
    // HTTP 层：普通券 取消 → 重买
    // ---------------------------------------------------------------

    @Test
    @DisplayName("HTTP：普通券购买 → 取消 → 再次购买成功（不再 30002），取消记录保留")
    void cancelThenRepurchaseViaHttp() throws Exception {
        long firstOrderId = purchaseViaHttp(NORMAL_VOUCHER_ID);
        assertThat(firstOrderId).isPositive();

        mockMvc.perform(post("/voucher-orders/{id}/cancel", firstOrderId).header(AUTH_HEADER, token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));

        // 终态订单记录保留（不物理删除）：状态为已取消
        Integer status = jdbcTemplate.queryForObject(
                "SELECT status FROM tb_voucher_order WHERE id = ?", Integer.class, firstOrderId);
        assertThat(status).isEqualTo(OrderStatus.CANCELLED);

        // 关键断言：取消后再次购买必须成功。修复前此处返回 30002 ORDER_DUPLICATE。
        long secondOrderId = purchaseViaHttp(NORMAL_VOUCHER_ID);
        assertThat(secondOrderId).isPositive();
        assertThat(secondOrderId).isNotEqualTo(firstOrderId);
    }

    // ---------------------------------------------------------------
    // HTTP 层：普通券 退款 → 重买
    // ---------------------------------------------------------------

    @Test
    @DisplayName("HTTP：普通券购买 → 支付 → 退款 → 再次购买成功（不再 30002）")
    void refundThenRepurchaseViaHttp() throws Exception {
        long orderId = purchaseViaHttp(NORMAL_VOUCHER_ID);

        mockMvc.perform(post("/voucher-orders/{id}/pay", orderId)
                        .header(AUTH_HEADER, token).param("payType", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));

        mockMvc.perform(post("/voucher-orders/{id}/refund", orderId).header(AUTH_HEADER, token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));

        Integer status = jdbcTemplate.queryForObject(
                "SELECT status FROM tb_voucher_order WHERE id = ?", Integer.class, orderId);
        assertThat(status).isEqualTo(OrderStatus.REFUNDED);

        // 关键断言：退款后再次购买必须成功
        long secondOrderId = purchaseViaHttp(NORMAL_VOUCHER_ID);
        assertThat(secondOrderId).isPositive();
        assertThat(secondOrderId).isNotEqualTo(orderId);
    }

    // ---------------------------------------------------------------
    // Service 层：秒杀券 抢购 → 取消 → 重抢（含 P1-3 Redis 补偿端到端断言）
    // ---------------------------------------------------------------

    @Test
    @DisplayName("秒杀：抢购 → 取消 → 重抢成功，Redis 库存/占位/幂等标记同步正确")
    void seckillCancelThenRepurchase() throws Exception {
        UserDTO user = new UserDTO();
        user.setId(userId);
        user.setNickName(NICK_PREFIX + "buyer");
        UserHolder.saveUser(user);
        try {
            Result first = voucherOrderService.seckillVoucher(SECKILL_VOUCHER_ID);
            assertThat(first.getSuccess()).as("首次秒杀应成功").isTrue();
            long firstOrderId = ((CreateVoucherOrderResultDTO) first.getData()).getOrderId();
            // 等 Stream 消费者异步落库
            assertThat(awaitOrder(firstOrderId)).as("秒杀订单应异步落库").isTrue();

            // Lua 预扣生效：Redis 库存 5→4，用户占位
            assertThat(redisStock()).isEqualTo("4");
            assertThat(Boolean.TRUE.equals(stringRedisTemplate.opsForSet()
                    .isMember(RedisConstants.SECKILL_ORDER_KEY + SECKILL_VOUCHER_ID, userId.toString())))
                    .isTrue();

            Result cancel = voucherOrderService.cancelVoucherOrder(firstOrderId);
            assertThat(cancel.getSuccess()).as("取消应成功").isTrue();

            // P1-3 端到端：DB 提交后 Redis 补偿生效（afterCommit + Lua 原子 + 幂等标记）
            assertThat(redisStock()).isEqualTo("5");
            assertThat(Boolean.TRUE.equals(stringRedisTemplate.opsForSet()
                    .isMember(RedisConstants.SECKILL_ORDER_KEY + SECKILL_VOUCHER_ID, userId.toString())))
                    .as("取消后用户占位应被移除").isFalse();
            assertThat(Boolean.TRUE.equals(stringRedisTemplate.hasKey(
                    RedisConstants.SECKILL_ROLLBACK_KEY + firstOrderId)))
                    .as("补偿幂等标记应已写入").isTrue();

            // P1-4：DB 名额已释放，重新抢购必须成功（修复前 30002）
            Result second = voucherOrderService.seckillVoucher(SECKILL_VOUCHER_ID);
            assertThat(second.getSuccess()).as("取消后重新抢购应成功").isTrue();
            long secondOrderId = ((CreateVoucherOrderResultDTO) second.getData()).getOrderId();
            assertThat(awaitOrder(secondOrderId)).as("第二个秒杀订单应异步落库").isTrue();

            // 终态：两单并存（一取消一待支付），DB 库存 5→4
            List<VoucherOrder> orders = voucherOrderService.lambdaQuery()
                    .eq(VoucherOrder::getUserId, userId)
                    .eq(VoucherOrder::getVoucherId, SECKILL_VOUCHER_ID)
                    .list();
            assertThat(orders).hasSize(2);
            assertThat(orders.stream().filter(o -> o.getStatus() == OrderStatus.CANCELLED)).hasSize(1);
            assertThat(orders.stream().filter(o -> o.getStatus() == OrderStatus.PENDING_PAYMENT)).hasSize(1);
            Integer dbStock = jdbcTemplate.queryForObject(
                    "SELECT stock FROM tb_seckill_voucher WHERE voucher_id = ?",
                    Integer.class, SECKILL_VOUCHER_ID);
            assertThat(dbStock).isEqualTo(4);
        } finally {
            UserHolder.removeUser();
        }
    }

    // ---------------------------------------------------------------
    // 辅助
    // ---------------------------------------------------------------

    private String redisStock() {
        return stringRedisTemplate.opsForValue().get(RedisConstants.SECKILL_STOCK_KEY + SECKILL_VOUCHER_ID);
    }

    private long purchaseViaHttp(Long voucherId) throws Exception {
        String body = mockMvc.perform(post("/voucher-orders/purchase-orders")
                        .header(AUTH_HEADER, token).param("voucherId", String.valueOf(voucherId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        // 订单 id 是雪花 Long，接口按字符串下发（避免 JS 精度丢失），所以这里不能直接强转 Long
        Object orderId = JsonPath.read(body, "$.data.orderId");
        return orderId instanceof Number number ? number.longValue() : Long.parseLong(String.valueOf(orderId));
    }

    /** 轮询等待 Stream 消费者落库（最多 10s） */
    private boolean awaitOrder(long orderId) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 10_000;
        while (System.currentTimeMillis() < deadline) {
            if (voucherOrderService.getById(orderId) != null) {
                return true;
            }
            TimeUnit.MILLISECONDS.sleep(200);
        }
        return voucherOrderService.getById(orderId) != null;
    }
}
