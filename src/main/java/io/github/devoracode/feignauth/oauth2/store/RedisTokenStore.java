package io.github.devoracode.feignauth.oauth2.store;

import io.github.devoracode.feignauth.autoconfigure.FeignAuthProperties;
import io.github.devoracode.feignauth.oauth2.OAuth2AccessToken;
import org.springframework.data.redis.core.RedisTemplate;

import java.util.concurrent.TimeUnit;

public class RedisTokenStore implements TokenStore {

    private static final String TOKEN_SUFFIX = "token:";

    private final RedisTemplate<Object, Object> redisTemplate;

    private final String keyPrefix;

    public RedisTokenStore(RedisTemplate<Object, Object> redisTemplate, FeignAuthProperties.Redis redis) {
        this.redisTemplate = redisTemplate;
        this.keyPrefix = redis.getKeyPrefix();
    }

    @Override
    public OAuth2AccessToken get(String key) {
        Object value = redisTemplate.opsForValue().get(keyPrefix + TOKEN_SUFFIX + key);
        if (value instanceof OAuth2AccessToken) {
            return (OAuth2AccessToken) value;
        }
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
