package com.hmdp.utils;

import cn.hutool.core.lang.UUID;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;

import java.util.Collections;
import java.util.concurrent.TimeUnit;

/**
 * @author 小新
 * date 2023/2/23
 * @apiNode 实现redisLock
 */
public class SimpleRedisLockRe implements ILock {

    private StringRedisTemplate stringRedisTemplate;
    private String name;
    private final String KEY_PREFIX = "lock:";
    private String ID_PREFIX = UUID.randomUUID().toString(true) + "-";

    private static final DefaultRedisScript<Long> UNLOCK_SCRIPT;

    //在释放锁时提前加载，因为每次执行都去读文件就会产生io流性能就不好
    static {
        UNLOCK_SCRIPT = new DefaultRedisScript<>();
        //加载lua脚本
        UNLOCK_SCRIPT.setLocation(new ClassPathResource("unlock.lua"));
        //返回值类型
        UNLOCK_SCRIPT.setResultType(Long.class);
    }


    public SimpleRedisLockRe(StringRedisTemplate stringRedisTemplate, String name) {
        this.stringRedisTemplate = stringRedisTemplate;
        this.name = name;
    }

    @Override
    public boolean tryLock(long timeoutSec) {
        String threadId = ID_PREFIX + Thread.currentThread().getId();
        Boolean b = stringRedisTemplate.opsForValue().setIfAbsent(KEY_PREFIX + name, threadId, timeoutSec, TimeUnit.SECONDS);
        return Boolean.TRUE.equals(b);
    }


    @Override
    public void unlock() {
        //调用lua脚本
        //因为调用的是脚本，所以能保证原子性
        stringRedisTemplate.execute(UNLOCK_SCRIPT,
                Collections.singletonList(KEY_PREFIX + name),
                ID_PREFIX + Thread.currentThread().getId());

    }

    //@Override
    //public void unlock() {
    //    String threadId = ID_PREFIX + Thread.currentThread().getId();
    //    String s = stringRedisTemplate.opsForValue().get(KEY_PREFIX + name);
    //    if(threadId.equals(s)){
    //        stringRedisTemplate.delete(KEY_PREFIX+name);
    //    }
    //
    //}
}
