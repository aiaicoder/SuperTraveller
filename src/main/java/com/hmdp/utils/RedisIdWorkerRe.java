package com.hmdp.utils;

import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import javax.swing.text.DateFormatter;
import java.text.DateFormat;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

/**
 * @author 小新
 * date 2023/2/22
 * @apiNode
 */
@Component
@Slf4j
public class RedisIdWorkerRe {
    /**
     * 当前的起始时间
     */
    private final  long BEGIN_TIMESTAMP = 1675330620L;

    @Resource
    private StringRedisTemplate stringRedisTemplate;
    /**
     * 32位bit
     */
    private final long COUNT_BITS = 32;

    public long getId(String prefixKey){
        //先获取当前时间
        LocalDateTime now = LocalDateTime.now();
        long nowTime = now.atOffset(ZoneOffset.UTC).toEpochSecond();
        long timestamp = nowTime - BEGIN_TIMESTAMP;

        //创建自增序列号
        //为了防止超过32位序列号，键值后可以加一个日期，同时方便统计每天的订单有多少
        String date = now.format(DateTimeFormatter.ofPattern("yyyy:MM:dd"));

        long increment = stringRedisTemplate.opsForValue().increment("icr:" + prefixKey+ ":" + date);

        return timestamp<<32 | increment;
    }
}
