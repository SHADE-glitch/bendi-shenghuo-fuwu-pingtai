package com.hmdp.test;

import com.hmdp.service.IVoucherOrderService;
import com.hmdp.utils.RedisConstants;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.Range;
import org.springframework.data.redis.connection.stream.PendingMessage;
import org.springframework.data.redis.connection.stream.PendingMessages;
import org.springframework.data.redis.connection.stream.RecordId;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.AopTestUtils;
import org.springframework.test.util.ReflectionTestUtils;

import jakarta.annotation.Resource;
import java.lang.reflect.Method;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * H2 回归测试：Redis Stream 陈旧 Pending 消息的跨进程认领恢复。
 *
 * <p>背景：消费进程的 {@code consumerName} 每次启动随机生成，而 pending 重放只读
 * <b>本 consumer 自己的 PEL</b>。进程在「XREADGROUP 已投递、业务未完成、ACK 未发出」
 * 之间退出时，消息会滞留在旧 consumer 的 PEL 中，新进程永远读不到 → 永久搁浅。
 * {@code claimStaleMessages()} 用 XPENDING + XCLAIM 把「非本 consumer 且 idle ≥ 60s」
 * 的消息过户到本 consumer，再复用既有 {@code handlePendingList()} 流程重放。</p>
 *
 * <p>覆盖：</p>
 * <ul>
 *   <li><b>T-H2-1</b>：陈旧 Pending（idle ≥ 60s，owner=其他 consumer）→ 被认领 → 重放落库 → ACK 清 PEL。</li>
 *   <li><b>T-H2-1b</b>：认领归属的<b>确定性</b>断言——owner 由其他 consumer 变为本进程 consumer。</li>
 *   <li><b>T-H2-2</b>：误抢保护——idle &lt; 60s 的 Pending 不被认领、不被处理（未落库）。</li>
 * </ul>
 *
 * <p>测试数据构造：内联 Lua 在同一原子脚本内完成 {@code XADD + XCLAIM ... FORCE IDLE}，
 * 并当场读回 owner / idle，避免与后台消费线程产生时序竞争。</p>
 *
 * <p>运行命令：{@code mvn -o test -Dtest=SeckillStreamClaimRecoveryTest -DfailIfNoTests=false}</p>
 */
@Slf4j
@SpringBootTest
@DisplayName("H2 秒杀 Stream 陈旧 Pending 认领恢复回归")
public class SeckillStreamClaimRecoveryTest {

    private static final String STREAM_KEY = RedisConstants.SECKILL_STREAM_KEY;
    private static final String GROUP = RedisConstants.SECKILL_STREAM_GROUP;

    /** 独立券 ID，避免与 VoucherOrderTest(999) / SeckillStreamCompensationTest(997) 冲突 */
    private static final long VOUCHER_ID = 996L;
    /** 独立用户 ID（真实存在，落库路径可成功） */
    private static final long USER_ID = 970002L;
    /** 故意不存在的用户 ID：用于构造「会被认领但不会 ACK」的消息（FK 违约） */
    private static final long MISSING_USER_ID = 970098L;

    private static final long ORDER_RECOVER = 8800000000000000003L;
    private static final long ORDER_CLAIM_MECH = 8800000000000000004L;
    private static final long ORDER_NOT_STOLEN = 8800000000000000005L;

    private static final String FOREIGN_CONSUMER = "consumer-foreign-stale-test";
    private static final int REDIS_STOCK = 10;
    private static final int DB_STOCK = 100;

    /**
     * 原子构造一条「挂在其他 consumer 名下、且不会被 {@code XREADGROUP >} 重新投递」的
     * 搁浅 Pending 消息，并当场返回 {id, owner, idle}。
     * <ul>
     *   <li>{@code XADD} 写入消息</li>
     *   <li>{@code XGROUP SETID} 把组游标推进到该消息 id —— 这一步必不可少：
     *       {@code XCLAIM ... FORCE} 只创建 PEL 条目，<b>不会</b>推进组游标，
     *       若不推进游标，后台消费线程的 {@code XREADGROUP >} 仍会把该消息当作
     *       「从未投递」重新投递给本 consumer，从而绕过认领逻辑。</li>
     *   <li>{@code XCLAIM ... FORCE IDLE <ms>} 强制把 PEL 归属置为指定 consumer 并设定 idle</li>
     *   <li>{@code XPENDING id id 1} 原子读回 owner / idle，作为「原 owner」的确定性证据</li>
     * </ul>
     */
    private static final String INJECT_STALE_PENDING_LUA =
            "local id = redis.call('XADD', KEYS[1], '*', 'orderId', ARGV[1], 'userId', ARGV[2], 'voucherId', ARGV[3]) "
                    + "redis.call('XGROUP', 'SETID', KEYS[1], KEYS[2], id) "
                    + "redis.call('XCLAIM', KEYS[1], KEYS[2], ARGV[4], 0, id, 'FORCE', 'IDLE', ARGV[5]) "
                    + "local p = redis.call('XPENDING', KEYS[1], KEYS[2], id, id, 1) "
                    + "local owner = '' "
                    + "local idle = 0 "
                    + "if p and #p > 0 and p[1] then owner = p[1][2]; idle = p[1][3] end "
                    + "return id .. '|' .. owner .. '|' .. tostring(idle)";

    @Resource
    private IVoucherOrderService voucherOrderService;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private JdbcTemplate jdbcTemplate;

    /** 本用例注入的 recordId，tearDown 统一 XACK + XDEL，避免污染其它用例 */
    private final List<RecordId> injectedIds = new ArrayList<>();

    private String stockKey() {
        return RedisConstants.SECKILL_STOCK_KEY + VOUCHER_ID;
    }

    private String orderKey() {
        return RedisConstants.SECKILL_ORDER_KEY + VOUCHER_ID;
    }

    @BeforeEach
    void setUp() {
        cleanTestData();
        ensureStreamGroup();

        jdbcTemplate.update(
                "INSERT INTO tb_voucher (id, title, pay_value, actual_value, type, status) VALUES (?, ?, ?, ?, ?, ?)",
                VOUCHER_ID, "H2-claim-test", 100, 1000, 1, 1);
        jdbcTemplate.update(
                "INSERT INTO tb_seckill_voucher (voucher_id, stock, begin_time, end_time) VALUES (?, ?, ?, ?)",
                VOUCHER_ID, DB_STOCK, LocalDateTime.now().minusMinutes(1), LocalDateTime.now().plusHours(2));
        jdbcTemplate.update(
                "INSERT INTO tb_user (id, phone, nick_name, role) VALUES (?, ?, ?, 0)",
                USER_ID, String.format("198%08d", USER_ID % 100000000), "H2ClaimUser_" + USER_ID);

        stringRedisTemplate.opsForValue().set(stockKey(), String.valueOf(REDIS_STOCK));
        stringRedisTemplate.opsForSet().add(orderKey(), String.valueOf(USER_ID));
    }

    @AfterEach
    void tearDown() {
        // 先摘除本用例注入的 Pending（H2-2 / H2-1b 的消息不会被 ACK，必须手工清理）
        for (RecordId id : injectedIds) {
            try {
                stringRedisTemplate.opsForStream().acknowledge(STREAM_KEY, GROUP, id);
                stringRedisTemplate.opsForStream().delete(STREAM_KEY, id);
            } catch (Exception e) {
                log.warn("清理注入 Pending 失败, id={}", id, e);
            }
        }
        injectedIds.clear();
        cleanTestData();
    }

    private void cleanTestData() {
        jdbcTemplate.update("DELETE FROM tb_voucher_order WHERE voucher_id = ?", VOUCHER_ID);
        jdbcTemplate.update("DELETE FROM tb_seckill_voucher WHERE voucher_id = ?", VOUCHER_ID);
        jdbcTemplate.update("DELETE FROM tb_voucher WHERE id = ?", VOUCHER_ID);
        jdbcTemplate.update("DELETE FROM tb_user WHERE id = ?", USER_ID);
        jdbcTemplate.update("DELETE FROM tb_user WHERE id = ?", MISSING_USER_ID);
        stringRedisTemplate.delete(stockKey());
        stringRedisTemplate.delete(orderKey());
    }

    // -----------------------------------------------------------------
    // 用例
    // -----------------------------------------------------------------

    @Test
    @DisplayName("T-H2-1 陈旧 Pending（idle>=60s，owner=其他 consumer）被认领 → 重放落库 → ACK 清 PEL")
    void stalePendingIsClaimedAndRecovered() throws Exception {
        String[] injected = injectForeignPending(ORDER_RECOVER, USER_ID, VOUCHER_ID, 120_000L);
        RecordId id = RecordId.of(injected[0]);
        String ownerBefore = injected[1];
        long idleBefore = Long.parseLong(injected[2]);

        // 「原 owner」证据：注入原子脚本内读回
        assertThat(ownerBefore).as("注入后消息应挂在其他 consumer 名下（搁浅状态）").isEqualTo(FOREIGN_CONSUMER);
        assertThat(idleBefore).as("注入 idle 应 >= 60s，落入可认领区间").isGreaterThanOrEqualTo(60_000L);

        String ours = ourConsumerName();
        assertThat(ours).as("本进程 consumerName 应已初始化").isNotBlank();

        // 显式触发认领（生产链路由 handlePendingList 调用同一方法）
        invokeClaimStaleMessages();

        // 「claim 后 owner」证据：认领后若消息仍在 PEL，owner 必须已是本进程 consumer；
        // 若已为空，说明后台消费线程抢先完成处理并 ACK（同样证明恢复生效）。
        PendingMessage after = findPending(id);
        if (after != null) {
            assertThat(after.getConsumerName())
                    .as("认领后 owner 必须变为本进程 consumer")
                    .isEqualTo(ours);
        }
        log.info("H2-1 归属观测: ownerBefore={}, ownerAfter={}",
                ownerBefore, after == null ? "<已 ACK>" : after.getConsumerName());

        // 「落库结果」证据
        assertThat(awaitOrderPersisted(ORDER_RECOVER, 15_000))
                .as("陈旧 Pending 应在认领后被重放并落库")
                .isTrue();

        // 「XPENDING 结果」证据
        assertThat(awaitPendingGone(id, 15_000))
                .as("落库后消息应被 ACK，PEL 中不再残留")
                .isTrue();

        assertThat(orderCount()).as("应且仅应落库一条订单").isEqualTo(1);
        assertThat(dbStock()).as("DB 库存应扣减 1").isEqualTo(DB_STOCK - 1);
        assertThat(stringRedisTemplate.opsForValue().get(stockKey()))
                .as("正常恢复落库不得产生 Redis 补偿（库存保持预扣后的值）")
                .isEqualTo(String.valueOf(REDIS_STOCK));
    }

    @Test
    @DisplayName("T-H2-1b 认领归属确定性：owner 由其他 consumer 变为本进程 consumer")
    void claimMovesOwnershipToCurrentConsumer() throws Exception {
        // 说明：使用「userId 不存在 → 落库触发 FK 违约（非 DuplicateKeyException）」构造一条
        //      「会被认领但永远不会被 ACK」的消息，使 owner 归属变化可被稳定断言，不依赖落库/ACK 时序。
        //      该消息形态仅用于观测归属，不代表生产消息形态。
        String[] injected = injectForeignPending(ORDER_CLAIM_MECH, MISSING_USER_ID, VOUCHER_ID, 120_000L);
        RecordId id = RecordId.of(injected[0]);
        assertThat(injected[1]).as("注入后 owner 应为其他 consumer").isEqualTo(FOREIGN_CONSUMER);
        assertThat(Long.parseLong(injected[2])).isGreaterThanOrEqualTo(60_000L);

        String ours = ourConsumerName();
        invokeClaimStaleMessages();

        // 该消息不会被 ACK，PEL 中稳定可见
        PendingMessage pm = awaitPending(id, 5_000);
        assertThat(pm).as("消息应仍在 PEL 中（未被 ACK）").isNotNull();
        assertThat(pm.getConsumerName())
                .as("认领后 owner 必须变为本进程 consumer（原 owner=%s）", FOREIGN_CONSUMER)
                .isEqualTo(ours);
        assertThat(pm.getTotalDeliveryCount()).as("认领会刷新投递次数").isGreaterThan(1L);

        // 未落库证明
        assertThat(orderCount()).as("FK 违约 → 事务回滚 → 不得落库").isZero();
    }

    @Test
    @DisplayName("T-H2-2 误抢保护：idle<60s 的 Pending 不被认领、不被处理")
    void recentPendingIsNotStolen() throws Exception {
        String[] injected = injectForeignPending(ORDER_NOT_STOLEN, USER_ID, VOUCHER_ID, 5_000L);
        RecordId id = RecordId.of(injected[0]);
        assertThat(injected[1]).as("注入后 owner 应为其他 consumer").isEqualTo(FOREIGN_CONSUMER);
        assertThat(Long.parseLong(injected[2])).as("注入 idle 应 < 60s").isLessThan(60_000L);

        invokeClaimStaleMessages();

        PendingMessage pm = findPending(id);
        assertThat(pm).as("idle 不足阈值的 Pending 不应被认领").isNotNull();
        assertThat(pm.getConsumerName()).as("owner 应保持为原 consumer").isEqualTo(FOREIGN_CONSUMER);

        // 静置一段时间，确认后台消费循环同样不抢
        Thread.sleep(3_000);
        PendingMessage still = findPending(id);
        assertThat(still).as("静置后消息仍应在 PEL 中").isNotNull();
        assertThat(still.getConsumerName()).as("owner 仍应保持为原 consumer").isEqualTo(FOREIGN_CONSUMER);
        assertThat(still.getElapsedTimeSinceLastDelivery())
                .as("idle 应仍小于认领阈值")
                .isLessThan(Duration.ofSeconds(60));

        // 未落库证明
        assertThat(orderCount()).as("未被认领 → 不得被处理落库").isZero();
        assertThat(dbStock()).as("未被处理 → DB 库存不变").isEqualTo(DB_STOCK);
    }

    // -----------------------------------------------------------------
    // 辅助
    // -----------------------------------------------------------------

    /** 原子注入一条挂在 {@value #FOREIGN_CONSUMER} 名下的 Pending 消息，返回 {id, owner, idle} */
    private String[] injectForeignPending(long orderId, long userId, long voucherId, long idleMs) {
        DefaultRedisScript<String> script = new DefaultRedisScript<>(INJECT_STALE_PENDING_LUA, String.class);
        String result = stringRedisTemplate.execute(
                script,
                Arrays.asList(STREAM_KEY, GROUP),
                String.valueOf(orderId), String.valueOf(userId), String.valueOf(voucherId),
                FOREIGN_CONSUMER, String.valueOf(idleMs));
        assertThat(result).as("注入 Lua 脚本返回为空").isNotNull();
        String[] parts = result.split("\\|", -1);
        assertThat(parts).as("注入 Lua 返回值格式异常: %s", result).hasSize(3);
        injectedIds.add(RecordId.of(parts[0]));
        return parts;
    }

    /** 反射调用生产私有方法 {@code claimStaleMessages()}（不改变其可见性） */
    private void invokeClaimStaleMessages() throws Exception {
        Object target = AopTestUtils.getTargetObject(voucherOrderService);
        Method m = target.getClass().getDeclaredMethod("claimStaleMessages");
        m.setAccessible(true);
        m.invoke(target);
    }

    /** 反射读取本进程随机 consumerName */
    private String ourConsumerName() {
        Object target = AopTestUtils.getTargetObject(voucherOrderService);
        Object name = ReflectionTestUtils.getField(target, "consumerName");
        return name == null ? null : name.toString();
    }

    private PendingMessage findPending(RecordId id) {
        PendingMessages pending = stringRedisTemplate.opsForStream().pending(
                STREAM_KEY, GROUP, Range.unbounded(), 200L);
        if (pending == null) {
            return null;
        }
        for (PendingMessage pm : pending) {
            if (pm.getId().equals(id)) {
                return pm;
            }
        }
        return null;
    }

    private PendingMessage awaitPending(RecordId id, long timeoutMs) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        PendingMessage last = null;
        while (System.currentTimeMillis() < deadline) {
            last = findPending(id);
            if (last != null) {
                return last;
            }
            Thread.sleep(100);
        }
        return last;
    }

    private boolean awaitPendingGone(RecordId id, long timeoutMs) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            if (findPending(id) == null) {
                return true;
            }
            Thread.sleep(200);
        }
        return false;
    }

    private boolean awaitOrderPersisted(long orderId, long timeoutMs) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            Integer c = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM tb_voucher_order WHERE id = ?", Integer.class, orderId);
            if (c != null && c > 0) {
                return true;
            }
            Thread.sleep(200);
        }
        return false;
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

    private void ensureStreamGroup() {
        try {
            if (Boolean.FALSE.equals(stringRedisTemplate.hasKey(STREAM_KEY))) {
                stringRedisTemplate.opsForStream().createGroup(STREAM_KEY, GROUP);
            }
        } catch (Exception ignored) {
            // 组已存在时忽略（BUSYGROUP）
        }
    }
}
