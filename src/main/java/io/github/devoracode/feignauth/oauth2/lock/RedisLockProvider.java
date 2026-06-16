package io.github.devoracode.feignauth.oauth2.lock;

import io.github.devoracode.feignauth.autoconfigure.FeignAuthProperties;
import io.github.devoracode.feignauth.exception.FeignAuthTokenException;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.TimeUnit;

public class RedisLockProvider implements LockProvider {

    private static final String LOCK_SUFFIX = "lock:";
    private static final int DEFAULT_MAX_RETRIES = 300;
    private static final long DEFAULT_RETRY_INTERVAL_MS = 100;
    private static final long DEFAULT_LOCK_TTL_SECONDS = 30;

    private final StringRedisTemplate redisTemplate;
    private final String keyPrefix;

    public RedisLockProvider(StringRedisTemplate redisTemplate, FeignAuthProperties.Redis redis) {
        this.redisTemplate = redisTemplate;
        this.keyPrefix = redis.getKeyPrefix();
    }

    @Override
    public <T> T execute(String key, Callable<T> callable) {
        String lockKey = keyPrefix + LOCK_SUFFIX + key;
        String requestId = UUID.randomUUID().toString();
        int retryCount = 0;

        try {
            while (!tryLock(lockKey, requestId)) {
                if (retryCount++ >= DEFAULT_MAX_RETRIES) {
                    throw new FeignAuthTokenException("Failed to acquire lock for key: " + key);
                }
                Thread.sleep(DEFAULT_RETRY_INTERVAL_MS);
            }
            return callable.call();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new FeignAuthTokenException("Lock acquisition interrupted for key: " + key, e);
        } catch (FeignAuthTokenException e) {
            throw e;
        } catch (Exception e) {
            throw new FeignAuthTokenException("Error executing with lock for key: " + key, e);
        } finally {
            unlock(lockKey, requestId);
        }
    }

    private boolean tryLock(String lockKey, String requestId) {
        Boolean success = redisTemplate.opsForValue().setIfAbsent(
                lockKey, requestId, DEFAULT_LOCK_TTL_SECONDS, TimeUnit.SECONDS);
        return Boolean.TRUE.equals(success);
    }

    private void unlock(String lockKey, String requestId) {
        RedisCallback<Void> callback = new RedisCallback<Void>() {
            @Override
            public Void doInRedis(RedisConnection connection) {
                byte[] keyBytes = lockKey.getBytes(StandardCharsets.UTF_8);
                byte[] valueBytes = connection.get(keyBytes);
                if (valueBytes != null) {
                    String current = new String(valueBytes, StandardCharsets.UTF_8);
                    if (requestId.equals(current)) {
                        connection.del(keyBytes);
                    }
                }
                return null;
            }
        };
        redisTemplate.execute(callback);
    }
}