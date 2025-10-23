package com.hmdp.utils;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

/**
 * @Auther: zzzlew
 * @Date: 2025/10/23 - 10 - 23 - 20:30
 * @Description: com.hmdp.utils
 * @version: 1.0
 */
@Component
public class RedisIdWorker {

    @Resource
    StringRedisTemplate stringRedisTemplate;

    // 开始时间戳
    private static final long BEGIN_TIMESTAMP = 1735689600L;
    // 序列号位数
    private static final int COUNT_BITS = 32;

    public long nextId(String keyPrefix) {
        // 生成时间戳
        LocalDateTime now = LocalDateTime.now();
        long nowSecond = now.toEpochSecond(ZoneOffset.UTC);
        long timestamp = nowSecond - BEGIN_TIMESTAMP;
        // 生成序列号
        // 获取当前日期精确到天数
        String date = now.format(DateTimeFormatter.ofPattern("yyyy:MM:dd"));
        // 自增长
        long count = stringRedisTemplate.opsForValue().increment("icr:" + keyPrefix + ":" + date);
        // 拼接并返回
        return timestamp << COUNT_BITS | count;
    }

}
