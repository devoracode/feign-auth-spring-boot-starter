package io.github.devoracode.feignauth.token;

import lombok.Data;

@Data
public class TokenCacheEntry {

    private String accessToken;
    private long expireAt;

    public boolean isExpired() {
        return System.currentTimeMillis() >= expireAt;
    }
}
