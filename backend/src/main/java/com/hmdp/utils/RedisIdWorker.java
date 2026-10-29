package com.hmdp.utils;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

/**
 * 基于 Redis 的全局唯一 ID 生成器
 * <p>
 * 采用"时间戳 + 序列号"的位运算拼接方案，生成的 ID 为 64 位 long 类型：
 * <ul>
 *   <li>高 32 位：相对时间戳（当前秒数 - 自定义起始时间），保证 ID 全局递增</li>
 *   <li>低 32 位：Redis INCR 自增序列号，按天重置，保证同一秒内不重复</li>
 * </ul>
 * </p>
 * <p>
 * 相比数据库自增 ID 和 UUID，该方案具有高性能、低延迟、全局唯一、趋势递增的优点。
 * 适用于订单号、交易流水号等需要全局唯一且有序的业务场景。
 * </p>
 */
@Component
public class RedisIdWorker {

    /**
     * 自定义起始时间戳（2022-01-01 00:00:00 UTC）
     * <p>
     * 用当前时间减去该值，得到相对秒数，减少高位占用，延长 ID 可用年限。
     * </p>
     */
    private static final long BEGIN_TIMESTAMP = 1640995200L;

    /**
     * 序列号占用的二进制位数
     * <p>
     * 32 位序列号意味着每秒最多可生成 2^32 - 1 = 约 42 亿个 ID。
     * </p>
     */
    private static final int COUNT_BITS = 32;

    private StringRedisTemplate stringRedisTemplate;

    /**
     * 构造 ID 生成器实例
     *
     * @param stringRedisTemplate Redis 操作模板，用于 INCR 自增序列号
     */
    public RedisIdWorker(StringRedisTemplate stringRedisTemplate) {
        this.stringRedisTemplate = stringRedisTemplate;
    }

    /**
     * 生成全局唯一 ID
     * <p>
     * ID 结构：[高 32 位相对时间戳 | 低 32 位 Redis 自增序列号]
     * Redis 中的序列号键格式为 "icr:{keyPrefix}:{yyyy:MM:dd}"，按天自增。
     * </p>
     *
     * @param keyPrefix 业务前缀，用于区分不同类型的 ID（如 "order"、"pay"），
     *                  不同前缀生成各自独立的序列号
     * @return 64 位全局唯一 ID，全局趋势递增
     */
    public long nextId(String keyPrefix) {
        // 1.生成时间戳
        LocalDateTime now = LocalDateTime.now();
        long nowSecond = now.toEpochSecond(ZoneOffset.UTC);
        long timestamp = nowSecond - BEGIN_TIMESTAMP;

        // 2.生成序列号
        // 2.1.获取当前日期，精确到天
        String date = now.format(DateTimeFormatter.ofPattern("yyyy:MM:dd"));
        // 2.2.自增长
        long count = stringRedisTemplate.opsForValue()
                .increment(RedisConstants.ID_SEQUENCE_KEY_PREFIX + keyPrefix + ":" + date);

        // 3.拼接并返回
        return timestamp << COUNT_BITS | count;
    }
}
