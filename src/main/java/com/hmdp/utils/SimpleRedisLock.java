package com.hmdp.utils;

import cn.hutool.core.lang.UUID;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;

import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * 实现分布式锁
 */
public class SimpleRedisLock implements ILock {

    StringRedisTemplate stringRedisTemplate;

    private String name;
    private static final String KEY_PREFIX = "lock:";
    private static final String ID_PREFIX = UUID.randomUUID().toString(true) + "-";
    private static final DefaultRedisScript<Long> UNLOCK_SCRIPT;

    static {
        UNLOCK_SCRIPT = new DefaultRedisScript<>();
        //获取脚本的路径
        UNLOCK_SCRIPT.setLocation(new ClassPathResource("unlock.lua"));
        //通知脚本我们接收的返回值是什么
        UNLOCK_SCRIPT.setResultType(Long.class);
    }

    public SimpleRedisLock(StringRedisTemplate stringRedisTemplate, String name) {
        this.stringRedisTemplate = stringRedisTemplate;
        this.name = name;
    }

    /**
     * 尝试获取锁
     *
     * @param timeoutSec 锁持有的超时时间,过期后自动释放锁
     * @return true代表获取锁成功, false代表获取锁失败
     */
    @Override
    public boolean tryLock(Long timeoutSec) {
        //获取线程唯一标识
        String threadId = ID_PREFIX + Thread.currentThread().getId();
        //获取锁,setIfAbsent如果不存在才执行
        Boolean success = stringRedisTemplate.opsForValue().setIfAbsent(KEY_PREFIX + name, threadId, timeoutSec, TimeUnit.SECONDS);
        return Boolean.TRUE.equals(success);
    }


    /**
     * 基于lua脚本改进分布式锁
     */
    @Override
    public void unlock() {
        //把KEY_PREFIX + name变成一个集合,因为下面的参数要的是一个集合类型的
        List<String> Key = Collections.singletonList(KEY_PREFIX + name);
        String threadId = ID_PREFIX + Thread.currentThread().getId();
        //调用lua脚本,成功就释放锁,超时也释放锁,不需要返回值
        stringRedisTemplate.execute(UNLOCK_SCRIPT, Key, threadId);
    }


    /**
     * 释放锁
     */
/*    @Override
    public void unlock() {
        //获取线程标识
        String threadId =ID_PREFIX + Thread.currentThread().getId();
        //获取锁中的标识
        String id = stringRedisTemplate.opsForValue().get(KEY_PREFIX + name);
        //判断锁是否一致
        if(threadId.equals(id)) {
            //释放锁
            stringRedisTemplate.delete(KEY_PREFIX + name);
        }
    }*/
}