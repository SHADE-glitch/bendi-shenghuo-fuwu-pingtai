package com.hmdp.utils;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * Redis 缓存数据封装类
 * <p>
 * 用于"逻辑过期"缓存方案，将实际业务数据与逻辑过期时间打包存储。
 * 写入 Redis 时，Redis 键本身的 TTL 设置较长（或永久），
 * 通过本类的 expireTime 字段控制逻辑层面的过期时间，
 * 从而实现异步缓存重建而不阻塞查询请求。
 * </p>
 */
@Data
public class RedisData {

    /**
     * 逻辑过期时间
     * <p>
     * 当该时间早于当前时间时，视为缓存逻辑过期，触发异步重建。
     * </p>
     */
    private LocalDateTime expireTime;

    /**
     * 实际缓存的业务数据对象
     */
    private Object data;
}
