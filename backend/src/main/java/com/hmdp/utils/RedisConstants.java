package com.hmdp.utils;

/**
 * Redis Key 常量定义
 * <p>
 * 集中管理项目中所有 Redis 键名和 TTL 常量，避免硬编码。
 * 命名规范：
 * <ul>
 *   <li>Key 前缀常量：格式为 {@code 业务:模块:}，末尾以冒号结尾，方便拼接业务 ID</li>
 *   <li>TTL 常量：单位为秒，名称以 {@code _TTL} 结尾</li>
 * </ul>
 * </p>
 */
public class RedisConstants {

    // ==================== 用户相关 ====================

    /** 用户基本信息缓存 key 前缀: user:info:{userId} */
    public static final String USER_INFO_KEY = "user:info:";

    // ==================== 登录相关 ====================

    /** 登录验证码 key 前缀: login:code:{phone} */
    public static final String LOGIN_CODE_KEY = "login:code:";
    /** 登录验证码有效期，单位：分钟 */
    public static final Long LOGIN_CODE_TTL = 2L;

    /**
     * 验证码发送冷却 key 前缀: login:code:limit:{phone}
     * <p>
     * P1-1：按手机号限制发送频率。SETNX 写入、TTL 到期自动解除；
     * 冷却期内再次请求发送直接拒绝（{@code CODE_SEND_TOO_FREQUENT}），
     * 不给攻击者靠「反复重发」放大 6 位验证码枚举空间的机会。
     * </p>
     */
    public static final String LOGIN_CODE_SEND_LIMIT_KEY = "login:code:limit:";
    /** 验证码发送冷却时长，单位：秒（1 次/分钟） */
    public static final Long LOGIN_CODE_SEND_LIMIT_TTL = 60L;

    /**
     * 验证码每小时发送计数 key 前缀: login:code:count:{phone}
     * <p>
     * P1-1：在分钟级冷却之上再加小时级配额，INCR 计数、首次即设 TTL。
     * </p>
     */
    public static final String LOGIN_CODE_SEND_COUNT_KEY = "login:code:count:";
    /** 每小时发送计数窗口，单位：秒 */
    public static final Long LOGIN_CODE_SEND_COUNT_TTL = 3600L;
    /** 单个手机号每小时最多发送次数 */
    public static final Long LOGIN_CODE_SEND_MAX_PER_HOUR = 5L;

    /**
     * 验证码校验失败计数 key 前缀: login:code:attempt:{phone}
     * <p>
     * P1-1：登录时校验失败也计数；达到上限立即作废当前验证码，
     * 要求重新发送。TTL 与验证码本身对齐（见 {@link #LOGIN_CODE_TTL}），
     * 计数的生命周期不超过它保护的那一次发放。
     * </p>
     */
    public static final String LOGIN_CODE_ATTEMPT_KEY = "login:code:attempt:";
    /** 单个验证码最多允许的校验失败次数 */
    public static final Long LOGIN_CODE_MAX_ATTEMPTS = 5L;
    /** 登录用户 token key 前缀: login:token:{token} */
    public static final String LOGIN_USER_KEY = "login:token:";
    /** 登录用户 token 有效期，单位：秒（36000 = 10 小时） */
    public static final Long LOGIN_USER_TTL = 36000L;

    // ==================== 缓存相关 ====================

    /** 空值缓存（缓存穿透防护）的 TTL，单位：秒 */
    public static final Long CACHE_NULL_TTL = 2L;

    /** 店铺信息缓存 TTL，单位：分钟 */
    public static final Long CACHE_SHOP_TTL = 30L;
    /** 店铺信息缓存 key 前缀: cache:shop:{shopId} */
    public static final String CACHE_SHOP_KEY = "cache:shop:";
    /** 店铺分类列表缓存 key 前缀: cache:shopType: */
    public static final String CACHE_SHOP_TYPE_KEY = "cache:shopType:";
    /** 店铺查询互斥锁 key 前缀: lock:shop:{shopId} */
    public static final String LOCK_SHOP_KEY = "lock:shop:";

    // ==================== 分布式锁相关 ====================

    /** 秒杀下单分布式锁 key 前缀: lock:seckill:order:{userId}:{voucherId} */
    public static final String LOCK_SECKILL_ORDER_KEY = "lock:seckill:order:";

    /** 普通购券分布式锁 key 前缀: lock:order:{userId}:{voucherId} */
    public static final String LOCK_ORDER_KEY = "lock:order:";

    // ==================== 全局 ID 序列号相关 ====================

    /** 全局 ID 序列号 Redis key 前缀: icr:{prefix}:{yyyy:MM:dd} */
    public static final String ID_SEQUENCE_KEY_PREFIX = "icr:";

    /** 订单 ID 业务前缀 */
    public static final String ORDER_ID_PREFIX = "order";

    // ==================== 秒杀相关 ====================

    /** 秒杀库存 Redis key 前缀: seckill:stock:{voucherId} */
    public static final String SECKILL_STOCK_KEY = "seckill:stock:";
    /**
     * 秒杀已占位用户集合 Redis key 前缀: seckill:orders:{voucherId}
     * <p>
     * 使用 SADD 标记用户占位；Lua 脚本用 SISMEMBER 预检一人一单。
     * </p>
     */
    public static final String SECKILL_ORDER_KEY = "seckill:orders:";

    /** 秒杀订单 Stream key: stream:seckill:orders */
    public static final String SECKILL_STREAM_KEY = "stream:seckill:orders";
    /** 秒杀订单 Stream consumer group */
    public static final String SECKILL_STREAM_GROUP = "seckill-group";
    /** 秒杀订单 Stream consumer name 前缀 */
    public static final String SECKILL_STREAM_CONSUMER = "consumer-";

    /**
     * 取消/退款库存补偿幂等标记 key 前缀: seckill:rollback:{orderId}
     * <p>
     * P1-3：补偿（Redis 库存 +1 + 移除用户占位）由 Lua 原子执行，
     * 标记用 SET NX 保证「同一订单只补偿一次」——补偿可能因响应丢失被重放，
     * 没有这一关，重放会给库存重复 +1。TTL 覆盖重试窗口即可，无需永久保留。
     * </p>
     */
    public static final String SECKILL_ROLLBACK_KEY = "seckill:rollback:";
    /** 补偿幂等标记 TTL，单位：秒（24 小时，远超内联重试与人工补卡的响应窗口） */
    public static final Long SECKILL_ROLLBACK_MARKER_TTL = 86400L;

    /**
     * 库存补偿死信 key: seckill:rollback:dlq
     * <p>
     * P1-3：内联重试全部失败后的补偿任务队列（List）。元素为
     * {@code orderId|voucherId|userId}；凭幂等标记可安全地重复执行/人工补卡，
     * 不会重复加库存。本项目无调度基建，消费靠运维/后续对账任务触发。
     * </p>
     */
    public static final String SECKILL_ROLLBACK_DLQ_KEY = "seckill:rollback:dlq";

    // ==================== 商铺相关 ====================

    /** 商铺地理位置（GeoHash）key 前缀: shop:geo:{typeId} */
    public static final String SHOP_GEO_KEY = "shop:geo:";

    // ==================== 内容（笔记）相关 ====================

    /**
     * 笔记点赞用户集合 key 前缀: blog:liked:{blogId}
     * <p>
     * 使用 Set 记录点赞用户，保证点赞幂等（SADD 返回 1 才计数）。
     * </p>
     */
    public static final String BLOG_LIKED_KEY = "blog:liked:";

    // ==================== 签到相关 ====================

    /** 用户签到 BitMap key 前缀: sign:{userId}:{yyyyMM} */
    public static final String USER_SIGN_KEY = "sign:";
}
