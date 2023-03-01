package com.hmdp.utils;

import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.RandomUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.hmdp.entity.Shop;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.Random;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import static com.hmdp.utils.RedisConstants.*;

/**
 * @author 小新
 * date 2023/2/21
 * @apiNode 缓存工具类
 */
@Component
@Slf4j
public class CacheClientRe {
    private final StringRedisTemplate stringRedisTemplate;

    public CacheClientRe(StringRedisTemplate stringRedisTemplate) {
        this.stringRedisTemplate = stringRedisTemplate;
    }

    // 将任意ava对象序列化为json并存储在string类型的key,并且设置过期时间
    public void set(String key, Object value, Long time, TimeUnit timeUnit) {
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(value), time, timeUnit);
    }

    //√根据指定的key查询缓存，并反序列化为指定类型，利用缓存空值的方式解决缓存穿透问题
    public <T, ID> T DealCacheThrough(String prefixKey, Long time, ID id, TimeUnit timeUnit, Class<T> type, Function<ID, T> function) {
        String key = prefixKey + id;
        String json = stringRedisTemplate.opsForValue().get(key);
        if (StrUtil.isNotBlank(json)) {
            return JSONUtil.toBean(json, type);
        }
        if (json != null) {
            return null;
        }

        T r = function.apply(id);

        if (r == null) {
            this.set(key, "", time, timeUnit);
            return null;
        }

        this.set(key, r, time, timeUnit);
        return r;
    }

    //将任意ava对象序列化为json并存储在string类型的key中，并且可以设置逻辑过期时间，用于处理缓存击穿问题
    public <T, ID> T CacheHit(String prefixKey, ID id, Class<T> type, Long time, TimeUnit unit, Function<ID, T> function) {
        String key = prefixKey + id;
        //1.根据id判断redis中是否带有缓存
        String Json = stringRedisTemplate.opsForValue().get(key);
        //判断是否命中
        if (StrUtil.isNotBlank(Json)) {
            //命中直接返回
            return JSONUtil.toBean(Json, type);
        }
        if (Json != null) {
            return null;
        }
        String keyLock = LOCK_SHOP_KEY + id;
        //缓存重建
        T r = null;
        try {
            boolean lock = getLock(keyLock);
            //尝试获取锁
            if (!lock) {
                //休眠
                Thread.sleep(50);
                //双重校验在判断是否其他线程已经修改了数据库,再此查看是否命中
                Json = stringRedisTemplate.opsForValue().get(key);
                if (StrUtil.isNotBlank(Json)) {
                    return JSONUtil.toBean(Json, type);
                }
                return CacheHit(prefixKey, id, type, time, unit, function);
            }
            //未命中去数据库中查询
            r = function.apply(id);
            //判断是否存在
            if (r == null) {
                //缓存空对象
                stringRedisTemplate.opsForValue().set(key, "", CACHE_NULL_TTL, TimeUnit.MINUTES);
                return null;
            }
            //写入到redis
            this.set(key, r, time + RandomUtil.randomLong(1, 5), unit);
            //返回信息
            return r;
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        } finally {
            //释放互斥锁
            FreeLock(keyLock);
        }
    }

    //根据指定的key查询缓存，并反序列化为指定类型，需要利用逻辑过期解决缓存击穿问题
    private static final ExecutorService CACHE_REBUILD_EXECUTOR = Executors.newFixedThreadPool(10);

    public <T, ID> T CacheLogical(String prefixKey, ID id, Class<T> type, Long time, TimeUnit unit, Function<ID, T> function) {
        String key = prefixKey + id;
        //1.根据id判断redis中是否带有缓存
        String Json = stringRedisTemplate.opsForValue().get(key);
        //判断是否命中
        if (StrUtil.isBlank(Json)) {
            //未命中
            return null;
        }
        //判断缓存是否过期
        RedisData redisData = JSONUtil.toBean(Json, RedisData.class);
        T r = JSONUtil.toBean((JSONObject) redisData.getData(), type);
        LocalDateTime expireTime = redisData.getExpireTime();

        if (expireTime.isAfter(LocalDateTime.now())) {
            //如果没有过期就直接返回
            return r;
        }
        //缓存重建
        String keyLock = LOCK_SHOP_KEY + id;
        //过期尝试获取锁
        if (getLock(keyLock)) {
            //双重验证
            Json = stringRedisTemplate.opsForValue().get(key);
            redisData = JSONUtil.toBean(Json, RedisData.class);
            r = JSONUtil.toBean((JSONObject) redisData.getData(), type);
            expireTime = redisData.getExpireTime();
            if (expireTime.isAfter(LocalDateTime.now())) {
                //如果没有过期就直接返回
                return r;
            }
            CACHE_REBUILD_EXECUTOR.submit(() -> {
                try {
                    T apply = function.apply(id);
                    //重建缓存
                    this.LogicalExpire(key, apply, time, unit);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                } finally {
                    //释放锁
                    FreeLock(keyLock);
                }
            });
        }
        return r;
    }


    public void LogicalExpire(String key, Object value, Long time, TimeUnit timeUnit) {
        RedisData redisData = new RedisData();
        redisData.setData(value);
        redisData.setExpireTime(LocalDateTime.now().plusSeconds(timeUnit.toSeconds(time)));
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(redisData));
    }

    public boolean getLock(String key) {
        Boolean b = stringRedisTemplate.opsForValue().setIfAbsent(key, "1", 10, TimeUnit.SECONDS);
        return BooleanUtil.isTrue(b);
    }

    public boolean FreeLock(String key) {
        Boolean delete = stringRedisTemplate.delete(key);
        return BooleanUtil.isTrue(delete);
    }


}
