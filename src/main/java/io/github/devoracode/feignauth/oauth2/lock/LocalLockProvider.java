package io.github.devoracode.feignauth.oauth2.lock;

import io.github.devoracode.feignauth.exception.FeignAuthTokenException;

import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

public class LocalLockProvider implements LockProvider {

    private final ConcurrentHashMap<String, LockEntry> lockMap = new ConcurrentHashMap<>();
    
    private static final int CLEANUP_THRESHOLD = 100;
    private static final long MAX_LOCK_AGE_MS = 60000;
    private final AtomicInteger operationCount = new AtomicInteger(0);

    @Override
    public <T> T execute(String key, Callable<T> callable) {
        LockEntry entry = lockMap.computeIfAbsent(key, k -> new LockEntry());
        try {
            synchronized (entry.lock) {
                entry.timestamp = System.currentTimeMillis();
                return callable.call();
            }
        } catch (Exception e) {
            throw new FeignAuthTokenException("Error executing with lock for key: " + key, e);
        } finally {
            maybeCleanup();
        }
    }

    private void maybeCleanup() {
        if (operationCount.incrementAndGet() >= CLEANUP_THRESHOLD) {
            operationCount.set(0);
            cleanupExpiredLocks();
        }
    }

    private void cleanupExpiredLocks() {
        long now = System.currentTimeMillis();
        lockMap.entrySet().removeIf(entry -> now - entry.getValue().timestamp > MAX_LOCK_AGE_MS);
    }

    private static class LockEntry {
        final Object lock = new Object();
        volatile long timestamp = System.currentTimeMillis();
    }
}