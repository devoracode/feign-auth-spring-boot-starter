package io.github.devoracode.feignauth.oauth2.lock;

import io.github.devoracode.feignauth.autoconfigure.FeignAuthProperties;
import io.github.devoracode.feignauth.exception.FeignAuthTokenException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;

import java.util.Collections;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.TimeUnit;

public class RedisLockProvider implements LockProvider {

    private static final String LOCK_SUFFIX = "lock:";
    private static final int DEFAULT_MAX_RETRIES = 60;
    private static final long DEFAULT_RETRY_INTERVAL_MS = 200;
    private static final long DEFAULT_LOCK_TTL_SECONDS = 30;

    private static final String UNLOCK_SCRIPT =
            "if redis.call('get', KEYS[1]) == ARGV[1] then return redis.call('del', KEYS[1]) else return 0 end";

    private final StringRedisTemplate redisTemplate;
    private final String keyPrefix;
    private final DefaultRedisScript<Long> unlockScript;

    public RedisLockProvider(StringRedisTemplate redisTemplate, FeignAuthProperties.Redis redis) {
        this.redisTemplate = redisTemplate;
        this.keyPrefix = redis.getKeyPrefix();
        this.unlockScript = new DefaultRedisScript<>(UNLOCK_SCRIPT, Long.class);
    }

    @Override
    public <T> T execute(String key, Callable<T> callable) {
        String lockKey = keyPrefix + LOCK_SUFFIX + key;
        String requestId = UUID.randomUUID().toString();
        boolean locked = false;
        int retryCount = 0;

        try {
            while (!tryLock(lockKey, requestId)) {
                if (retryCount++ >= DEFAULT_MAX_RETRIES) {
                    throw new FeignAuthTokenException("Failed to acquire lock for key: " + key);
                }
                Thread.sleep(DEFAULT_RETRY_INTERVAL_MS);
            }
            locked = true;
            return callable.call();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new FeignAuthTokenException("Lock acquisition interrupted for key: " + key, e);
        } catch (FeignAuthTokenException e) {
            throw e;
        } catch (Exception e) {
            throw new FeignAuthTokenException("Error executing with lock for key: " + key, e);
        } finally {
            if (locked) {
                unlock(lockKey, requestId);
            }
        }
    }

    private boolean tryLock(String lockKey, String requestId) {
        Boolean success = redisTemplate.opsForValue().setIfAbsent(lockKey, requestId, DEFAULT_LOCK_TTL_SECONDS, TimeUnit.SECONDS);
        return Boolean.TRUE.equals(success);
    }

    private void unlock(String lockKey, String requestId) {
        redisTemplate.execute(unlockScript, Collections.singletonList(lockKey), requestId);
    }
}
