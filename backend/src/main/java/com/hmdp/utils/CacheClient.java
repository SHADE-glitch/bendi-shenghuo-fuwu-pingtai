package com.hmdp.utils;

import static com.hmdp.utils.RedisConstants.CACHE_NULL_TTL;
import static com.hmdp.utils.RedisConstants.LOCK_SHOP_KEY;

import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

/**
 * 通用缓存客户端封装
 * <p>
 * 基于 StringRedisTemplate 封装的缓存工具类，提供以下核心能力：
 * <ul>
 *   <li>基础缓存读写（set / get）</li>
 *   <li>带逻辑过期时间的缓存写入（setWithLogicalExpire）</li>
 *   <li>缓存穿透防护：通过逻辑空值（空字符串）避免查询不存在的数据</li>
 *   <li>缓存击穿防护：通过互斥锁 + 后台异步线程重建缓存</li>
 *   <li>缓存雪崩防护：TTL 设置时采用不同时间单位或加随机偏移</li>
 * </ul>
 * </p>
 */
@Component
@Slf4j
public class CacheClient {

    private final StringRedisTemplate stringRedisTemplate;
    /**
     * 释放互斥锁的原子脚本：只有 value 与持有者 token 一致才 DEL。
     * 否则「A 的锁过期 → B 拿到锁 → A 执行 unlock」会把 B 的锁删掉，
     * 让互斥彻底失效。
     */
    private static final DefaultRedisScript<Long> UNLOCK_SCRIPT = new DefaultRedisScript<>(
        "if redis.call('get', KEYS[1]) == ARGV[1] then return redis.call('del', KEYS[1]) else return 0 end",
        Long.class
    );

    private static final long CACHE_TTL_JITTER_MAX_SECONDS = 300L;
    /** 互斥锁租约（秒）：防持锁线程异常退出后死锁；正常回源远快于该值 */
    private static final long CACHE_MUTEX_LOCK_TTL_SECONDS = 10L;
    /**
     * 等待总窗口（毫秒）：与锁租约对齐（P1-5）。
     * <p>
     * 旧实现是固定 10 次 × 50ms = 500ms：重建超过 500ms 后，所有等待线程一起回源
     * MySQL——正是互斥锁要防的击穿/雪崩。现在等待者以指数退避 + 全抖动轮询缓存，
     * 最多覆盖一个完整租约；租约内重建完成的等待者随即可读到新缓存。
     * </p>
     */
    private static final long CACHE_MUTEX_MAX_WAIT_MS =
            TimeUnit.SECONDS.toMillis(CACHE_MUTEX_LOCK_TTL_SECONDS);
    /** 退避基数（毫秒），首次等待时长 */
    private static final long CACHE_MUTEX_RETRY_INTERVAL_MS = 50L;
    /** 单次退避上限（毫秒）：封顶后等待者仍以 ≤500ms 的粒度继续轮询 */
    private static final long CACHE_MUTEX_BACKOFF_CAP_MS = 500L;

    public CacheClient(StringRedisTemplate stringRedisTemplate) {
        this.stringRedisTemplate = stringRedisTemplate;
    }

    /**
     * 将对象序列化后写入 Redis，并设置过期时间
     *
     * @param key     Redis 中的键名
     * @param value   待缓存的对象，会通过 JSONUtil 序列化为字符串
     * @param timeout 过期时长数值
     * @param unit    过期时长的时间单位
     */
    public void set(String key, Object value, Long timeout, TimeUnit unit) {
        stringRedisTemplate
            .opsForValue()
            .set(key, JSONUtil.toJsonStr(value), timeout, unit);
    }

    /**
     * 将对象写入 Redis，并在基础 TTL 上追加随机抖动（jitter）
     * <p>
     * 通过错峰过期时间降低缓存雪崩风险。jitter 范围：[0, CACHE_TTL_JITTER_MAX_SECONDS] 秒。
     * </p>
     */
    public void setWithRandomTtl(
        String key,
        Object value,
        Long timeout,
        TimeUnit unit
    ) {
        long baseSeconds = unit.toSeconds(timeout);
        long jitterSeconds = ThreadLocalRandom.current().nextLong(
            CACHE_TTL_JITTER_MAX_SECONDS + 1
        );
        stringRedisTemplate
            .opsForValue()
            .set(
                key,
                JSONUtil.toJsonStr(value),
                baseSeconds + jitterSeconds,
                TimeUnit.SECONDS
            );
    }

    /**
     * 将对象包装为 RedisData（含逻辑过期时间）后写入 Redis
     * <p>
     * 适用于"逻辑过期"方案处理缓存击穿场景：数据本身设置较长 TTL，
     * 同时在 RedisData 中记录逻辑过期时间，过期后通过异步线程重建缓存。
     * </p>
     *
     * @param key     Redis 中的键名
     * @param value   待缓存的对象
     * @param timeout 逻辑过期时长数值
     * @param unit    逻辑过期时长的时间单位
     */
    public void setWithLogicalExpire(
        String key,
        Object value,
        Long timeout,
        TimeUnit unit
    ) {
        // 1. 创建马甲对象
        RedisData redisData = new RedisData();
        redisData.setData(value);

        // 2. 穿上马甲，并写上“逻辑过期时间”
        // unit.toSeconds()是为了确保计时单位是秒
        redisData.setExpireTime(
            LocalDateTime.now().plusSeconds(unit.toSeconds(timeout))
        );

        // 3. 写入 Redis
        // 【修复1】这里必须存入包裹了过期时间的 redisData，而不是原始的 value
        // 【修复2】这里绝对不能加 timeout 和 unit，因为逻辑过期要求物理上永久有效（或者是一个极长的兜底时间）
        stringRedisTemplate
            .opsForValue()
            .set(key, JSONUtil.toJsonStr(redisData));
    }

    /**
     * 根据 ID 查询数据，并处理缓存穿透问题
     * <p>
     * 处理流程：
     * <ol>
     *   <li>先查 Redis 缓存，命中则直接返回</li>
     *   <li>缓存中存储的是空字符串（逻辑空值），说明该数据不存在，直接返回 null</li>
     *   <li>缓存未命中则回源数据库查询</li>
     *   <li>数据库也不存在时，写入空字符串到缓存（TTL 较短），防止后续重复查询</li>
     *   <li>数据库存在该数据时，写入正常缓存后返回</li>
     * </ol>
     * </p>
     *
     * @param keyPrefix  Redis 键名前缀，调用方传入的完整 key = keyPrefix + id
     * @param id         业务主键 ID
     * @param type       返回值的目标类型 Class 对象
     * @param dbFallback 数据库回退查询函数，接收 ID 参数并返回查询结果
     * @param timeout    缓存过期时长数值（数据库查询命中时使用）
     * @param unit       缓存过期时长的时间单位
     * @param <T>        返回值类型
     * @param <ID>       ID 参数类型
     * @return 查询到的数据对象；数据不存在时返回 null
     */
    public <T, ID> T handleCachePenetration(
        String keyPrefix,
        ID id,
        Class<T> type,
        Function<ID, T> dbFallback,
        Long timeout,
        TimeUnit unit
    ) {
        String key = keyPrefix + id;
        // 1、从Redis中查询店铺数据
        String jsonStr = stringRedisTemplate.opsForValue().get(key);

        T t = null;
        // 2、判断缓存是否命中
        if (StrUtil.isNotBlank(jsonStr)) {
            // 2.1 缓存命中，直接返回店铺数据
            t = JSONUtil.toBean(jsonStr, type);
            return t;
        }

        // 2.2 缓存未命中，判断缓存中查询的数据是否是空字符串(isNotBlank把null和空字符串给排除了)
        if (Objects.nonNull(jsonStr)) {
            // 2.2.1 当前数据是空字符串（说明该数据是之前缓存的空对象），直接返回失败信息
            return null;
        }
        // 2.2.2 当前数据是null，则从数据库中查询店铺数据
        t = dbFallback.apply(id);

        // 4、判断数据库是否存在店铺数据
        if (Objects.isNull(t)) {
            // 4.1 数据库中不存在，缓存空对象（解决缓存穿透），返回失败信息
            this.set(key, "", CACHE_NULL_TTL, TimeUnit.SECONDS);
            return null;
        }
        // 4.2 数据库中存在，重建缓存，并返回店铺数据
        this.setWithRandomTtl(key, t, timeout, unit);
        return t;
    }

    /**
     * 缓存异步重建线程池
     * <p>
     * 用于在缓存击穿场景下，通过后台线程异步重建过期缓存，
     * 避免阻塞主查询线程。使用 CachedThreadPool 允许动态创建线程，
     * 所有线程都设置为守护线程，确保应用可以优雅关闭。
     * </p>
     */
    private static final ExecutorService CACHE_REBUILD_EXECUTOR =
        Executors.newCachedThreadPool(r -> {
            Thread t = new Thread(r, "cache-rebuild-");
            t.setDaemon(true);  // 守护线程，允许应用优雅关闭
            return t;
        });

    /**
     * 销毁回调：在 Bean 销毁时优雅关闭缓存重建线程池
     * <p>
     * 先尝试等待 30 秒让已有任务执行完毕，超时后强制关闭。
     * 如果等待期间被中断，则恢复中断状态并强制关闭。
     * </p>
     */
    @PreDestroy
    public void destroy() {
        if (CACHE_REBUILD_EXECUTOR != null && !CACHE_REBUILD_EXECUTOR.isShutdown()) {
            CACHE_REBUILD_EXECUTOR.shutdown();
            try {
                // 等待 30 秒让任务完成
                if (!CACHE_REBUILD_EXECUTOR.awaitTermination(30, TimeUnit.SECONDS)) {
                    log.warn("缓存重建线程池未在 30 秒内关闭，强制终止");
                    CACHE_REBUILD_EXECUTOR.shutdownNow();
                    // 再次等待，确保彻底关闭
                    if (!CACHE_REBUILD_EXECUTOR.awaitTermination(5, TimeUnit.SECONDS)) {
                        log.error("缓存重建线程池无法完全关闭");
                    }
                }
            } catch (InterruptedException e) {
                log.warn("等待缓存重建线程池关闭时被中断", e);
                CACHE_REBUILD_EXECUTOR.shutdownNow();
                Thread.currentThread().interrupt();  // 恢复中断状态
            }
            log.info("缓存重建线程池已关闭");
        }
    }

    /**
     * 根据 ID 查询数据，并处理缓存击穿问题（基于逻辑过期方案）
     * <p>
     * 处理流程：
     * <ol>
     *   <li>查询 Redis 缓存，未命中则返回 null</li>
     *   <li>缓存命中后检查逻辑过期时间，未过期则直接返回数据</li>
     *   <li>缓存已过期时尝试获取互斥锁，获取成功则提交异步任务重建缓存</li>
     *   <li>获取锁失败时进行二次检查（double-check），确认缓存是否已被其他线程重建</li>
     *   <li>如果重建仍未完成，则返回已过期的旧数据作为降级处理</li>
     * </ol>
     * </p>
     *
     * @param keyPrefix  Redis 键名前缀，调用方传入的完整 key = keyPrefix + id
     * @param id         业务主键 ID
     * @param type       返回值的目标类型 Class 对象
     * @param dbFallback 数据库回退查询函数，接收 ID 参数并返回查询结果
     * @param timeout    缓存逻辑过期时长数值
     * @param unit       缓存逻辑过期时长的时间单位
     * @param <T>        返回值类型
     * @param <ID>       ID 参数类型
     * @return 查询到的数据对象；缓存未命中时返回 null；缓存重建期间可能返回过期数据
     */
    public <T, ID> T handleCacheBreakdownWithLogicalExpire(
        String keyPrefix,
        ID id,
        Class<T> type,
        Function<ID, T> dbFallback,
        Long timeout,
        TimeUnit unit
    ) {
        String key = keyPrefix + id;
        // 1、从Redis中查询店铺数据，并判断缓存是否命中
        String jsonStr = stringRedisTemplate.opsForValue().get(key);
        if (StrUtil.isBlank(jsonStr)) {
            // 1.1 缓存未命中，直接返回失败信息
            return null;
        }
        // 1.2 缓存命中，将JSON字符串反序列化未对象，并判断缓存数据是否逻辑过期
        RedisData redisData = JSONUtil.toBean(jsonStr, RedisData.class);
        // 这里需要先转成JSONObject再转成反序列化，否则可能无法正确映射Shop的字段
        JSONObject data = (JSONObject) redisData.getData();
        T t = JSONUtil.toBean(data, type);
        LocalDateTime expireTime = redisData.getExpireTime();
        if (expireTime.isAfter(LocalDateTime.now())) {
            // 当前缓存数据未过期，直接返回
            return t;
        }

        // 2、缓存数据已过期，获取互斥锁，并且重建缓存
        String lockKey = LOCK_SHOP_KEY + id;
        String lockToken = tryLock(lockKey);
        if (lockToken != null) {
            // 获取锁成功，开启一个子线程去重建缓存
            CACHE_REBUILD_EXECUTOR.submit(() -> {
                try {
                    // 查询数据库
                    T t1 = dbFallback.apply(id);
                    // 将查询到的数据保存到Redis
                    this.setWithLogicalExpire(key, t1, timeout, unit);
                } catch (Exception e) {
                    // 缓存重建失败，记录日志告警
                    log.error(
                        "缓存重建失败, key={}, error={}",
                        key,
                        e.getMessage(),
                        e
                    );
                    // 降级处理：不做额外处理，返回过期数据
                } finally {
                    unlock(lockKey, lockToken);
                }
            });
        }

        // 3、获取锁失败，再次查询缓存，判断缓存是否重建（这里双检是有必要的）
        jsonStr = stringRedisTemplate.opsForValue().get(key);
        if (StrUtil.isBlank(jsonStr)) {
            // 3.1 缓存未命中，直接返回失败信息
            return null;
        }
        // 3.2 缓存命中，将JSON字符串反序列化未对象，并判断缓存数据是否逻辑过期
        redisData = JSONUtil.toBean(jsonStr, RedisData.class);
        // 这里需要先转成JSONObject再转成反序列化，否则可能无法正确映射Shop的字段
        data = (JSONObject) redisData.getData();
        t = JSONUtil.toBean(data, type);
        expireTime = redisData.getExpireTime();
        if (expireTime.isAfter(LocalDateTime.now())) {
            // 当前缓存数据未过期，直接返回
            return t;
        }

        // 4、返回过期数据
        return t;
    }

    /**
     * 根据 ID 查询数据，并通过互斥锁处理缓存击穿（未命中回源 DB 并重建缓存）
     * <p>
     * 处理流程：
     * <ol>
     *   <li>先查 Redis，命中则直接返回</li>
     *   <li>命中空值标记则返回 null（防穿透）</li>
     *   <li>缓存未命中时尝试获取互斥锁，获取成功后回源 DB 并重建缓存</li>
     *   <li>未获取锁则指数退避 + 抖动等待，持续短轮询缓存（P1-5：总窗口与锁租约
     *       对齐，默认 10s，而不是旧实现的 10×50ms）——重建慢于 500ms 时等待者
     *       不再集体回源 MySQL</li>
     *   <li>窗口耗尽（重建超过一个租约）时最后抢一次锁：抢到则接管重建，抢不到才
     *       降级回源（保证可用性，绝不死等）</li>
     *   <li>缓存重建时写入随机 TTL，降低雪崩风险</li>
     * </ol>
     * </p>
     */
    public <T, ID> T handleCacheBreakdownWithMutex(
        String keyPrefix,
        String lockKeyPrefix,
        ID id,
        Class<T> type,
        Function<ID, T> dbFallback,
        Long timeout,
        TimeUnit unit
    ) {
        String key = keyPrefix + id;
        String lockKey = lockKeyPrefix + id;

        long deadline = System.currentTimeMillis() + CACHE_MUTEX_MAX_WAIT_MS;
        long backoffMs = CACHE_MUTEX_RETRY_INTERVAL_MS;
        while (true) {
            String jsonStr = stringRedisTemplate.opsForValue().get(key);
            if (StrUtil.isNotBlank(jsonStr)) {
                return JSONUtil.toBean(jsonStr, type);
            }
            if (Objects.nonNull(jsonStr)) {
                return null;
            }

            String lockToken = tryLock(lockKey);
            if (lockToken != null) {
                return rebuildUnderLock(key, lockKey, lockToken, id, type, dbFallback, timeout, unit);
            }

            // 互斥锁被他人持有：说明已有线程在重建。指数退避 + 全抖动后继续轮询缓存，
            // 不回源 DB——把「同时回源」压成「一个回源、其余等待」。
            if (System.currentTimeMillis() >= deadline) {
                // 窗口耗尽：重建方异常退出或慢到超过一个完整租约。最后抢一次锁，
                // 抢到就接管重建；抢不到才降级回源（可用性优先，但此路径极窄）。
                String takeoverToken = tryLock(lockKey);
                if (takeoverToken != null) {
                    return rebuildUnderLock(key, lockKey, takeoverToken, id, type, dbFallback, timeout, unit);
                }
                T fallback = dbFallback.apply(id);
                if (Objects.isNull(fallback)) {
                    this.set(key, "", CACHE_NULL_TTL, TimeUnit.SECONDS);
                    return null;
                }
                this.setWithRandomTtl(key, fallback, timeout, unit);
                return fallback;
            }
            sleepWithJitter(backoffMs);
            backoffMs = Math.min(backoffMs * 2, CACHE_MUTEX_BACKOFF_CAP_MS);
        }
    }

    /**
     * 持锁重建：double-check 后回源 DB 并写回缓存，finally 释放锁。
     * <p>
     * double-check 不能省：拿到锁的瞬间前一个持锁线程可能刚重建完，不再查一次就会
     * 重复回源（double-checked locking 在缓存场景的落地）。
     * </p>
     */
    private <T, ID> T rebuildUnderLock(
        String key,
        String lockKey,
        String lockToken,
        ID id,
        Class<T> type,
        Function<ID, T> dbFallback,
        Long timeout,
        TimeUnit unit
    ) {
        try {
            String jsonStr = stringRedisTemplate.opsForValue().get(key);
            if (StrUtil.isNotBlank(jsonStr)) {
                return JSONUtil.toBean(jsonStr, type);
            }
            if (Objects.nonNull(jsonStr)) {
                return null;
            }

            T t = dbFallback.apply(id);
            if (Objects.isNull(t)) {
                this.set(key, "", CACHE_NULL_TTL, TimeUnit.SECONDS);
                return null;
            }
            this.setWithRandomTtl(key, t, timeout, unit);
            return t;
        } finally {
            unlock(lockKey, lockToken);
        }
    }

    /**
     * 带全抖动的等待：在 {@code [0, backoffMs)} 内随机睡眠。
     * <p>
     * 抖动的目的是让并发等待者的下一次轮询错峰，避免锁释放瞬间所有人同时惊醒
     * 再次争抢（惊群）。全抖动同时把平均等待压到退避值的一半，配合指数增长
     * 与 {@link #CACHE_MUTEX_MAX_WAIT_MS} 的总窗口，慢重建下 Redis 轮询负载有界。
     * </p>
     */
    private void sleepWithJitter(long backoffMs) {
        long sleepMs = ThreadLocalRandom.current().nextLong(backoffMs + 1);
        try {
            Thread.sleep(sleepMs);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("缓存互斥重试被中断", e);
        }
    }

    /**
     * 尝试获取 Redis 互斥锁
     * <p>
     * 使用 SET NX 命令实现，锁的 TTL 为 {@link #CACHE_MUTEX_LOCK_TTL_SECONDS} 秒，
     * 防止持锁线程异常退出后死锁。value 写入持有者随机 token，供
     * {@link #unlock(String, String)} 做归属校验。
     * </p>
     *
     * @param key 锁对应的 Redis 键名
     * @return 获取成功返回持有者 token；失败（锁已被其他线程持有）返回 null
     */
    private String tryLock(String key) {
        String token = UUID.randomUUID().toString();
        Boolean flag = stringRedisTemplate
            .opsForValue()
            .setIfAbsent(key, token, CACHE_MUTEX_LOCK_TTL_SECONDS, TimeUnit.SECONDS);
        // 拆箱要判空，防止NPE
        return BooleanUtil.isTrue(flag) ? token : null;
    }

    /**
     * 释放 Redis 互斥锁（仅当 value 仍是本次持有的 token 时才删除）
     *
     * @param key   锁对应的 Redis 键名
     * @param token 获取锁时返回的持有者 token
     */
    private void unlock(String key, String token) {
        if (token == null) {
            return;
        }
        stringRedisTemplate.execute(UNLOCK_SCRIPT, Collections.singletonList(key), token);
    }
}
