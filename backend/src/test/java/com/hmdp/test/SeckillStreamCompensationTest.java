package com.hmdp.test;

import com.hmdp.dto.VoucherOrderMessage;
import com.hmdp.service.IVoucherOrderService;
import com.hmdp.utils.RedisConstants;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;

import jakarta.annotation.Resource;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * H3 回归测试：Stream 落库幂等判定（以消息自带 orderId 为主键）。
 *
 * <p>覆盖两条语义完全相反的路径：</p>
 * <ul>
 *   <li><b>T-H3-1</b>：{@code getById(orderId) != null} —— 本消息已完成落库，
 *       属于 ACK 丢失后的重放，Redis 预扣已被本次落库兑现，<b>禁止补偿</b>。</li>
 *   <li><b>T-H3-2</b>：{@code getById(orderId) == null && count(user,voucher) > 0} ——
 *       异常占位（他人/历史订单占用），<b>仍须补偿</b>。</li>
 * </ul>
 *
 * <p>测试直接调用 {@code createOrderByMessage}（接口公开方法），不写 Stream，
 * 因此不影响正在运行的消费者，也不与 {@code VoucherOrderTest} 共用券 ID。</p>
 *
 * <p>运行命令：{@code mvn -o test -Dtest=SeckillStreamCompensationTest -DfailIfNoTests=false}</p>
 */
@Slf4j
@SpringBootTest
@DisplayName("H3 秒杀 Stream 补偿幂等回归")
public class SeckillStreamCompensationTest {

    /** 独立券 ID，避免与 VoucherOrderTest(999) / 冒烟脚本(30003) 冲突 */
    private static final long VOUCHER_ID = 997L;
    /** 独立用户 ID，避免与既有测试用户区间冲突 */
    private static final long USER_ID = 970001L;
    /** 「本消息」的预分配 orderId */
    private static final long ORDER_ID_APPLIED = 8800000000000000001L;
    /** 「另一条消息」的预分配 orderId */
    private static final long ORDER_ID_OTHER = 8800000000000000002L;
    /** Redis 预扣基线（与 DB stock 无关，本测试只验证补偿方向） */
    private static final int REDIS_STOCK = 10;

    @Resource
    private IVoucherOrderService voucherOrderService;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private JdbcTemplate jdbcTemplate;

    private String stockKey() {
        return RedisConstants.SECKILL_STOCK_KEY + VOUCHER_ID;
    }

    private String orderKey() {
        return RedisConstants.SECKILL_ORDER_KEY + VOUCHER_ID;
    }

    @BeforeEach
    void setUp() {
        cleanTestData();

        // tb_voucher 必须先于 tb_seckill_voucher / tb_voucher_order 存在（fk_seckill_voucher_voucher / fk_order_voucher）
        jdbcTemplate.update(
                "INSERT INTO tb_voucher (id, title, pay_value, actual_value, type, status) VALUES (?, ?, ?, ?, ?, ?)",
                VOUCHER_ID, "H3-compensation-test", 100, 1000, 1, 1);
        jdbcTemplate.update(
                "INSERT INTO tb_seckill_voucher (voucher_id, stock, begin_time, end_time) VALUES (?, ?, ?, ?)",
                VOUCHER_ID, 100, LocalDateTime.now().minusMinutes(1), LocalDateTime.now().plusHours(2));
        // tb_user 必须先于 tb_voucher_order 存在（fk_order_user）
        jdbcTemplate.update(
                "INSERT INTO tb_user (id, phone, nick_name, role) VALUES (?, ?, ?, 0)",
                USER_ID, String.format("197%08d", USER_ID % 100000000), "H3TestUser_" + USER_ID);

        // Redis 预扣基线：库存已扣、用户已占位
        stringRedisTemplate.opsForValue().set(stockKey(), String.valueOf(REDIS_STOCK));
        stringRedisTemplate.opsForSet().add(orderKey(), String.valueOf(USER_ID));
    }

    @AfterEach
    void tearDown() {
        cleanTestData();
    }

    private void cleanTestData() {
        // 清理顺序必须与外键方向相反
        jdbcTemplate.update("DELETE FROM tb_voucher_order WHERE voucher_id = ?", VOUCHER_ID);
        jdbcTemplate.update("DELETE FROM tb_seckill_voucher WHERE voucher_id = ?", VOUCHER_ID);
        jdbcTemplate.update("DELETE FROM tb_voucher WHERE id = ?", VOUCHER_ID);
        jdbcTemplate.update("DELETE FROM tb_user WHERE id = ?", USER_ID);
        stringRedisTemplate.delete(stockKey());
        stringRedisTemplate.delete(orderKey());
    }

    /** 直接落库一条订单，模拟「该 orderId 的消息此前已成功落库」 */
    private void insertOrder(long orderId) {
        jdbcTemplate.update(
                "INSERT INTO tb_voucher_order (id, user_id, voucher_id, pay_type, status, is_deleted) "
                        + "VALUES (?, ?, ?, 1, 1, 0)",
                orderId, USER_ID, VOUCHER_ID);
    }

    private int orderCount() {
        Integer c = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM tb_voucher_order WHERE voucher_id = ? AND is_deleted = 0",
                Integer.class, VOUCHER_ID);
        return c == null ? 0 : c;
    }

    @Test
    @DisplayName("T-H3-1 本消息已落库的重放：禁止 Redis 补偿（库存与占位均不变）")
    void replayOfAppliedMessageMustNotCompensate() {
        insertOrder(ORDER_ID_APPLIED);
        String stockBefore = stringRedisTemplate.opsForValue().get(stockKey());
        assertThat(stockBefore).isEqualTo(String.valueOf(REDIS_STOCK));

        // ACK 丢失后的重放：同一条消息（同 orderId）再次被消费
        voucherOrderService.createOrderByMessage(
                new VoucherOrderMessage(ORDER_ID_APPLIED, USER_ID, VOUCHER_ID));

        assertThat(stringRedisTemplate.opsForValue().get(stockKey()))
                .as("本消息已落库，重放时不得回补 Redis 库存（否则 R > D 漂移）")
                .isEqualTo(stockBefore);
        assertThat(stringRedisTemplate.opsForSet().isMember(orderKey(), String.valueOf(USER_ID)))
                .as("本消息已落库，重放时不得清除用户占位")
                .isTrue();
        assertThat(orderCount())
                .as("重放不得新增订单")
                .isEqualTo(1);
    }

    @Test
    @DisplayName("T-H3-2 异常占位（不同 orderId）：仍执行 Redis 补偿")
    void spuriousAdmissionMustStillCompensate() {
        insertOrder(ORDER_ID_APPLIED);
        String stockBefore = stringRedisTemplate.opsForValue().get(stockKey());
        assertThat(stockBefore).isEqualTo(String.valueOf(REDIS_STOCK));

        // 另一条消息（新 orderId）抵达，但该 (user, voucher) 已有订单 → 异常占位 → 补偿
        voucherOrderService.createOrderByMessage(
                new VoucherOrderMessage(ORDER_ID_OTHER, USER_ID, VOUCHER_ID));

        assertThat(stringRedisTemplate.opsForValue().get(stockKey()))
                .as("存在他人/历史订单占位时应回补 Redis 库存")
                .isEqualTo(String.valueOf(REDIS_STOCK + 1));
        assertThat(stringRedisTemplate.opsForSet().isMember(orderKey(), String.valueOf(USER_ID)))
                .as("补偿后应清除用户占位")
                .isFalse();
        assertThat(orderCount())
                .as("补偿路径不得新增订单")
                .isEqualTo(1);
    }
}
