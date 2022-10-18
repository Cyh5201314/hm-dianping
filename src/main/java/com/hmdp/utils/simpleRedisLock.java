package com.hmdp.utils;

import cn.hutool.core.lang.UUID;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.concurrent.TimeUnit;


public class simpleRedisLock implements ILock{

    private StringRedisTemplate stringRedisTemplate;
    private String name;

    private static final String KEY_PREFIX = "lock:";
    private static final String ID_PREFIX = UUID.randomUUID().toString(true)+"-";
    private static final DefaultRedisScript<Long> UNLOCK_SCRPTI;
    static {
        UNLOCK_SCRPTI = new DefaultRedisScript<>();
        UNLOCK_SCRPTI.setLocation(new ClassPathResource("unlock.lua"));
        UNLOCK_SCRPTI.setResultType(Long.class);
    }

    public simpleRedisLock(StringRedisTemplate stringRedisTemplate, String name) {
        this.stringRedisTemplate = stringRedisTemplate;
        this.name = name;
    }

    @Override
    public boolean tryLock(long timeouSec) {
        //获取线程Id
        String threadId = ID_PREFIX+Thread.currentThread().getId();
        //如果不存在才执行
        Boolean success = stringRedisTemplate.opsForValue()
                .setIfAbsent(KEY_PREFIX+name,threadId,timeouSec, TimeUnit.SECONDS);
        return Boolean.TRUE.equals(success);
    }

    /**
     * 使用lua脚本改造释放锁的步骤,使其拥有原子性
     */
    @Override
    public void unlock() {
        //保证释放锁的原子性
        stringRedisTemplate.execute(UNLOCK_SCRPTI, Collections.singletonList(KEY_PREFIX+name),
            ID_PREFIX+Thread.currentThread().getId());
    }

//    @Override
//    public void unlock() {
//        //获取线程标识
//        String threadId = ID_PREFIX+Thread.currentThread().getId();
//        //获取锁中的标识
//        String id = stringRedisTemplate.opsForValue().get(KEY_PREFIX + name);
//        //判断标识是否一致
//        if (threadId.equals(id)){
//            //一致才可以删
//            stringRedisTemplate.delete(KEY_PREFIX+name);
//        }
//    }
}
