package io.github.devoracode.feignauth.oauth2.store;

import io.github.devoracode.feignauth.oauth2.OAuth2AccessToken;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class LocalTokenStore implements TokenStore {

    private final Map<String, OAuth2AccessToken> cache = new ConcurrentHashMap<>();

    @Override
    public OAuth2AccessToken get(String key) {
        OAuth2AccessToken token = cache.get(key);
        if (token != null && token.isExpired()) {
            cache.remove(key);
            return null;
        }
        return token;
    }

    @Override
    public void put(String key, OAuth2AccessToken token) {
        cache.put(key, token);
    }

    @Override
    public boolean remove(String key) {
        return cache.remove(key) != null;
    }
}