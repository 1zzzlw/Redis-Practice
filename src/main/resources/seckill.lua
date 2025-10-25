-- 参数列表
-- 优惠卷id
local voucherId = ARGV[1]
-- 用户id
local userId = ARGV[2]

-- 数据key
-- 库存key
local stockKey = 'seckill:stock:' .. voucherId
-- 订单key
local orderKey = 'seckill:order:' .. voucherId

-- 脚本业务
-- 判断库存是否充足
if (tonumber(redis.call('get', stockKey)) <= 0) then
    -- 库存不足，返回1
    return 1
end
-- 执行到这里说明库存充足，则判断用户是否下单
if (redis.call('sismember', orderKey, userId) == 1) then
    -- 用户id存在，说明是重复下单，返回2
    return 2
end
-- 执行到这里说明库存充足并且用户没有下过单，则扣减库存
redis.call('incrby', stockKey, -1)
-- 然后下单保存用户信息
redis.call('sadd', orderKey, userId)
-- 成功
return 0