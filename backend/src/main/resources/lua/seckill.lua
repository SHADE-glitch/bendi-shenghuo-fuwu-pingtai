-- seckill.lua
-- 秒杀原子性预检：库存扣减 + 用户占位
-- KEYS[1] = seckill:stock:{voucherId}   库存计数 key
-- KEYS[2] = seckill:orders:{voucherId}  已占位用户 Set key
-- ARGV[1] = userId                      当前用户 ID
--
-- 返回值：
--   -2  该用户已占位（即重复下单）
--   -1  库存不足
--    0  成功（已扣库存、已标记用户占位）

-- 1. 检查库存
local stock = tonumber(redis.call('get', KEYS[1]))
if stock == nil or stock <= 0 then
    return -1
end

-- 2. 检查用户是否已经占位（一人一单）
local isMember = redis.call('sismember', KEYS[2], ARGV[1])
if isMember == 1 then
    return -2
end

-- 3. 原子扣减库存
redis.call('incrby', KEYS[1], -1)

-- 4. 标记该用户已占位
redis.call('sadd', KEYS[2], ARGV[1])

return 0
