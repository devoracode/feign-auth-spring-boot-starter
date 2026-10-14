package io.github.devoracode.feignauth.oauth2.lock;

import io.github.devoracode.feignauth.exception.FeignAuthTokenException;

import java.util.concurrent.Callable;
import java.util.concurrent.locks.ReentrantLock;

public class LocalLockProvider implements LockProvider {

    // 固定的分段数避免了无上限的按 key 锁对象，以及可能在他方仍持有锁时
    // 将其淘汰的、存在竞态的 inUse/时间戳清理逻辑。
    private static final int STRIPE_COUNT = 64;

    private final ReentrantLock[] stripes = new ReentrantLock[STRIPE_COUNT];

    public LocalLockProvider() {
        for (int i = 0; i < STRIPE_COUNT; i++) {
            this.stripes[i] = new ReentrantLock();
        }
    }

    @Override
    public <T> T execute(String key, Callable<T> callable) {
        ReentrantLock lock = this.stripes[stripeIndex(key)];
        lock.lock();
        try {
            return callable.call();
        } catch (Exception e) {
            throw new FeignAuthTokenException("Error executing with lock for key: " + key, e);
        } finally {
            lock.unlock();
        }
    }

    private static int stripeIndex(String key) {
        int hash = key == null ? 0 : key.hashCode();
        // Math.abs(Integer.MIN_VALUE) 结果仍为负数，因此改用位掩码
        return (hash & 0x7fffffff) % STRIPE_COUNT;
    }

}
