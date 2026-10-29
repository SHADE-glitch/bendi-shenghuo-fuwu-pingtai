-- seckill_rollback.lua
-- 取消/退款后的秒杀库存补偿：原子 + 幂等（P1-3）
-- KEYS[1] = seckill:stock:{voucherId}   库存计数 key
-- KEYS[2] = seckill:orders:{voucherId}  已占位用户 Set key
-- KEYS[3] = seckill:rollback:{orderId}  补偿幂等标记 key
-- ARGV[1] = userId                      当前用户 ID
-- ARGV[2] = marker TTL（秒）            幂等标记过期时间，由 Java 侧常量注入
--
-- 返回值：
--   0  已补偿过（幂等命中，跳过）
--   1  本次执行了补偿
--
-- 为什么需要幂等标记：补偿可能在「脚本已在服务端执行、响应丢失」后被重放，
-- 没有 SET NX 这一关，重放会给库存重复 +1，Redis 永久高于 DB。
--
-- 为什么两个 Redis 写要在同一个脚本里：INCR 与 SREM 是两条独立命令，
-- 分两次执行时「INCR 成功、SREM 失败」会留下「库存回了、占位还在」的中间态，
-- 用户无法再次抢购；合并后要么都生效、要么都不生效。

-- 1. 幂等标记：SET NX 失败说明本订单已补偿过，直接跳过
if redis.call('set', KEYS[3], '1', 'NX', 'EX', ARGV[2]) == false then
    return 0
end

-- 2. 库存 +1。key 不存在说明 Redis 侧早已没有这张券的账本
--    （与改动前 hasKey 守卫同语义），不凭空创建
if redis.call('exists', KEYS[1]) == 1 then
    redis.call('incrby', KEYS[1], 1)
end

-- 3. 移除用户占位（集合不存在时 SREM 为 no-op）
redis.call('srem', KEYS[2], ARGV[1])

return 1
