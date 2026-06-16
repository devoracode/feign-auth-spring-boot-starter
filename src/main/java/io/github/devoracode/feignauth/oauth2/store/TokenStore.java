package io.github.devoracode.feignauth.oauth2.store;

import io.github.devoracode.feignauth.oauth2.OAuth2AccessToken;

public interface TokenStore {

    OAuth2AccessToken get(String key);

    void put(String key, OAuth2AccessToken token);

    boolean remove(String key);
}
