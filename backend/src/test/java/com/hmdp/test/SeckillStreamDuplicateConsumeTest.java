package com.hmdp.test;

import com.hmdp.service.IVoucherOrderService;
import com.hmdp.utils.RedisConstants;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.RecordId;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.AopTestUtils;

import jakarta.annotation.Resource;
import javax.sql.DataSource;
import java.lang.reflect.Method;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * H2 / A3 并发语义回归：XCLAIM 认领重叠导致的「同一消息被两个 consumer 并发处理」。
 *
 * <p><b>重要声明</b>：该测试用于验证 {@code DuplicateKeyException} / A3 补偿分支的
 * <b>并发语义</b>，通过反射直接调用生产私有方法 {@code handleStreamRecord} 来精确制造
 * 「已通过 H3 幂等检查、但落库撞唯一约束」的时刻，<b>不替代完整 Stream 消费链路测试</b>
 * （完整链路已由 H1/H3 用例与冻结冒烟脚本覆盖）。不修改 {@code handleStreamRecord} 可见性，
 * 也不为测试改动任何生产代码结构。</p>
 *
 * <p>时序构造（确定性，不依赖 sleep 竞速）：测试线程持有一个<b>未提交</b>的
 * {@code INSERT INTO tb_voucher_order} 事务 T，它持有目标唯一键的排他锁：</p>
 * <ol>
 *   <li>B 线程进入 {@code handleStreamRecord} → {@code getById(orderId)} 看不到 T 的未提交行 → 通过 H3 检查；</li>
 *   <li>B 的 {@code count(user,voucher)} 同样看不到 T 的行；</li>
 *   <li>B 的 {@code INSERT} 阻塞在 T 的锁上（{@code INNODB_TRX.trx_state='LOCK WAIT'}，可观测）；</li>
 *   <li>测试提交 T → B 的 INSERT 立即抛 {@code DuplicateKeyException} → 进入 A3 分支。</li>
 * </ol>
 * <p>因此可以断言 A3 分支的两种语义：</p>
 * <ul>
 *   <li><b>T-H2-3a</b>：同 orderId（PK 冲突）→ {@code getById(orderId) != null} → <b>不补偿</b> Redis。</li>
 *   <li><b>T-H2-3b</b>：不同 orderId、同 (user,voucher)（uk_user_voucher 冲突）→
 *       {@code getById(orderId) == null} → <b>保留</b> Redis 补偿。</li>
 * </ul>
 *
 * <p>运行命令：{@code mvn -o test -Dtest=SeckillStreamDuplicateConsumeTest -DfailIfNoTests=false}</p>
 */
@Slf4j
@SpringBootTest
@DisplayName("H2/A3 秒杀 Stream 并发重复消费与补偿分支语义")
public class SeckillStreamDuplicateConsumeTest {

    private static final String STREAM_KEY = RedisConstants.SECKILL_STREAM_KEY;

    /** 独立券 ID，避免与其它秒杀用例（999/997/996）冲突 */
    private static final long VOUCHER_ID = 995L;
    private static final long USER_ID = 970003L;

    /** 情况A：两个并发处理携带同一个 orderId（PK 冲突） */
    private static final long ORDER_A = 8800000000000000011L;
    /** 情况B：先落库的订单（uk_user_voucher 占用者） */
    private static final long ORDER_B_EXISTING = 8800000000000000012L;
    /** 情况B：后到消息携带的另一个 orderId */
    private static final long ORDER_B_NEW = 8800000000000000013L;

    private static final int REDIS_STOCK = 10;
    private static final int DB_STOCK = 100;

    @Resource
    private IVoucherOrderService voucherOrderService;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private JdbcTemplate jdbcTemplate;

    @Resource
    private DataSource dataSource;

    private String stockKey() {
        return RedisConstants.SECKILL_STOCK_KEY + VOUCHER_ID;
    }

    private String orderKey() {
        return RedisConstants.SECKILL_ORDER_KEY + VOUCHER_ID;
    }

    @BeforeEach
    void setUp() {
        cleanTestData();

        jdbcTemplate.update(
                "INSERT INTO tb_voucher (id, title, pay_value, actual_value, type, status) VALUES (?, ?, ?, ?, ?, ?)",
                VOUCHER_ID, "H2-dup-consume-test", 100, 1000, 1, 1);
        jdbcTemplate.update(
                "INSERT INTO tb_seckill_voucher (voucher_id, stock, begin_time, end_time) VALUES (?, ?, ?, ?)",
                VOUCHER_ID, DB_STOCK, LocalDateTime.now().minusMinutes(1), LocalDateTime.now().plusHours(2));
        jdbcTemplate.update(
                "INSERT INTO tb_user (id, phone, nick_name, role) VALUES (?, ?, ?, 0)",
                USER_ID, String.format("198%08d", USER_ID % 100000000), "H2DupUser_" + USER_ID);

        stringRedisTemplate.opsForValue().set(stockKey(), String.valueOf(REDIS_STOCK));
        stringRedisTemplate.opsForSet().add(orderKey(), String.valueOf(USER_ID));
    }

    @AfterEach
    void tearDown() {
        cleanTestData();
    }

    private void cleanTestData() {
        jdbcTemplate.update("DELETE FROM tb_voucher_order WHERE voucher_id = ?", VOUCHER_ID);
        jdbcTemplate.update("DELETE FROM tb_seckill_voucher WHERE voucher_id = ?", VOUCHER_ID);
        jdbcTemplate.update("DELETE FROM tb_voucher WHERE id = ?", VOUCHER_ID);
        jdbcTemplate.update("DELETE FROM tb_user WHERE id = ?", USER_ID);
        stringRedisTemplate.delete(stockKey());
        stringRedisTemplate.delete(orderKey());
    }

    // -----------------------------------------------------------------
    // 用例
    // -----------------------------------------------------------------

    @Test
    @DisplayName("T-H2-3a 并发同 orderId：撞 PK 且 getById(orderId)!=null → 跳过 Redis 补偿")
    void concurrentSameOrderIdMustNotCompensate() throws Exception {
        assertThat(stringRedisTemplate.opsForValue().get(stockKey()))
                .as("前置：Redis 预扣后库存").isEqualTo(String.valueOf(REDIS_STOCK));

        // T：未提交插入 ORDER_A，持有其 PK 排他锁
        Connection t = openUncommittedInsert(ORDER_A);
        Thread b = null;
        try {
            // B：并发处理同一 orderId 的消息（模拟 XCLAIM 认领重叠）
            b = startHandleStreamRecord(ORDER_A, USER_ID, VOUCHER_ID);

            assertThat(awaitInsertLockWait(10_000))
                    .as("B 应阻塞在 tb_voucher_order 的 INSERT 上（证明其已通过 H3 幂等检查）")
                    .isTrue();

            // 放行 T → B 的 INSERT 立即撞 PK 主键冲突
            commitAndClose(t);
            t = null;
            b.join(10_000);
            assertThat(b.isAlive()).as("B 线程应在收到 DuplicateKeyException 后正常结束").isFalse();
        } finally {
            if (t != null) {
                rollbackAndClose(t);
            }
            if (b != null) {
                b.join(5_000);
            }
        }

        assertThat(stringRedisTemplate.opsForValue().get(stockKey()))
                .as("getById(orderId)!=null → 不得补偿 Redis（否则 R−D 永久 +1 漂移）")
                .isEqualTo(String.valueOf(REDIS_STOCK));
        assertThat(orderCount()).as("并发同 orderId 只允许一条订单").isEqualTo(1);
        assertThat(dbStock()).as("B 的事务整体回滚，DB 库存不得被扣减").isEqualTo(DB_STOCK);
    }

    @Test
    @DisplayName("T-H2-3b 并发不同 orderId 同 user+voucher：撞 uk_user_voucher 且 getById(orderId)==null → 保留 Redis 补偿")
    void concurrentDifferentOrderIdMustCompensate() throws Exception {
        // T：未提交插入 ORDER_B_EXISTING，持有 uk_user_voucher 的排他锁
        Connection t = openUncommittedInsert(ORDER_B_EXISTING);
        Thread b = null;
        try {
            // B：并发处理另一个 orderId、但同一 (user,voucher) 的消息
            b = startHandleStreamRecord(ORDER_B_NEW, USER_ID, VOUCHER_ID);

            assertThat(awaitInsertLockWait(10_000))
                    .as("B 应阻塞在 tb_voucher_order 的 INSERT 上（证明其已通过 H3 幂等检查）")
                    .isTrue();

            commitAndClose(t);
            t = null;
            b.join(10_000);
            assertThat(b.isAlive()).as("B 线程应在收到 DuplicateKeyException 后正常结束").isFalse();
        } finally {
            if (t != null) {
                rollbackAndClose(t);
            }
            if (b != null) {
                b.join(5_000);
            }
        }

        assertThat(stringRedisTemplate.opsForValue().get(stockKey()))
                .as("getById(orderId)==null（本消息自身未落库）→ 应保留 Redis 预扣补偿")
                .isEqualTo(String.valueOf(REDIS_STOCK + 1));
        assertThat(stringRedisTemplate.opsForSet().isMember(orderKey(), String.valueOf(USER_ID)))
                .as("补偿后应清除用户占位")
                .isFalse();
        assertThat(orderCount()).as("只有先落库的那条订单存在").isEqualTo(1);
        assertThat(dbStock()).as("B 的事务整体回滚，DB 库存不得被扣减").isEqualTo(DB_STOCK);
    }

    // -----------------------------------------------------------------
    // 辅助
    // -----------------------------------------------------------------

    /** 反射调用生产私有方法 {@code handleStreamRecord}（不改变其可见性） */
    private void invokeHandleStreamRecord(long orderId, long userId, long voucherId) throws Exception {
        Object target = AopTestUtils.getTargetObject(voucherOrderService);
        Method m = target.getClass().getDeclaredMethod("handleStreamRecord", MapRecord.class);
        m.setAccessible(true);
        Map<Object, Object> fields = new HashMap<>();
        fields.put("orderId", String.valueOf(orderId));
        fields.put("userId", String.valueOf(userId));
        fields.put("voucherId", String.valueOf(voucherId));
        MapRecord<String, Object, Object> record =
                MapRecord.create(STREAM_KEY, fields).withId(RecordId.of("1-1"));
        m.invoke(target, record);
    }

    private Thread startHandleStreamRecord(long orderId, long userId, long voucherId) {
        Thread t = new Thread(() -> {
            try {
                invokeHandleStreamRecord(orderId, userId, voucherId);
            } catch (Throwable e) {
                // DuplicateKeyException 已在生产代码内消化；其它异常记录后结束线程
                log.warn("handleStreamRecord 线程异常结束, orderId={}", orderId, e);
            }
        }, "h2-dup-consume-b-" + orderId);
        t.setDaemon(true);
        t.start();
        return t;
    }

    /** 打开一个未提交事务并插入指定 orderId 的订单行（持有 PK / uk_user_voucher 排他锁） */
    private Connection openUncommittedInsert(long orderId) throws Exception {
        Connection conn = dataSource.getConnection();
        conn.setAutoCommit(false);
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO tb_voucher_order (id, user_id, voucher_id, pay_type, status, is_deleted) "
                        + "VALUES (?, ?, ?, 1, 1, 0)")) {
            ps.setLong(1, orderId);
            ps.setLong(2, USER_ID);
            ps.setLong(3, VOUCHER_ID);
            ps.executeUpdate();
        }
        return conn;
    }

    /**
     * 等待 B 阻塞在 tb_voucher_order 的 INSERT 上，证明其已通过 H3 幂等检查并卡在落库。
     * <p>
     * 不使用 {@code information_schema.INNODB_TRX}：MySQL 8.0 中该视图已废弃，实测在本场景下
     * 不包含阻塞中的 INSERT 事务（只有持有锁的空闲事务可见）。改用
     * {@code performance_schema.data_lock_waits} 联表定位「请求锁的线程当前正在执行本测试的 INSERT」，
     * 该条件精确且无需等待计时器。
     * </p>
     */
    private boolean awaitInsertLockWait(long timeoutMs) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            Integer n = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM performance_schema.data_lock_waits w "
                            + "JOIN performance_schema.threads t ON t.THREAD_ID = w.REQUESTING_THREAD_ID "
                            + "JOIN information_schema.PROCESSLIST p ON p.ID = t.PROCESSLIST_ID "
                            + "WHERE p.INFO LIKE 'INSERT INTO tb_voucher_order%'",
                    Integer.class);
            if (n != null && n > 0) {
                return true;
            }
            Thread.sleep(20);
        }
        log.warn("[diag] 等待落库阻塞超时, PROCESSLIST={}, LOCK_WAITS={}",
                jdbcTemplate.queryForList(
                        "SELECT id, state, time, LEFT(IFNULL(info,''),90) AS info "
                                + "FROM information_schema.PROCESSLIST WHERE command <> 'Sleep'"),
                jdbcTemplate.queryForList("SELECT * FROM performance_schema.data_lock_waits"));
        return false;
    }

    private void commitAndClose(Connection conn) {
        try {
            conn.commit();
        } catch (Exception e) {
            throw new IllegalStateException("提交测试事务失败", e);
        } finally {
            closeQuietly(conn);
        }
    }

    private void rollbackAndClose(Connection conn) {
        try {
            conn.rollback();
        } catch (Exception ignored) {
            // 回滚失败无需处理
        } finally {
            closeQuietly(conn);
        }
    }

    private void closeQuietly(Connection conn) {
        try {
            conn.close();
        } catch (Exception ignored) {
            // 关闭失败无需处理
        }
    }

    private int orderCount() {
        Integer c = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM tb_voucher_order WHERE voucher_id = ? AND is_deleted = 0",
                Integer.class, VOUCHER_ID);
        return c == null ? 0 : c;
    }

    private int dbStock() {
        Integer s = jdbcTemplate.queryForObject(
                "SELECT stock FROM tb_seckill_voucher WHERE voucher_id = ?", Integer.class, VOUCHER_ID);
        return s == null ? -1 : s;
    }
}
