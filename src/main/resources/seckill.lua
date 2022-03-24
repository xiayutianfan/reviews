-- 异步秒杀的lua脚本

-- 1.参数列表
-- 1.1.优惠券id
local voucherId = ARGV[1]
-- 1.2 用户id
local userId = ARGV[2]

-- 2.数据key
-- 2.1库存key 这里拼接不是用 + 而是 ..
local stockKey = 'seckill:stock:' .. voucherId
-- 2.2订单key
local orderKey = 'seckill:order:' .. voucherId

-- 3.脚本业务
-- 3.1判断库存是否充足,get stockKey
-- 这里字符串是没办法和数值类型做比较的,得转换 tonumber
if( tonumber(redis.call('get', stockKey)) <= 0) then
    -- 库存不足,返回1
    return 1
end
-- 3.2.判断用户是否下单 使用SISMEMBER orderKey userId
if( redis.call('sismember', orderKey, userId) == 1) then
    -- 3.3 存在说明是重复下单,返回2
    return 2;
end
-- 3.4 扣库存 incrby stockKey -1; incrby在redis是加法的意思,加-1就是减1
redis.call('incrby', stockKey, -1)
-- 3.5 下单,保存用户 使用sadd orderKey userId
redis.call('sadd', orderKey, userId)
return 0