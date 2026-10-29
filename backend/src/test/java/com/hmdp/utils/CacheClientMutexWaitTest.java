package com.hmdp.utils;

import cn.hutool.json.JSONUtil;
import com.hmdp.entity.Shop;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 互斥锁击穿防护的等待行为单测（P1-5）
 *
 * <p>
 * <b>性质：纯单元测试，零基础设施。</b>{@link StringRedisTemplate} 为 Mockito 桩，
 * {@link CacheClient} 直接 new。钉住 P1-5 的核心承诺：<b>锁被占用期间，等待者继续
 * 轮询缓存而绝不分批回源 DB</b>——旧实现在 10×50ms（500ms）后让所有等待线程一起
 * 打 MySQL，慢重建时互斥锁形同虚设。
 * </p>
 *
 * <p>
 * 总窗口（10s，与锁租约对齐）耗尽后的接管/降级路径、多等待者并发场景需要真实
 * Redis，由 {@code CacheGovernanceObservabilityTest} 的集成用例覆盖。
 * </p>
 */
@DisplayName("缓存击穿互斥等待行为（P1-5）")
class CacheClientMutexWaitTest {

    private static final String KEY_PREFIX = "cache:test:mutex:";
    private static final String LOCK_PREFIX = "lock:test:mutex:";
    private static final long ID = 9_400_001L;

    private StringRedisTemplate stringRedisTemplate;
    private ValueOperations<String, String> valueOperations;
    private CacheClient cacheClient;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        stringRedisTemplate = mock(StringRedisTemplate.class);
        valueOperations = mock(ValueOperations.class);
        when(stringRedisTemplate.opsForValue()).thenReturn(valueOperations);
        cacheClient = new CacheClient(stringRedisTemplate);
    }

    @Test
    @DisplayName("锁被占用且重建中：等待者读到新缓存即返回，零回源 DB（P1-5 核心）")
    void waiterReturnsOnceCacheAppearsWithoutDbFallback() {
        Shop dbShop = new Shop().setId(ID).setName("等待者店铺").setTypeId(1L);
        String cachedJson = JSONUtil.toJsonStr(dbShop);
        // 前两次 GET 未命中（锁持有者还在重建），第三次读到已重建的缓存
        when(valueOperations.get(anyString())).thenReturn(null, null, cachedJson);
        // 锁全程被他人持有（setIfAbsent 返回 false）
        when(valueOperations.setIfAbsent(anyString(), anyString(), anyLong(), any()))
                .thenReturn(Boolean.FALSE);

        int[] dbHits = {0};
        Shop result = cacheClient.handleCacheBreakdownWithMutex(
                KEY_PREFIX, LOCK_PREFIX, ID, Shop.class,
                miss -> {
                    dbHits[0]++;
                    return dbShop;
                },
                30L, TimeUnit.MINUTES);

        assertThat(result).as("应读到重建后的缓存").isNotNull();
        assertThat(result.getName()).isEqualTo("等待者店铺");
        assertThat(dbHits[0]).as("锁被占用期间绝不应回源 DB").isZero();
    }

    @Test
    @DisplayName("空值哨兵命中：直接返回 null，不抢锁、不回源")
    void sentinelShortCircuits() {
        when(valueOperations.get(anyString())).thenReturn("");

        int[] dbHits = {0};
        Shop result = cacheClient.handleCacheBreakdownWithMutex(
                KEY_PREFIX, LOCK_PREFIX, ID, Shop.class,
                miss -> {
                    dbHits[0]++;
                    return null;
                },
                30L, TimeUnit.MINUTES);

        assertThat(result).isNull();
        assertThat(dbHits[0]).isZero();
        verify(valueOperations, never()).setIfAbsent(anyString(), anyString(), anyLong(), any());
    }

    @Test
    @DisplayName("抢到锁：double-check 后回源一次并写回缓存，随后释放锁")
    void lockHolderRebuildsOnce() {
        Shop dbShop = new Shop().setId(ID).setName("重建店铺").setTypeId(1L);
        // 第一次 GET 未命中（进入抢锁分支），double-check 仍未命中
        when(valueOperations.get(anyString())).thenReturn(null, null);
        when(valueOperations.setIfAbsent(anyString(), anyString(), anyLong(), any()))
                .thenReturn(Boolean.TRUE);

        int[] dbHits = {0};
        Shop result = cacheClient.handleCacheBreakdownWithMutex(
                KEY_PREFIX, LOCK_PREFIX, ID, Shop.class,
                miss -> {
                    dbHits[0]++;
                    return dbShop;
                },
                30L, TimeUnit.MINUTES);

        assertThat(result).isNotNull();
        assertThat(dbHits[0]).as("持锁者只回源一次").isEqualTo(1);
        // 写回缓存（setWithRandomTtl 走 set，TTL = 30min + jitter 秒）
        verify(valueOperations, times(1)).set(anyString(), anyString(), anyLong(), any());
        // 释放锁走 Lua 脚本（token 归属校验），mock 下返回 null 不影响流程
        verify(stringRedisTemplate, times(1)).execute(any(), any(), any());
    }

    @Test
    @DisplayName("DB 也不存在：写空值哨兵（短 TTL）并返回 null")
    void missingRowWritesNullSentinel() {
        when(valueOperations.get(anyString())).thenReturn(null, null);
        when(valueOperations.setIfAbsent(anyString(), anyString(), anyLong(), any()))
                .thenReturn(Boolean.TRUE);

        int[] dbHits = {0};
        Shop result = cacheClient.handleCacheBreakdownWithMutex(
                KEY_PREFIX, LOCK_PREFIX, ID, Shop.class,
                miss -> {
                    dbHits[0]++;
                    return null;
                },
                30L, TimeUnit.MINUTES);

        assertThat(result).isNull();
        assertThat(dbHits[0]).isEqualTo(1);
        // 空值哨兵：CACHE_NULL_TTL=2s，防止穿透
        verify(valueOperations).set(KEY_PREFIX + ID, "",
                RedisConstants.CACHE_NULL_TTL, TimeUnit.SECONDS);
    }
}
