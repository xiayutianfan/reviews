--这里的KEYS[1] 就是锁的key,这里的ARGV[1]就是当前线程的标识
--获取锁中的标识,判断是否与当前线程一致

-- 获取锁中的线程标识 get key
local id = redis.call('get', KEYS[1])
-- 比较线程标识与锁中的标识是否一致
if(id == ARGV[1]) then
    --释放锁 del key
    redis.call('del', KEYS[1])
end
return 0