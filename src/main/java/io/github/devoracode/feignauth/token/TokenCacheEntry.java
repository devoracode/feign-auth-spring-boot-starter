package io.github.devoracode.feignauth.token;

import lombok.Data;

/**
 * A cache entry that stores an OAuth2 access token together with its expiry timestamp.
 *
 * <p>Instances are created by {@link TokenFetcher} after a successful token request
 * and stored in an in-memory cache keyed by service name and client ID.
 *
 * <p>The {@link #isExpired()} method can be used to check whether the entry is still
 * valid before reusing the cached token.
 *
 * @author Wenjie Liu
 * @since 1.0.0
 */
@Data
public class TokenCacheEntry {

    /**
     * The OAuth2 access token value.
     */
    private String accessToken;

    /**
     * The absolute expiry time of this cache entry, expressed as the number of
     * milliseconds since the Unix epoch (i.e. the value returned by
     * {@link System#currentTimeMillis()} at the time the entry was created,
     * plus the effective TTL in milliseconds).
     */
    private long expireAt;

    /**
     * Returns {@code true} if this cache entry has expired and the token should
     * no longer be used.
     *
     * <p>The check is based on the current wall-clock time:
     * {@code System.currentTimeMillis() >= expireAt}.
     *
     * @return {@code true} if the entry has expired; {@code false} otherwise
     */
    public boolean isExpired() {
        return System.currentTimeMillis() >= expireAt;
    }
}
