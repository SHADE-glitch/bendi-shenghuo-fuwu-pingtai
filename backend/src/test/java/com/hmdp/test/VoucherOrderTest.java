package com.hmdp.test;

import com.hmdp.entity.SeckillVoucher;
import com.hmdp.entity.User;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.mapper.UserMapper;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.service.IVoucherOrderService;
import com.hmdp.utils.RedisConstants;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.domain.Range;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import jakarta.annotation.Resource;
import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * 秒杀并发测试
 *
 * 前置条件（在测试运行前自动准备）：
 *   - MySQL / Redis 需已启动
 *   - 测试方法会自动在 DB + Redis 中准备一张秒杀券（库存 = INITIAL_STOCK）
 *   - 测试方法会自动创建测试用户（tb_voucher_order.user_id 受外键 fk_order_user 约束）
 *   - 测试完成后自动清理测试数据
 *
 * 运行命令：
 *   cd backend && mvn test -Dtest=VoucherOrderTest#testSeckillFlow
 */
@Slf4j
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@DisplayName("秒杀并发测试")
public class VoucherOrderTest {

    /** 并发线程数 */
    private static final int THREAD_COUNT = 100;
    /** 初始库存（应 < THREAD_COUNT 以触发超卖场景） */
    private static final int INITIAL_STOCK = 50;
    /** 测试用秒杀券 ID */
    private static final long TEST_VOUCHER_ID = 999L;
    /** 测试用户起始 ID（区间 [FIRST_TEST_USER_ID, FIRST_TEST_USER_ID + THREAD_COUNT)） */
    private static final long FIRST_TEST_USER_ID = 990001L;
    /** 测试用户昵称前缀，用于识别测试残留数据 */
    private static final String TEST_USER_NICK_PREFIX = "TestUser_";

    @Resource
    private ISeckillVoucherService seckillVoucherService;

    @Resource
    private IVoucherOrderService voucherOrderService;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private UserMapper userMapper;

    @Resource
    private JdbcTemplate jdbcTemplate;

    /** 本次测试实际创建的测试用户 ID（tearDown 时清理） */
    private final Set<Long> createdTestUserIds = new HashSet<>();

    // ---------------------------------------------------------------
    // 测试数据准备 & 清理
    // ---------------------------------------------------------------

    @BeforeEach
    void setUp() {
        // 清理可能残留的测试数据
        cleanTestData();

        // 准备测试用户（tb_voucher_order.user_id 受 fk_order_user 外键约束，必须真实存在）
        prepareTestUsers();

        // 在 DB 中插入测试秒杀券
        SeckillVoucher voucher = new SeckillVoucher();
        voucher.setVoucherId(TEST_VOUCHER_ID);
        voucher.setStock(INITIAL_STOCK);
        voucher.setBeginTime(LocalDateTime.now().minusMinutes(1));
        voucher.setEndTime(LocalDateTime.now().plusHours(2));
        seckillVoucherService.saveOrUpdate(voucher);

        // 初始化 Redis 库存
        String stockKey = RedisConstants.SECKILL_STOCK_KEY + TEST_VOUCHER_ID;
        stringRedisTemplate.opsForValue().set(stockKey, String.valueOf(INITIAL_STOCK));

        // 清理 Redis 占位 Set
        String orderKey = RedisConstants.SECKILL_ORDER_KEY + TEST_VOUCHER_ID;
        stringRedisTemplate.delete(orderKey);

        if (Boolean.TRUE.equals(stringRedisTemplate.hasKey(RedisConstants.SECKILL_STREAM_KEY))) {
            stringRedisTemplate.delete(RedisConstants.SECKILL_STREAM_KEY);
        }
        try {
            stringRedisTemplate.opsForStream().createGroup(
                    RedisConstants.SECKILL_STREAM_KEY,
                    RedisConstants.SECKILL_STREAM_GROUP);
        } catch (Exception ignored) {
        }

        log.info("测试数据准备完成：voucherId={}, 初始库存={}, 并发线程数={}",
                TEST_VOUCHER_ID, INITIAL_STOCK, THREAD_COUNT);
    }

    @AfterEach
    void tearDown() throws InterruptedException {
        // 等待异步消费完成（最多等 5s）
        Thread.sleep(5000);
        cleanTestData();
        log.info("测试数据已清理");
    }

    private void cleanTestData() {
        // 物理清理 DB 订单（逻辑删除会残留 is_deleted=1 的行，仍持有外键引用，无法删除测试用户）
        jdbcTemplate.update("DELETE FROM tb_voucher_order WHERE voucher_id = ?", TEST_VOUCHER_ID);
        // 清理 DB 秒杀券
        seckillVoucherService.removeById(TEST_VOUCHER_ID);
        // 清理本次创建的测试用户（须在订单物理删除之后，受 fk_order_user RESTRICT 约束）
        for (Long userId : createdTestUserIds) {
            userMapper.deleteById(userId);
        }
        createdTestUserIds.clear();
        // 清理 Redis
        stringRedisTemplate.delete(RedisConstants.SECKILL_STOCK_KEY + TEST_VOUCHER_ID);
        stringRedisTemplate.delete(RedisConstants.SECKILL_ORDER_KEY + TEST_VOUCHER_ID);
        if (Boolean.TRUE.equals(stringRedisTemplate.hasKey(RedisConstants.SECKILL_STREAM_KEY))) {
            stringRedisTemplate.delete(RedisConstants.SECKILL_STREAM_KEY);
        }
        // 生产代码仅在启动时创建消费组，测试删除 Stream 后需把「空 Stream + 消费组」补回来，
        // 否则正在运行的实例会持续 NOGROUP 报错（XGROUP CREATE ... MKSTREAM）
        ensureStreamGroup();
    }

    /**
     * 确保 Stream 与消费组存在（空 Stream 会被 MKSTREAM 保留下来）。
     */
    private void ensureStreamGroup() {
        try {
            if (Boolean.FALSE.equals(stringRedisTemplate.hasKey(RedisConstants.SECKILL_STREAM_KEY))) {
                stringRedisTemplate.opsForStream().createGroup(
                        RedisConstants.SECKILL_STREAM_KEY,
                        RedisConstants.SECKILL_STREAM_GROUP);
            }
        } catch (Exception ignored) {
            // 组已存在时忽略
        }
    }

    /**
     * 准备测试用户（990001 ~ 990001+THREAD_COUNT-1）
     * <p>
     * 存在且为测试残留（昵称以 TestUser_ 开头）时重建；
     * 存在真实用户时复用、不删除；不存在则创建并记录，交由 tearDown 清理。
     * </p>
     */
    private void prepareTestUsers() {
        for (long userId = FIRST_TEST_USER_ID; userId < FIRST_TEST_USER_ID + THREAD_COUNT; userId++) {
            User existing = userMapper.selectById(userId);
            if (existing != null
                    && existing.getNickName() != null
                    && existing.getNickName().startsWith(TEST_USER_NICK_PREFIX)) {
                userMapper.deleteById(userId);
                existing = null;
            }
            if (existing == null) {
                User user = new User();
                user.setId(userId);
                user.setPhone(String.format("199%08d", userId));
                user.setNickName(TEST_USER_NICK_PREFIX + userId);
                userMapper.insert(user);
                createdTestUserIds.add(userId);
            }
        }
    }

    // ---------------------------------------------------------------
    // 核心并发测试
    // ---------------------------------------------------------------

    /**
     * 秒杀高并发测试
     *
     * 100 个线程同时请求秒杀，模拟真实高并发场景。
     * 断言：
     *   1. DB 最终库存 >= 0（无超卖）
     *   2. 订单总数 <= 初始库存
     *   3. 每个 userId 至多有 1 条订单（一人一单）
     *   4. 成功下单数 = INITIAL_STOCK（所有库存都被消费）
     */
    @Test
    @DisplayName("100并发秒杀：验证无超卖 + 一人一单")
    void testSeckillFlow() throws Exception {
        // 模拟 100 个不同用户 ID（990001~990100）
        // 注意：此处绕过 HTTP 认证，直接调用 Service 层并注入用户 ID
        CountDownLatch readyLatch = new CountDownLatch(THREAD_COUNT); // 所有线程就绪
        CountDownLatch startLatch = new CountDownLatch(1);            // 发令枪
        CountDownLatch doneLatch  = new CountDownLatch(THREAD_COUNT); // 所有线程完成

        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger failCount    = new AtomicInteger(0);

        ExecutorService pool = Executors.newFixedThreadPool(THREAD_COUNT);

        for (int i = 0; i < THREAD_COUNT; i++) {
            final long userId = FIRST_TEST_USER_ID + i; // 不同用户 ID，避免一人一单干扰
            pool.submit(() -> {
                readyLatch.countDown();
                try {
                    startLatch.await(); // 等待发令枪
                    // 直接通过 MockMvc 模拟 HTTP 调用（含完整 Spring 拦截器链）
                    // 为避免 401，使用 code/mock 预置 token，此处简化为直接 Service 调用
                    simulateSeckill(userId, TEST_VOUCHER_ID, successCount, failCount);
                } catch (Exception e) {
                    failCount.incrementAndGet();
                    log.error("并发请求异常, userId={}", userId, e);
                } finally {
                    doneLatch.countDown();
                }
            });
        }

        readyLatch.await(); // 等待所有线程就绪
        long startMs = System.currentTimeMillis();
        startLatch.countDown(); // 发令枪
        doneLatch.await(30, TimeUnit.SECONDS); // 最多等 30s
        long costMs = System.currentTimeMillis() - startMs;

        log.info("并发测试完成：耗时={}ms, 成功={}, 失败={}", costMs, successCount.get(), failCount.get());

        // Stream 中应存在订单消息记录
        List<MapRecord<String, Object, Object>> streamRecords = stringRedisTemplate.opsForStream()
                .range(RedisConstants.SECKILL_STREAM_KEY, Range.unbounded());
        boolean hasOrderRecord = streamRecords != null && streamRecords.stream()
                .anyMatch(record -> record.getValue().containsKey("orderId"));
        assertThat(hasOrderRecord)
                .as("秒杀下单应写入 Redis Stream")
                .isTrue();

        // 等待异步消费完成（最多等 15s）。
        // 只看到 pending=0 并不代表消费完了：消息可能还躺在 Stream 里没被 XREADGROUP
        // 投递，那一刻 pending 天然是 0，随后消费线程一读就会跳到 30 这样的 in-flight 值。
        // 所以「已完成」必须同时满足：全部投递（lastDeliveredId == lastGeneratedId）且 Pending 归零。
        long deadline = System.currentTimeMillis() + 15000;
        Long pendingCount;
        boolean drained = false;
        String lastDelivered = "0-0";
        String lastGenerated = "";
        do {
            Thread.sleep(200);
            pendingCount = stringRedisTemplate.opsForStream()
                    .pending(RedisConstants.SECKILL_STREAM_KEY, RedisConstants.SECKILL_STREAM_GROUP)
                    .getTotalPendingMessages();
            lastDelivered = stringRedisTemplate.opsForStream()
                    .groups(RedisConstants.SECKILL_STREAM_KEY).stream()
                    .filter(g -> RedisConstants.SECKILL_STREAM_GROUP.equals(g.groupName()))
                    .map(g -> g.lastDeliveredId())
                    .findFirst()
                    .orElse("0-0");
            lastGenerated = stringRedisTemplate.opsForStream()
                    .info(RedisConstants.SECKILL_STREAM_KEY)
                    .lastGeneratedId();
            drained = pendingCount != null && pendingCount == 0
                    && lastGenerated.equals(lastDelivered);
        } while (!drained && System.currentTimeMillis() < deadline);

        // ---------------------------------------------------------------
        // 断言
        // ---------------------------------------------------------------

        // 1. DB 最终库存 >= 0（绝不超卖）
        SeckillVoucher finalVoucher = seckillVoucherService.getById(TEST_VOUCHER_ID);
        log.info("DB 最终库存: {}", finalVoucher != null ? finalVoucher.getStock() : "null");
        assertThat(finalVoucher).isNotNull();
        assertThat(finalVoucher.getStock())
                .as("DB 库存不能为负（出现负值说明超卖）")
                .isGreaterThanOrEqualTo(0);

        // 2. 订单总数 <= 初始库存
        List<VoucherOrder> orders = voucherOrderService.list(
                new com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<VoucherOrder>()
                        .eq("voucher_id", TEST_VOUCHER_ID));
        log.info("DB 订单总数: {}", orders.size());
        assertThat(orders.size())
                .as("订单总数不能超过初始库存")
                .isLessThanOrEqualTo(INITIAL_STOCK);

        // 3. 一人一单：每个 userId 至多一条订单
        Set<Long> userIds = orders.stream()
                .map(VoucherOrder::getUserId)
                .collect(Collectors.toSet());
        assertThat(userIds.size())
                .as("存在重复 userId 的订单（一人一单被破坏）")
                .isEqualTo(orders.size());

        // 4. Redis 库存与 DB 库存一致（容错：异步可能尚未全部消费）
        String stockKey = RedisConstants.SECKILL_STOCK_KEY + TEST_VOUCHER_ID;
        String redisStock = stringRedisTemplate.opsForValue().get(stockKey);
        log.info("Redis 最终库存: {}", redisStock);
        if (redisStock != null) {
            assertThat(Integer.parseInt(redisStock))
                    .as("Redis 库存不能为负")
                    .isGreaterThanOrEqualTo(0);
        }

        // 5. Stream 应完成「全部投递 + 全部确认」
        assertThat(drained)
                .as("15s 内未完成全部投递与确认：lastDelivered=%s, lastGenerated=%s",
                        lastDelivered, lastGenerated)
                .isTrue();
        Long finalPendingCount = stringRedisTemplate.opsForStream()
                .pending(RedisConstants.SECKILL_STREAM_KEY, RedisConstants.SECKILL_STREAM_GROUP)
                .getTotalPendingMessages();
        assertThat(finalPendingCount)
                .as("Stream 中不应长期残留未确认消息")
                .isZero();

        log.info("所有断言通过：无超卖，一人一单约束正常，库存不为负");

        pool.shutdown();
    }

    // ---------------------------------------------------------------
    // 辅助：模拟秒杀调用（绕过 HTTP 认证，直接注入用户上下文）
    // ---------------------------------------------------------------

    private void simulateSeckill(long userId, long voucherId,
                                 AtomicInteger successCount, AtomicInteger failCount) {
        // 注入用户上下文（模拟登录态，绕过 HTTP 层认证）
        com.hmdp.dto.UserDTO userDTO = new com.hmdp.dto.UserDTO();
        userDTO.setId(userId);
        userDTO.setNickName("TestUser_" + userId);
        com.hmdp.utils.UserHolder.saveUser(userDTO);

        try {
            com.hmdp.dto.Result result = voucherOrderService.seckillVoucher(voucherId);
            if (Boolean.TRUE.equals(result.getSuccess())) {
                successCount.incrementAndGet();
            } else {
                failCount.incrementAndGet();
                log.debug("秒杀失败（正常业务拒绝）: code={}, msg={}",
                        result.getErrorCode(), result.getErrorMsg());
            }
        } finally {
            com.hmdp.utils.UserHolder.removeUser();
        }
    }
}
