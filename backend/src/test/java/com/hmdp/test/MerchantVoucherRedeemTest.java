package com.hmdp.test;

import com.hmdp.test.support.WebIntegrationTestBase;
import com.hmdp.utils.RedisConstants;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.ConnectionCallback;

import java.nio.charset.StandardCharsets;
import java.sql.Statement;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 商家核销订单测试（Phase 3.4-A · {@code POST /voucher-orders/{orderId}/redeem}）
 * <p>
 * 覆盖：状态机（仅 {@code PAID(2) -> USED(3)}）、权限（角色 + 店铺归属）、
 * 异常分支（订单/券不存在、券无归属、店铺不存在）、
 * <b>CAS 并发唯一性</b>、以及核销对 Redis 的<b>零副作用</b>。
 * </p>
 *
 * <h3>测试用例清单</h3>
 * <ol>
 *   <li>A：商家核销本店已支付订单 → 200 ok，DB {@code status=3} 且 {@code use_time} 写入；</li>
 *   <li>B：重复核销 → 第二次 30009，{@code use_time} 不被覆盖；</li>
 *   <li>C：核销待支付（1）订单 → 30009，DB 不变；</li>
 *   <li>D：核销已取消（4）订单 → 30009，DB 不变；</li>
 *   <li>E：核销已退款（6）订单 → 30009，DB 不变；</li>
 *   <li>F：核销不存在的订单 → 200 + 30008；</li>
 *   <li>G：商家A 核销商家B 店铺的订单 → 403 + 40004，DB 不变；</li>
 *   <li>H：普通用户（role=0）核销 → 403 + 40003，DB 不变；</li>
 *   <li>I：匿名核销 → 401 + 10001，DB 不变；</li>
 *   <li>J：订单对应券不存在（悬空引用）→ 200 + 30000；</li>
 *   <li>K：券无归属（{@code shop_id} 为 NULL）→ 403 + 40004，DB 不变；</li>
 *   <li>L：券所属店铺不存在 → 200 + 40001，DB 不变；</li>
 *   <li>M：核销对 Redis 零副作用（{@code seckill:*}/{@code stream:*}/{@code lock:*} 键集合不变）；</li>
 *   <li>N：已过期秒杀券的已支付订单仍可核销（D2：不校验 {@code end_time}）→ 200 ok；</li>
 *   <li>CONC：并发核销同一订单 → 恰好 1 次成功，其余 30009，DB 终态 {@code status=3}。</li>
 * </ol>
 *
 * <p>
 * 自建自清：商家 / 买家 / 店铺 / 券 / 订单均由测试创建，{@code tearDown} 按
 * 「订单 → 秒杀行 → 券 → 店铺 → 用户」的 FK 顺序精确删除并清理 Redis，
 * 不触碰任何种子数据与真实登录会话。
 * </p>
 */
@DisplayName("商家核销订单（POST /voucher-orders/{orderId}/redeem）")
class MerchantVoucherRedeemTest extends WebIntegrationTestBase {

    private static final int SEED_TYPE_ID = 1;
    private static final String HOURS = "10:00-22:00";
    private static final String TITLE_PREFIX = "IT-Redeem-";
    private static final String ADDR_A = "集成测试核销店铺A";
    private static final String ADDR_B = "集成测试核销店铺B";

    /** 直接落库订单的主键序号（远离真实雪花 id，避免碰撞） */
    private static final AtomicLong DIRECT_ORDER_SEQ = new AtomicLong(9_000_000_000_000_000_000L);
    /** 直接落库券的主键序号（tb_voucher.id 为自增，取极大值避免与 AUTO_INCREMENT 冲突） */
    private static final AtomicLong DIRECT_VOUCHER_SEQ = new AtomicLong(9_000_000_000_000_000_000L);

    private Long merchantAId;
    private Long merchantBId;
    private Long buyer1Id;
    private Long buyer2Id;
    private Long buyer3Id;
    private String merchantAToken;
    private String merchantBToken;
    private String buyer1Token;
    private String buyer2Token;
    private String buyer3Token;

    private Long shopAId;
    private Long shopBId;
    private Long voucherAId;
    private Long voucherBId;

    private final List<Long> createdShopIds = new ArrayList<>();
    private final List<Long> createdVoucherIds = new ArrayList<>();
    private final List<Long> createdOrderIds = new ArrayList<>();
    private final List<Long> createdUserIds = new ArrayList<>();

    @BeforeEach
    void setUp() throws Exception {
        merchantAId = newUser("TestRedeemMerchantA_", "a");
        merchantBId = newUser("TestRedeemMerchantB_", "b");
        buyer1Id = newUser("TestRedeemBuyer1_", "1");
        buyer2Id = newUser("TestRedeemBuyer2_", "2");
        buyer3Id = newUser("TestRedeemBuyer3_", "3");

        merchantAToken = loginAsMerchant(merchantAId, "TestRedeemMerchantA");
        merchantBToken = loginAsMerchant(merchantBId, "TestRedeemMerchantB");
        buyer1Token = loginAs(buyer1Id, "TestRedeemBuyer1");
        buyer2Token = loginAs(buyer2Id, "TestRedeemBuyer2");
        buyer3Token = loginAs(buyer3Id, "TestRedeemBuyer3");

        shopAId = createShop(merchantAToken, "核销店铺A", ADDR_A);
        shopBId = createShop(merchantBToken, "核销店铺B", ADDR_B);

        voucherAId = createPlainVoucher(merchantAToken, shopAId, "A的券");
        voucherBId = createPlainVoucher(merchantBToken, shopBId, "B的券");
    }

    @AfterEach
    void tearDown() {
        // 1) 订单（子表）先删：tb_voucher_order -> tb_voucher 为 FK RESTRICT
        for (Long oid : createdOrderIds) {
            jdbcTemplate.update("DELETE FROM tb_voucher_order WHERE id = ?", oid);
        }
        createdOrderIds.clear();

        // 2) 秒杀行 -> 券 -> 店铺（按 FK 依赖逆序）
        for (Long vid : createdVoucherIds) {
            jdbcTemplate.update("DELETE FROM tb_seckill_voucher WHERE voucher_id = ?", vid);
            jdbcTemplate.update("DELETE FROM tb_voucher WHERE id = ?", vid);
            stringRedisTemplate.delete(RedisConstants.SECKILL_STOCK_KEY + vid);
            stringRedisTemplate.delete(RedisConstants.SECKILL_ORDER_KEY + vid);
        }
        createdVoucherIds.clear();
        jdbcTemplate.update("DELETE FROM tb_voucher WHERE title LIKE ?", TITLE_PREFIX + "%");

        for (Long sid : createdShopIds) {
            jdbcTemplate.update("DELETE FROM tb_shop WHERE id = ?", sid);
            stringRedisTemplate.delete(RedisConstants.CACHE_SHOP_KEY + sid);
        }
        createdShopIds.clear();
        jdbcTemplate.update("DELETE FROM tb_shop WHERE address IN (?, ?)", ADDR_A, ADDR_B);

        // 3) 用户
        for (Long uid : createdUserIds) {
            jdbcTemplate.update("DELETE FROM tb_user_info WHERE user_id = ?", uid);
            jdbcTemplate.update("DELETE FROM tb_user WHERE id = ?", uid);
        }
        createdUserIds.clear();

        // 4) 登录会话
        logout(merchantAToken);
        logout(merchantBToken);
        logout(buyer1Token);
        logout(buyer2Token);
        logout(buyer3Token);
    }

    // ==================================================================
    // Case A：正常核销
    // ==================================================================

    @Test
    @DisplayName("CaseA 商家A 核销本店已支付订单 -> 200 ok，DB status=3 且 use_time 写入")
    void redeemPaidOrderSucceeds() throws Exception {
        long orderId = createPaidOrder(buyer1Token, voucherAId);
        Timestamp payTimeBefore = dbPayTime(orderId);

        mockMvc.perform(post("/voucher-orders/" + orderId + "/redeem")
                        .header(AUTH_HEADER, merchantAToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));

        assertThat(dbOrderStatus(orderId)).isEqualTo(3);
        assertThat(dbUseTime(orderId)).as("use_time 必须写入").isNotNull();
        // 只改状态与核销时间，其余字段不动
        assertThat(dbPayTime(orderId)).isEqualTo(payTimeBefore);
        assertThat(dbOrderUserId(orderId)).isEqualTo(buyer1Id);
        assertThat(dbOrderVoucherId(orderId)).isEqualTo(voucherAId);
        assertThat(dbOrderIsDeleted(orderId)).isZero();
    }

    // ==================================================================
    // Case B：重复核销
    // ==================================================================

    @Test
    @DisplayName("CaseB 重复核销 -> 第二次 30009，use_time 不被覆盖")
    void redeemTwiceSecondRejected() throws Exception {
        long orderId = createPaidOrder(buyer1Token, voucherAId);

        mockMvc.perform(post("/voucher-orders/" + orderId + "/redeem")
                        .header(AUTH_HEADER, merchantAToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));
        Timestamp firstUseTime = dbUseTime(orderId);
        assertThat(firstUseTime).isNotNull();

        mockMvc.perform(post("/voucher-orders/" + orderId + "/redeem")
                        .header(AUTH_HEADER, merchantAToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.errorCode").value("30009"))
                .andExpect(jsonPath("$.errorMsg").value("当前订单状态不允许该操作"));

        assertThat(dbOrderStatus(orderId)).isEqualTo(3);
        assertThat(dbUseTime(orderId)).isEqualTo(firstUseTime);
    }

    // ==================================================================
    // Case C / D / E：非 PAID 状态一律拒绝
    // ==================================================================

    @Test
    @DisplayName("CaseC 核销待支付(1)订单 -> 30009，DB 不变")
    void redeemPendingPaymentRejected() throws Exception {
        long orderId = createPendingOrder(buyer1Token, voucherAId);

        assertRejectedWithStatusInvalid(orderId);
        assertThat(dbOrderStatus(orderId)).isEqualTo(1);
        assertThat(dbUseTime(orderId)).isNull();
    }

    @Test
    @DisplayName("CaseD 核销已取消(4)订单 -> 30009，DB 不变")
    void redeemCancelledRejected() throws Exception {
        long orderId = createPendingOrder(buyer1Token, voucherAId);
        mockMvc.perform(post("/voucher-orders/" + orderId + "/cancel")
                        .header(AUTH_HEADER, buyer1Token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));
        assertThat(dbOrderStatus(orderId)).isEqualTo(4);

        assertRejectedWithStatusInvalid(orderId);
        assertThat(dbOrderStatus(orderId)).isEqualTo(4);
        assertThat(dbUseTime(orderId)).isNull();
    }

    @Test
    @DisplayName("CaseE 核销已退款(6)订单 -> 30009，DB 不变")
    void redeemRefundedRejected() throws Exception {
        long orderId = createPaidOrder(buyer1Token, voucherAId);
        mockMvc.perform(post("/voucher-orders/" + orderId + "/refund")
                        .header(AUTH_HEADER, buyer1Token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));
        assertThat(dbOrderStatus(orderId)).isEqualTo(6);

        assertRejectedWithStatusInvalid(orderId);
        assertThat(dbOrderStatus(orderId)).isEqualTo(6);
        assertThat(dbUseTime(orderId)).isNull();
    }

    // ==================================================================
    // Case F：订单不存在
    // ==================================================================

    @Test
    @DisplayName("CaseF 核销不存在的订单 -> 200 + 30008")
    void redeemMissingOrderReturnsOrderNotFound() throws Exception {
        mockMvc.perform(post("/voucher-orders/999999999999/redeem")
                        .header(AUTH_HEADER, merchantAToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.errorCode").value("30008"))
                .andExpect(jsonPath("$.errorMsg").value("订单不存在"));
    }

    // ==================================================================
    // Case G：跨商家核销
    // ==================================================================

    @Test
    @DisplayName("CaseG 商家A 核销商家B 店铺的订单 -> 403 + 40004，DB 不变")
    void crossMerchantRedeemForbidden() throws Exception {
        long orderId = createPaidOrder(buyer1Token, voucherBId);

        mockMvc.perform(post("/voucher-orders/" + orderId + "/redeem")
                        .header(AUTH_HEADER, merchantAToken))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.errorCode").value("40004"))
                .andExpect(jsonPath("$.errorMsg").value("无权操作该店铺"));

        assertThat(dbOrderStatus(orderId)).isEqualTo(2);
        assertThat(dbUseTime(orderId)).isNull();
    }

    // ==================================================================
    // Case H / I：角色语义
    // ==================================================================

    @Test
    @DisplayName("CaseH 普通用户(role=0)核销 -> 403 + 40003，DB 不变")
    void plainUserRedeemForbidden() throws Exception {
        long orderId = createPaidOrder(buyer1Token, voucherAId);

        mockMvc.perform(post("/voucher-orders/" + orderId + "/redeem")
                        .header(AUTH_HEADER, buyer2Token))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.errorCode").value("40003"))
                .andExpect(jsonPath("$.errorMsg").value("仅商家可操作"));

        assertThat(dbOrderStatus(orderId)).isEqualTo(2);
        assertThat(dbUseTime(orderId)).isNull();
    }

    @Test
    @DisplayName("CaseI 匿名核销 -> 401 + 10001，DB 不变")
    void anonymousRedeemUnauthorized() throws Exception {
        long orderId = createPaidOrder(buyer1Token, voucherAId);

        mockMvc.perform(post("/voucher-orders/" + orderId + "/redeem"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.errorCode").value("10001"))
                .andExpect(jsonPath("$.errorMsg").value("用户未登录"));

        assertThat(dbOrderStatus(orderId)).isEqualTo(2);
        assertThat(dbUseTime(orderId)).isNull();
    }

    // ==================================================================
    // Case J：订单对应券不存在（悬空引用，FK 校验临时关闭以构造数据）
    // ==================================================================

    @Test
    @DisplayName("CaseJ 订单对应券不存在 -> 200 + 30000")
    void redeemOrderWithMissingVoucherReturnsVoucherNotFound() throws Exception {
        long missingVoucherId = 888_000_001L;
        long orderId = DIRECT_ORDER_SEQ.incrementAndGet();
        execIgnoringFk("INSERT INTO tb_voucher_order (id, user_id, voucher_id, pay_type, status, pay_time, is_deleted)"
                + " VALUES (" + orderId + ", " + buyer1Id + ", " + missingVoucherId + ", 1, 2, NOW(), 0)");
        createdOrderIds.add(orderId);

        mockMvc.perform(post("/voucher-orders/" + orderId + "/redeem")
                        .header(AUTH_HEADER, merchantAToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.errorCode").value("30000"))
                .andExpect(jsonPath("$.errorMsg").value("优惠券不存在"));

        assertThat(dbOrderStatus(orderId)).isEqualTo(2);
    }

    // ==================================================================
    // Case K：券无归属（shop_id 为 NULL）
    // ==================================================================

    @Test
    @DisplayName("CaseK 券无归属(shop_id NULL) -> 403 + 40004，DB 不变")
    void redeemOrderWithNullShopVoucherForbidden() throws Exception {
        long voucherId = DIRECT_VOUCHER_SEQ.incrementAndGet();
        jdbcTemplate.update("INSERT INTO tb_voucher (id, shop_id, title, pay_value, actual_value, type, status)"
                + " VALUES (?, NULL, ?, 100, 200, 0, 1)", voucherId, TITLE_PREFIX + "无归属券");
        createdVoucherIds.add(voucherId);

        long orderId = DIRECT_ORDER_SEQ.incrementAndGet();
        jdbcTemplate.update("INSERT INTO tb_voucher_order (id, user_id, voucher_id, pay_type, status, pay_time, is_deleted)"
                + " VALUES (?, ?, ?, 1, 2, NOW(), 0)", orderId, buyer1Id, voucherId);
        createdOrderIds.add(orderId);

        mockMvc.perform(post("/voucher-orders/" + orderId + "/redeem")
                        .header(AUTH_HEADER, merchantAToken))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.errorCode").value("40004"));

        assertThat(dbOrderStatus(orderId)).isEqualTo(2);
        assertThat(dbUseTime(orderId)).isNull();
    }

    // ==================================================================
    // Case L：券所属店铺不存在 -> 40001（与「无权限」严格区分）
    // ==================================================================

    @Test
    @DisplayName("CaseL 券所属店铺不存在 -> 200 + 40001，DB 不变")
    void redeemOrderWithMissingShopReturnsShopNotFound() throws Exception {
        long missingShopId = 777_000_001L;
        long voucherId = DIRECT_VOUCHER_SEQ.incrementAndGet();
        execIgnoringFk("INSERT INTO tb_voucher (id, shop_id, title, pay_value, actual_value, type, status)"
                + " VALUES (" + voucherId + ", " + missingShopId + ", '" + TITLE_PREFIX + "悬空店铺券', 100, 200, 0, 1)");
        createdVoucherIds.add(voucherId);

        long orderId = DIRECT_ORDER_SEQ.incrementAndGet();
        jdbcTemplate.update("INSERT INTO tb_voucher_order (id, user_id, voucher_id, pay_type, status, pay_time, is_deleted)"
                + " VALUES (?, ?, ?, 1, 2, NOW(), 0)", orderId, buyer1Id, voucherId);
        createdOrderIds.add(orderId);

        mockMvc.perform(post("/voucher-orders/" + orderId + "/redeem")
                        .header(AUTH_HEADER, merchantAToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.errorCode").value("40001"))
                .andExpect(jsonPath("$.errorMsg").value("店铺不存在"));

        assertThat(dbOrderStatus(orderId)).isEqualTo(2);
        assertThat(dbUseTime(orderId)).isNull();
    }

    // ==================================================================
    // Case M：核销对 Redis 零副作用
    // ==================================================================

    @Test
    @DisplayName("CaseM 核销秒杀券订单 -> Redis seckill/stream/lock 键集合不变")
    void redeemHasNoRedisSideEffects() throws Exception {
        long seckillVoucherId = createSeckillVoucher(merchantAToken, shopAId, "A的秒杀券", 5);
        long orderId = createPaidOrder(buyer1Token, seckillVoucherId);

        // 人为制造秒杀残留：库存键（addSeckillVoucher 已写入）+ 占位集合成员
        stringRedisTemplate.opsForSet().add(
                RedisConstants.SECKILL_ORDER_KEY + seckillVoucherId, buyer1Id.toString());

        Set<String> keysBefore = redisSeckillKeys();
        String stockBefore = redisStock(seckillVoucherId);
        Set<String> orderSetBefore = stringRedisTemplate.opsForSet()
                .members(RedisConstants.SECKILL_ORDER_KEY + seckillVoucherId);
        // 注意：下单时 DB 秒杀库存已被扣减（5 -> 4），此处记录核销前基线
        Integer dbStockBefore = dbSeckillStock(seckillVoucherId);

        mockMvc.perform(post("/voucher-orders/" + orderId + "/redeem")
                        .header(AUTH_HEADER, merchantAToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));

        assertThat(dbOrderStatus(orderId)).isEqualTo(3);
        // 键集合、库存值、占位集合成员三者均不变
        assertThat(redisSeckillKeys()).isEqualTo(keysBefore);
        assertThat(redisStock(seckillVoucherId)).isEqualTo(stockBefore);
        assertThat(stringRedisTemplate.opsForSet()
                .members(RedisConstants.SECKILL_ORDER_KEY + seckillVoucherId)).isEqualTo(orderSetBefore);
        // DB 秒杀库存同样不动（核销不涉及库存流转）
        assertThat(dbSeckillStock(seckillVoucherId)).isEqualTo(dbStockBefore);
    }

    // ==================================================================
    // Case N：D2 —— 已过期秒杀券的已支付订单仍可核销（不校验 end_time）
    // ==================================================================

    @Test
    @DisplayName("CaseN 秒杀券 end_time 已过期，其已支付订单仍可核销 -> 200 ok（D2）")
    void redeemSeckillOrderIgnoresEndTime() throws Exception {
        long seckillVoucherId = createSeckillVoucher(merchantAToken, shopAId, "A的过期秒杀券", 5);
        // 先正常支付（end_time 尚未过期）
        long orderId = createPaidOrder(buyer1Token, seckillVoucherId);
        // 再把 end_time 推到过去，模拟「券活动已结束但订单已支付」
        jdbcTemplate.update("UPDATE tb_seckill_voucher SET end_time = ? WHERE voucher_id = ?",
                Timestamp.valueOf("2020-01-01 00:00:00"), seckillVoucherId);

        mockMvc.perform(post("/voucher-orders/" + orderId + "/redeem")
                        .header(AUTH_HEADER, merchantAToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));

        assertThat(dbOrderStatus(orderId)).isEqualTo(3);
        assertThat(dbUseTime(orderId)).isNotNull();
    }

    // ==================================================================
    // CONC：并发核销同一订单 —— CAS 保证恰好一次成功
    // ==================================================================

    @Test
    @DisplayName("CaseCONC 并发核销同一订单 -> 恰好 1 次成功，其余 30009，DB 终态 status=3")
    void concurrentRedeemOnlyOneSucceeds() throws Exception {
        final long orderId = createPaidOrder(buyer1Token, voucherAId);

        final int threads = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        List<String> errorCodes = java.util.Collections.synchronizedList(new ArrayList<>());
        AtomicLong successCount = new AtomicLong();

        try {
            for (int i = 0; i < threads; i++) {
                pool.submit(() -> {
                    ready.countDown();
                    try {
                        start.await();
                        String body = mockMvc.perform(post("/voucher-orders/" + orderId + "/redeem")
                                        .header(AUTH_HEADER, merchantAToken))
                                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
                        Boolean success = JsonPath.read(body, "$.success");
                        if (Boolean.TRUE.equals(success)) {
                            successCount.incrementAndGet();
                        } else {
                            errorCodes.add((String) JsonPath.read(body, "$.errorCode"));
                        }
                    } catch (Exception e) {
                        errorCodes.add("EX:" + e.getClass().getSimpleName());
                    } finally {
                        done.countDown();
                    }
                });
            }
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            assertThat(done.await(30, TimeUnit.SECONDS)).isTrue();
        } finally {
            pool.shutdownNow();
        }

        assertThat(successCount.get()).as("并发核销必须恰好成功一次").isEqualTo(1);
        assertThat(errorCodes).hasSize(threads - 1).containsOnly("30009");
        assertThat(dbOrderStatus(orderId)).isEqualTo(3);
        assertThat(dbUseTime(orderId)).isNotNull();
    }

    // ==================================================================
    // 辅助：构造订单
    // ==================================================================

    /** 下单（不支付）→ 订单状态 1 */
    private long createPendingOrder(String buyerToken, long voucherId) throws Exception {
        // 秒杀券只能走秒杀入口：普通购买入口对 type=1 直接返回 30007，
        // 否则会绕过库存预扣与时间窗，等于把秒杀库存挂到同步购买上卖。
        if (isSeckillVoucher(voucherId)) {
            String body = mockMvc.perform(post("/voucher-orders/seckill-orders")
                            .header(AUTH_HEADER, buyerToken)
                            .param("voucherId", String.valueOf(voucherId)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true))
                    .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
            long orderId = Long.parseLong(String.valueOf((Object) JsonPath.read(body, "$.data.orderId")));
            createdOrderIds.add(orderId);
            // 秒杀入口只做 Redis 预扣 + 投递 Stream，落库由消费者异步完成
            awaitOrderPersisted(orderId);
            return orderId;
        }
        String body = mockMvc.perform(post("/voucher-orders/purchase-orders")
                        .header(AUTH_HEADER, buyerToken)
                        .param("voucherId", String.valueOf(voucherId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        long orderId = Long.parseLong(String.valueOf((Object) JsonPath.read(body, "$.data.orderId")));
        createdOrderIds.add(orderId);
        return orderId;
    }

    private boolean isSeckillVoucher(long voucherId) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM tb_seckill_voucher WHERE voucher_id = ?", Integer.class, voucherId);
        return count != null && count > 0;
    }

    /** 等待 Stream 消费者把订单落库（最多 5s）。 */
    private void awaitOrderPersisted(long orderId) throws InterruptedException {
        for (int i = 0; i < 50; i++) {
            Integer count = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM tb_voucher_order WHERE id = ?", Integer.class, orderId);
            if (count != null && count > 0) {
                return;
            }
            Thread.sleep(100);
        }
        throw new AssertionError("秒杀订单未在 5s 内落库: orderId=" + orderId);
    }

    /** 下单并支付 → 订单状态 2 */
    private long createPaidOrder(String buyerToken, long voucherId) throws Exception {
        long orderId = createPendingOrder(buyerToken, voucherId);
        mockMvc.perform(post("/voucher-orders/" + orderId + "/pay")
                        .header(AUTH_HEADER, buyerToken)
                        .param("payType", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));
        assertThat(dbOrderStatus(orderId)).isEqualTo(2);
        return orderId;
    }

    private void assertRejectedWithStatusInvalid(long orderId) throws Exception {
        mockMvc.perform(post("/voucher-orders/" + orderId + "/redeem")
                        .header(AUTH_HEADER, merchantAToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.errorCode").value("30009"))
                .andExpect(jsonPath("$.errorMsg").value("当前订单状态不允许该操作"));
    }

    // ==================================================================
    // 辅助：构造店铺 / 券 / 用户
    // ==================================================================

    private Long newUser(String prefix, String suffix) {
        Long id = createTestUser(prefix, suffix);
        createdUserIds.add(id);
        return id;
    }

    private long createShop(String token, String name, String address) throws Exception {
        String body = mockMvc.perform(post("/shops")
                        .header(AUTH_HEADER, token)
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .content("{"
                                + "\"name\":\"" + name + "\","
                                + "\"typeId\":" + SEED_TYPE_ID + ","
                                + "\"images\":\"[\\\"https://example.com/test-shop.jpg\\\"]\","
                                + "\"address\":\"" + address + "\","
                                + "\"x\":120.1500,\"y\":30.3100,"
                                + "\"sold\":0,\"comments\":0,\"score\":50,"
                                + "\"openHours\":\"" + HOURS + "\""
                                + "}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        long id = Long.parseLong(String.valueOf((Object) JsonPath.read(body, "$.data")));
        createdShopIds.add(id);
        return id;
    }

    private long createPlainVoucher(String token, long shopId, String title) throws Exception {
        return createVoucher(token, "/vouchers", "{"
                + "\"shopId\":" + shopId + ","
                + "\"title\":\"" + TITLE_PREFIX + title + "\","
                + "\"payValue\":100,"
                + "\"actualValue\":200,"
                + "\"type\":0"
                + "}");
    }

    private long createSeckillVoucher(String token, long shopId, String title, int stock) throws Exception {
        return createVoucher(token, "/vouchers/seckill-vouchers", "{"
                + "\"shopId\":" + shopId + ","
                + "\"title\":\"" + TITLE_PREFIX + title + "\","
                + "\"payValue\":100,"
                + "\"actualValue\":200,"
                + "\"type\":1,"
                + "\"stock\":" + stock + ","
                + "\"beginTime\":\"2020-01-01T00:00:00\","
                + "\"endTime\":\"2099-12-31T23:59:59\""
                + "}");
    }

    private long createVoucher(String token, String path, String payload) throws Exception {
        String body = mockMvc.perform(post(path)
                        .header(AUTH_HEADER, token)
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .content(payload))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        long id = Long.parseLong(String.valueOf((Object) JsonPath.read(body, "$.data")));
        createdVoucherIds.add(id);
        return id;
    }

    /**
     * 在单条连接上临时关闭外键校验后执行 SQL。
     * <p>
     * 仅用于构造「悬空引用」异常数据（订单指向不存在的券、券指向不存在的店铺），
     * 这类数据在真实 FK 约束下无法产生，但防御性代码分支必须被覆盖。
     * 连接池保证同一 {@link ConnectionCallback} 内为同一连接，执行完毕立即恢复
     * {@code FOREIGN_KEY_CHECKS=1}，不影响其它连接与后续语句。
     * </p>
     */
    private void execIgnoringFk(String... sqls) {
        jdbcTemplate.execute((ConnectionCallback<Void>) con -> {
            try (Statement st = con.createStatement()) {
                st.execute("SET FOREIGN_KEY_CHECKS=0");
                try {
                    for (String sql : sqls) {
                        st.execute(sql);
                    }
                } finally {
                    st.execute("SET FOREIGN_KEY_CHECKS=1");
                }
            }
            return null;
        });
    }

    // ==================================================================
    // 辅助：DB / Redis 断言取值
    // ==================================================================

    private Integer dbOrderStatus(long orderId) {
        return jdbcTemplate.queryForObject("SELECT status FROM tb_voucher_order WHERE id = ?", Integer.class, orderId);
    }

    private Timestamp dbUseTime(long orderId) {
        return jdbcTemplate.queryForObject("SELECT use_time FROM tb_voucher_order WHERE id = ?", Timestamp.class, orderId);
    }

    private Timestamp dbPayTime(long orderId) {
        return jdbcTemplate.queryForObject("SELECT pay_time FROM tb_voucher_order WHERE id = ?", Timestamp.class, orderId);
    }

    private Long dbOrderUserId(long orderId) {
        return jdbcTemplate.queryForObject("SELECT user_id FROM tb_voucher_order WHERE id = ?", Long.class, orderId);
    }

    private Long dbOrderVoucherId(long orderId) {
        return jdbcTemplate.queryForObject("SELECT voucher_id FROM tb_voucher_order WHERE id = ?", Long.class, orderId);
    }

    private Integer dbOrderIsDeleted(long orderId) {
        return jdbcTemplate.queryForObject("SELECT is_deleted FROM tb_voucher_order WHERE id = ?", Integer.class, orderId);
    }

    private Integer dbSeckillStock(long voucherId) {
        return jdbcTemplate.queryForObject(
                "SELECT stock FROM tb_seckill_voucher WHERE voucher_id = ?", Integer.class, voucherId);
    }

    private String redisStock(long voucherId) {
        return stringRedisTemplate.opsForValue().get(RedisConstants.SECKILL_STOCK_KEY + voucherId);
    }

    /** 核销可能触及的全部 Redis 键（库存 / 占位 / Stream / 锁），用于零副作用断言 */
    private Set<String> redisSeckillKeys() {
        Set<String> keys = new HashSet<>();
        for (String pattern : new String[]{"seckill*", "stream*", "lock*"}) {
            Set<String> matched = stringRedisTemplate.keys(pattern);
            if (matched != null) {
                keys.addAll(matched);
            }
        }
        return keys;
    }
}
