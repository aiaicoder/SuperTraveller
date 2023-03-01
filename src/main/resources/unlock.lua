-- KEY[1],ARGV[1] 分别是锁的key，和线程标示的值
if(redis.call('GET',KEYS[1]) == ARGV[1]) then
    -- 释放锁
    return redis.call('del',KEYS[1])
end
-- 不一致直接返回
return 0;