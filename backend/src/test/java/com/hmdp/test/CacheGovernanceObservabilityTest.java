package com.hmdp.test;

import static com.hmdp.utils.RedisConstants.CACHE_NULL_TTL;
import static com.hmdp.utils.RedisConstants.CACHE_SHOP_KEY;
import static com.hmdp.utils.RedisConstants.CACHE_SHOP_TTL;
import static com.hmdp.utils.RedisConstants.LOCK_SHOP_KEY;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.hmdp.entity.Shop;
import com.hmdp.test.support.WebIntegrationTestBase;
import com.hmdp.utils.CacheClient;
import jakarta.annotation.Resource;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 缓存治理只读观测测试（P1 缺口：穿透 / 击穿 / 雪崩此前只有手工 curl / ab 证据）
 *
 * <p>
 * <b>性质：只读观测，零实现改动。</b>{@link CacheClient} 与
 * {@code ShopServiceImpl.queryById} 都是亮点所依赖的实现，后端已锁定（见 `AGENTS.md`），
 * 本类只调用并观察其 Redis 副作用，不修改任何生产代码，符合「实现禁改，只能加只读
 * 观测性测试」的口径。
 * </p>
 *
 * <p>
 * <b>隔离性：</b>所有 Redis key 使用独立命名空间 / 独立 id 段（远离种子店铺与雪花 id），
 * 直插的店铺行与写入的 key 均在 {@link #tearDown()} 精确清理，不触碰种子数据与真实会话。
 * TTL 断言一律用<b>区间</b>（含 Redis 秒级截断容差），不断言精确值。
 * </p>
 *
 * <p>
 * <b>覆盖边界：</b>Lua 秒杀脚本无单测属冻结限制（禁止改动实现），不在本类范围。
 * </p>
 */
@DisplayName("缓存治理只读观测（穿透 / 击穿 / 雪崩）")
class CacheGovernanceObservabilityTest extends WebIntegrationTestBase {

    /** 穿透专用 key 前缀（独立命名空间，避免与其他测试的 key 冲突） */
    private static final String PEN_KEY_PREFIX = "cache:test:pen:";
    /** 雪崩专用 key 前缀 */
    private static final String AVAL_KEY_PREFIX = "cache:test:aval:";

    /** 基础 TTL：CACHE_SHOP_TTL = 30 分钟 = 1800 秒 */
    private static final long BASE_TTL_SECONDS = TimeUnit.MINUTES.toSeconds(CACHE_SHOP_TTL);
    /** CacheClient 的抖动上限（CACHE_TTL_JITTER_MAX_SECONDS = 300） */
    private static final long JITTER_MAX_SECONDS = 300L;
    /** Redis TTL 为秒级截断，留少量容差 */
    private static final long TTL_TOLERANCE_SECONDS = 10L;

    /** 独立测试 id 段：远离种子店铺（个位/百位）与雪花 id（18 位） */
    private static final AtomicLong SEQ = new AtomicLong(9_100_000_000_000_000_000L);

    @Resource
    private CacheClient cacheClient;

    private final List<String> redisKeysToClean = new ArrayList<>();

    @AfterEach
    void tearDown() {
        for (String key : redisKeysToClean) {
            stringRedisTemplate.delete(key);
        }
        redisKeysToClean.clear();
    }

    // ==================================================================
    // 缓存穿透（空值哨兵）
    // ==================================================================

    @Test
    @DisplayName("穿透：DB 不存在 → 写入空串哨兵，TTL 落在 (0, CACHE_NULL_TTL]")
    void penetrationWritesNullSentinel() {
        Long id = nextId();
        String key = PEN_KEY_PREFIX + id;
        redisKeysToClean.add(key);

        Shop result = cacheClient.handleCachePenetration(
                PEN_KEY_PREFIX, id, Shop.class, miss -> null, CACHE_SHOP_TTL, TimeUnit.MINUTES);

        assertThat(result).as("DB 不存在应返回 null").isNull();
        assertThat(stringRedisTemplate.opsForValue().get(key))
                .as("应写入空串哨兵（而非 null）")
                .isEqualTo("");
        Long ttl = stringRedisTemplate.getExpire(key, TimeUnit.SECONDS);
        assertThat(ttl)
                .as("哨兵 TTL 应为短 TTL（CACHE_NULL_TTL=2s）")
                .isNotNull()
                .isGreaterThan(0L)
                .isLessThanOrEqualTo(CACHE_NULL_TTL);
    }

    @Test
    @DisplayName("穿透：哨兵命中后不再回源 DB（第二次调用零回源）")
    void penetrationSentinelShortCircuitsDb() {
        Long id = nextId();
        String key = PEN_KEY_PREFIX + id;
        redisKeysToClean.add(key);

        // 第一次：写哨兵
        cacheClient.handleCachePenetration(
                PEN_KEY_PREFIX, id, Shop.class, miss -> null, CACHE_SHOP_TTL, TimeUnit.MINUTES);

        // 第二次：统计回源次数，应短路在哨兵处
        int[] dbHits = {0};
        Shop second = cacheClient.handleCachePenetration(
                PEN_KEY_PREFIX,
                id,
                Shop.class,
                miss -> {
                    dbHits[0]++;
                    return null;
                },
                CACHE_SHOP_TTL,
                TimeUnit.MINUTES);

        assertThat(second).isNull();
        assertThat(dbHits[0]).as("哨兵命中时不应回源 DB").isZero();
    }

    @Test
    @DisplayName("穿透（真实读链路）：GET /shops/{不存在} → 空串哨兵")
    void penetrationThroughRealEndpoint() throws Exception {
        Long id = nextId();
        String key = CACHE_SHOP_KEY + id;
        redisKeysToClean.add(key);

        mockMvc.perform(get("/shops/{id}", id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(false));

        assertThat(stringRedisTemplate.opsForValue().get(key))
                .as("真实 queryById 链路也应写入空串哨兵")
                .isEqualTo("");
        Long ttl = stringRedisTemplate.getExpire(key, TimeUnit.SECONDS);
        assertThat(ttl).isNotNull().isGreaterThan(0L).isLessThanOrEqualTo(CACHE_NULL_TTL);
    }

    // ==================================================================
    // 缓存击穿（互斥锁重建 + 降级）
    // ==================================================================

    @Test
    @DisplayName("击穿：未命中 → 回源重建，TTL 落在 [base, base+300]（随机抖动生效）")
    void breakdownRebuildsWithJitteredTtl() {
        Long id = nextId();
        String key = CACHE_SHOP_KEY + id;
        redisKeysToClean.add(key);

        Shop dbShop = new Shop().setId(id).setName("缓存观测店铺").setTypeId(1L);
        Shop first = cacheClient.handleCacheBreakdownWithMutex(
                CACHE_SHOP_KEY,
                LOCK_SHOP_KEY,
                id,
                Shop.class,
                miss -> dbShop,
                CACHE_SHOP_TTL,
                TimeUnit.MINUTES);

        assertThat(first).as("未命中应回源并返回数据").isNotNull();
        assertThat(first.getId()).isEqualTo(id);
        assertThat(stringRedisTemplate.opsForValue().get(key))
                .as("回源后应重建缓存")
                .isNotBlank();
        Long ttl = stringRedisTemplate.getExpire(key, TimeUnit.SECONDS);
        assertThat(ttl)
                .as("TTL 应为 基础 TTL + [0,300] 抖动")
                .isNotNull()
                .isGreaterThan(BASE_TTL_SECONDS - TTL_TOLERANCE_SECONDS)
                .isLessThanOrEqualTo(BASE_TTL_SECONDS + JITTER_MAX_SECONDS);
    }

    @Test
    @DisplayName("击穿：互斥锁被持续占用 → 等待窗口（一个租约）耗尽后降级回源一次")
    void breakdownFallsBackWhenLockHeld() {
        Long id = nextId();
        String key = CACHE_SHOP_KEY + id;
        String lockKey = LOCK_SHOP_KEY + id;
        redisKeysToClean.add(key);
        redisKeysToClean.add(lockKey);

        // 模拟「其他线程正在重建且重建超过一个完整租约」：锁不设 TTL，
        // 等待者的接管抢锁必然失败，只能走最后的降级回源（P1-5 新契约：
        // 窗口 = 锁租约 10s，而不是旧实现的 10×50ms）。
        stringRedisTemplate.opsForValue().set(lockKey, "1");

        int[] dbHits = {0};
        Shop dbShop = new Shop().setId(id).setName("缓存观测店铺-锁占用").setTypeId(1L);
        Shop result = cacheClient.handleCacheBreakdownWithMutex(
                CACHE_SHOP_KEY,
                LOCK_SHOP_KEY,
                id,
                Shop.class,
                miss -> {
                    dbHits[0]++;
                    return dbShop;
                },
                CACHE_SHOP_TTL,
                TimeUnit.MINUTES);

        assertThat(result).as("锁被占也应降级返回数据（不阻塞、不抛错）").isNotNull();
        assertThat(result.getId()).isEqualTo(id);
        assertThat(dbHits[0]).as("窗口耗尽后恰好回源一次").isEqualTo(1);
        assertThat(stringRedisTemplate.opsForValue().get(key))
                .as("降级回源后应重建缓存")
                .isNotBlank();
    }

    @Test
    @DisplayName("击穿：慢重建（700ms > 旧 500ms 窗口）+ 多等待者 → DB 只回源一次")
    void breakdownSlowRebuildServesAllWaiters() throws Exception {
        Long id = nextId();
        String key = CACHE_SHOP_KEY + id;
        redisKeysToClean.add(key);

        int waiters = 6;
        Shop dbShop = new Shop().setId(id).setName("缓存观测店铺-慢重建").setTypeId(1L);
        java.util.concurrent.atomic.AtomicInteger dbHits =
                new java.util.concurrent.atomic.AtomicInteger();
        java.util.concurrent.atomic.AtomicInteger failures =
                new java.util.concurrent.atomic.AtomicInteger();
        java.util.concurrent.CountDownLatch done = new java.util.concurrent.CountDownLatch(waiters + 1);
        java.util.concurrent.ExecutorService pool = Executors.newFixedThreadPool(waiters + 1);

        // 一个「慢重建者」：持锁期间 dbFallback 睡 700ms（旧实现的 500ms 窗口覆盖不到）
        pool.submit(() -> {
            try {
                Shop r = cacheClient.handleCacheBreakdownWithMutex(
                        CACHE_SHOP_KEY, LOCK_SHOP_KEY, id, Shop.class,
                        miss -> {
                            dbHits.incrementAndGet();
                            sleepQuietly(700);
                            return dbShop;
                        },
                        CACHE_SHOP_TTL, TimeUnit.MINUTES);
                if (r == null) {
                    failures.incrementAndGet();
                }
            } catch (Exception e) {
                failures.incrementAndGet();
            } finally {
                done.countDown();
            }
        });
        // N 个等待者：与慢重建者并发，全部应读到重建结果且零回源
        for (int i = 0; i < waiters; i++) {
            pool.submit(() -> {
                try {
                    Shop r = cacheClient.handleCacheBreakdownWithMutex(
                            CACHE_SHOP_KEY, LOCK_SHOP_KEY, id, Shop.class,
                            miss -> {
                                dbHits.incrementAndGet();
                                return dbShop;
                            },
                            CACHE_SHOP_TTL, TimeUnit.MINUTES);
                    if (r == null || !id.equals(r.getId())) {
                        failures.incrementAndGet();
                    }
                } catch (Exception e) {
                    failures.incrementAndGet();
                } finally {
                    done.countDown();
                }
            });
        }

        assertThat(done.await(30, TimeUnit.SECONDS)).as("30s 内全部请求应完成").isTrue();
        pool.shutdown();

        assertThat(failures.get()).as("所有请求都应拿到数据").isZero();
        assertThat(dbHits.get())
                .as("慢重建场景 DB 只能被回源一次（修复前 6 个等待者全部回源）")
                .isEqualTo(1);
        assertThat(stringRedisTemplate.opsForValue().get(key)).isNotBlank();
    }

    @Test
    @DisplayName("击穿：持锁者失联（锁过期未重建）→ 等待者接管抢锁并重建")
    void breakdownTakesOverWhenLockExpires() {
        Long id = nextId();
        String key = CACHE_SHOP_KEY + id;
        String lockKey = LOCK_SHOP_KEY + id;
        redisKeysToClean.add(key);
        redisKeysToClean.add(lockKey);

        // 模拟「持锁线程异常退出」：锁存在但 300ms 后过期，且没有任何人重建缓存
        stringRedisTemplate.opsForValue().set(lockKey, "dead-holder", 300, TimeUnit.MILLISECONDS);

        int[] dbHits = {0};
        Shop dbShop = new Shop().setId(id).setName("缓存观测店铺-锁过期接管").setTypeId(1L);
        Shop result = cacheClient.handleCacheBreakdownWithMutex(
                CACHE_SHOP_KEY,
                LOCK_SHOP_KEY,
                id,
                Shop.class,
                miss -> {
                    dbHits[0]++;
                    return dbShop;
                },
                CACHE_SHOP_TTL,
                TimeUnit.MINUTES);

        assertThat(result).as("锁过期后等待者应接管并返回数据").isNotNull();
        assertThat(result.getId()).isEqualTo(id);
        assertThat(dbHits[0]).as("接管重建只回源一次").isEqualTo(1);
        assertThat(stringRedisTemplate.opsForValue().get(key)).isNotBlank();
    }

    // ==================================================================
    // 缓存雪崩（随机 TTL 抖动）
    // ==================================================================

    @Test
    @DisplayName("雪崩：setWithRandomTtl 的 TTL 全部落在 [base, base+300] 且不完全相同")
    void avalancheAppliesBoundedJitter() {
        int samples = 24;
        Set<Long> observedTtls = new HashSet<>();

        for (int i = 0; i < samples; i++) {
            String key = AVAL_KEY_PREFIX + nextId();
            redisKeysToClean.add(key);

            cacheClient.setWithRandomTtl(key, new Shop().setId(1L), CACHE_SHOP_TTL, TimeUnit.MINUTES);

            Long ttl = stringRedisTemplate.getExpire(key, TimeUnit.SECONDS);
            assertThat(ttl)
                    .as("抖动必须落在有界区间内，避免无限拉长缓存")
                    .isNotNull()
                    .isGreaterThan(BASE_TTL_SECONDS - TTL_TOLERANCE_SECONDS)
                    .isLessThanOrEqualTo(BASE_TTL_SECONDS + JITTER_MAX_SECONDS);
            observedTtls.add(ttl);
        }

        assertThat(observedTtls.size())
                .as("%d 次采样全部落在同一秒的概率可忽略，出现多个不同 TTL 才说明抖动生效", samples)
                .isGreaterThan(1);
    }

    // ==================================================================
    // 辅助
    // ==================================================================

    private Long nextId() {
        return SEQ.incrementAndGet();
    }

    /**
     * 在回源回调里模拟慢重建：只在本测试线程内睡眠，不触碰共享状态。
     * 被中断时恢复中断标记并抛出，避免把「测试线程被中断」伪装成「重建成功」。
     */
    private static void sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("慢重建模拟被中断", e);
        }
    }
}
