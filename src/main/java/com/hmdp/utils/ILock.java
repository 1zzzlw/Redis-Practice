package com.hmdp.utils;

/**
 * @Auther: zzzlew
 * @Date: 2025/10/24 - 10 - 24 - 22:20
 * @Description: com.hmdp.utils
 * @version: 1.0
 */
public interface ILock {
    // 尝试获取锁
    boolean tryLock(long timeoutSec);

    // 释放锁
    void unlock();

}
