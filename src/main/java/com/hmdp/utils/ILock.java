package com.hmdp.utils;

public interface ILock {

    /**
     * 尝试获取锁
     * @param timeouSec
     * @return
     */
    boolean tryLock(long timeouSec);

    /**
     * 释放锁
     */
    void unlock();

}
