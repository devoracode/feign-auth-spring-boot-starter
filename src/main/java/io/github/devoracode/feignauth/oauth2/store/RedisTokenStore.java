package io.github.devoracode.feignauth.oauth2.store;

import io.github.devoracode.feignauth.autoconfigure.FeignAuthProperties;
import io.github.devoracode.feignauth.oauth2.OAuth2AccessToken;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.springframework.data.redis.core.RedisTemplate;

import java.util.concurrent.TimeUnit;

public class RedisTokenStore implements TokenStore {

    private static final Log logger = LogFactory.getLog(RedisTokenStore.class);

    private static final String TOKEN_SUFFIX = "token:";

    private final RedisTemplate<Object, Object> redisTemplate;

    private final String keyPrefix;

    public RedisTokenStore(RedisTemplate<Object, Object> redisTemplate, FeignAuthProperties.Redis redis) {
        this.redisTemplate = redisTemplate;
        this.keyPrefix = redis.getKeyPrefix();
    }

    @Override
    public OAuth2AccessToken get(String key) {
        String fullKey = keyPrefix + TOKEN_SUFFIX + key;
        Object value = redisTemplate.opsForValue().get(fullKey);
        if (value == null) {
            return null;
        }
        if (value instanceof OAuth2AccessToken) {
            return (OAuth2AccessToken) value;
        }
        // A foreign value (e.g. serializer mismatch) would silently re-fetch every
        // request; drop it once so the next put overwrites with the right type.
        logger.warn("FeignAuth [redis-store] unexpected value type " + value.getClass().getName()
                + " for key '" + fullKey + "', removing it");
        redisTemplate.delete(fullKey);
        return null;
    }

    @Override
    public void put(String key, OAuth2AccessToken token) {
        // SETEX rejects ttl <= 0, so fall back to the shortest valid expiry
        long ttl = Math.max(1L, token.getTtl());
        redisTemplate.opsForValue().set(keyPrefix + TOKEN_SUFFIX + key, token, ttl, TimeUnit.SECONDS);
    }

    @Override
    public boolean remove(String key) {
        return redisTemplate.delete(keyPrefix + TOKEN_SUFFIX + key);
    }
}
