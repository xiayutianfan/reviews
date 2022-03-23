package com.hmdp.utils;

import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.hmdp.entity.Shop;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

/**
 * Redis工具类
 */
@Slf4j
@Component
public class CacheClient {

    private final StringRedisTemplate stringRedisTemplate;

    //线程池
    private static final ExecutorService CACHE_REBUILD_EXECUTOR = Executors.newFixedThreadPool(3);


    public CacheClient(StringRedisTemplate stringRedisTemplate) {
        this.stringRedisTemplate = stringRedisTemplate;
    }

    /**
     * @param key key
     * @param value 对象
     * @param time 时间值
     * @param unit 时间单位
     */
    public void set(String key, Object value, Long time, TimeUnit unit) {
        //先序列化value
        String jsonStr = JSONUtil.toJsonStr(value);
        stringRedisTemplate.opsForValue().set(key, jsonStr, time, unit);
    }

    /**
     * 逻辑过期
     * @param key key
     * @param value 对象
     * @param time 时间值
     * @param unit 时间单位
     */
    public void setWithLogicalExpire(String key, Object value, Long time, TimeUnit unit) {
        //设置逻辑过期
        RedisData redisData = new RedisData();
        redisData.setData(value);
        redisData.setExpireTime(LocalDateTime.now().plusSeconds(unit.toSeconds(time)));
        //先序列化value
        String jsonStr = JSONUtil.toJsonStr(redisData);
        stringRedisTemplate.opsForValue().set(key, jsonStr);
    }

    /**
     * 解决缓存穿透
     * 根据前缀+id查询redis里是否有数据,没有则查询数据库
     * @param keyPrefix:前缀
     * @param id:根据id查询
     * @param type:对象
     * @param daFallback:调用者查询数据库
     * @param time 时间值
     * @param unit 时间单位
     */
    public <R, ID> R queryWithPassThrough(String keyPrefix, ID id, Class<R> type,
                                          Function<ID, R> daFallback, Long time, TimeUnit unit) {
        String key = keyPrefix + id;
        //1.从redis查询商铺缓存
        String Json = stringRedisTemplate.opsForValue().get(key);
        //2.判断是否存在,空字符串不会进入if里
        if(StrUtil.isNotBlank(Json)) {
            //3.存在,直接返回
            R r = JSONUtil.toBean(Json, type);
            return r;
        }

        //判断是否命中的是空值,上面的那个if已经判断过了,如果是空值,并且能走到这里,直接返回Null
        if (Json != null) {
            //返回错误信息
            return null;
        }

        //4.根据id查询数据库
        R r = daFallback.apply(id);
        //5.返回错误
        if (r == null) {
            //将空值写入redis
            stringRedisTemplate.opsForValue().set(key, "", RedisConstants.CACHE_NULL_TTL, TimeUnit.MINUTES);
            //返回错误信息
            return null;
        }
        //存在,写入redis
        this.set(key, r, time, unit);

        //返回
        return r;
    }

    /**
     * 使用逻辑过期来解决缓存击穿
     */
    public <R, ID> R queryWithLogicalExpire(String keyPrefix, ID id, Class<R> type, Function<ID, R> dbFallback,
                                            Long time, TimeUnit unit) {
        String key = keyPrefix + id;
        //1.从redis查询商铺缓存
        String shopJson = stringRedisTemplate.opsForValue().get(key);
        //2.如果为空,直接返回
        if(StrUtil.isBlank(shopJson)) {
            return null;
        }

        //命中,需要先把json反序列化为对象
        RedisData redisData = JSONUtil.toBean(shopJson, RedisData.class);
        //这里是JSONObject是因为,上面为json的反序列化得到的
        JSONObject data = (JSONObject) redisData.getData();
        R r = JSONUtil.toBean(data, type);
        LocalDateTime expireTime = redisData.getExpireTime();
        //判断逻辑是否过期
        if(expireTime == null){
            return null;
        }
        if(expireTime.isAfter(LocalDateTime.now())) {
            //未过期,直接返回店铺信息
            return r;
        }
        //已过期,需要重建
        //获取互斥锁
        String lockKey = RedisConstants.LOCK_SHOP_KEY + id;
        boolean isLock = tryLock(lockKey);
        //判断锁是否获取成功
        if(isLock) {
            //成功,开启独立线程,实现缓存重建
            CACHE_REBUILD_EXECUTOR.submit(() -> {
                try {
                    //重建缓存,这里只是为了测试设置为20秒,实际开发建议根据业务需求而设置时间
                    //先查数据库
                    R r1 = dbFallback.apply(id);
                    //再写入redis
                    this.setWithLogicalExpire(key, r1, time, unit);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }finally {
                    //释放锁
                    unLock(lockKey);
                }
            });
        }
        //返回过期的店铺信息
        return r;
    }

    /**
     * 互斥锁,解决缓存击穿问题
     * 获取锁
     */
    private boolean tryLock(String key) {
        //相当于redis的 setnx 设置有效期为10秒钟,根据业务而定
        Boolean flag = stringRedisTemplate.opsForValue().setIfAbsent(key, "1", 10, TimeUnit.SECONDS);
        //如果直接返回,会自动拆箱,有可能空指针异常
        return BooleanUtil.isTrue(flag);
    }
    /**
     * 释放锁
     */
    private void unLock(String key) {
        stringRedisTemplate.delete(key);
    }


}
