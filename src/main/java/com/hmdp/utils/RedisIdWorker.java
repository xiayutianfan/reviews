package com.hmdp.utils;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

/**
 * id生成器
 * 使用的是Redis自增ID策略,每天一个key,方便统计订单量
 * 结构是: 符号位(最高位)+时间戳(31位)+计数器(32位)
 */
@Component
public class RedisIdWorker {

    private static final long BEGIN_TIMESTAMP = 1640995200L;
    //序列号的位数
    private static final int COUNT_BITS = 32;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    /**
     *前缀用来区分不同业务的,而且redis是k,v的形式
     */
    public long nextId(String keyPrefix) {
        //1.生成时间戳
        LocalDateTime now = LocalDateTime.now();
        long nowSecond = now.toEpochSecond(ZoneOffset.UTC);
        long timestamp = nowSecond - BEGIN_TIMESTAMP;
        //2.生成序列化
        //2.1获取当前日期,精确到天
        String date = now.format(DateTimeFormatter.ofPattern("yyyy:MM:dd"));
        //value是自增长的
        long count = stringRedisTemplate.opsForValue().increment("icr:" + keyPrefix + ":" + date);
        //3.拼接并返回,由于时间戳是在高位的,要移动32位,空出32位给序列号(count)
        return timestamp << COUNT_BITS | count;
    }



/*    public static void main(String[] args) {
        //生成秒数
        LocalDateTime time = LocalDateTime.of(2022,1, 1, 0, 0,0 );
        long second = time.toEpochSecond(ZoneOffset.UTC);
        System.out.println(second);
    }*/
}
