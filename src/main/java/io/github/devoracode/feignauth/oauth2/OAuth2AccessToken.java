package io.github.devoracode.feignauth.oauth2;

import java.io.Serializable;
import java.util.Objects;

/**
 * Cached OAuth2 access token with an absolute expiry timestamp.
 *
 * @author Wenjie Liu
 * @since 1.0.0
 */
public class OAuth2AccessToken implements Serializable {

	private static final long serialVersionUID = 1L;

	private String accessToken;

	private long ttl;

	private long expireAt;

	public String getAccessToken() {
		return this.accessToken;
	}

	public void setAccessToken(String accessToken) {
		this.accessToken = accessToken;
	}

	public long getTtl() {
		return ttl;
	}

	public void setTtl(long ttl) {
		this.ttl = ttl;
	}

	public long getExpireAt() {
		return this.expireAt;
	}

	public void setExpireAt(long expireAt) {
		this.expireAt = expireAt;
	}

	public boolean isExpired() {
		return System.currentTimeMillis() >= this.expireAt;
	}

	@Override
	public boolean equals(Object o) {
		if (this == o) return true;
		if (o == null || getClass() != o.getClass()) return false;
		OAuth2AccessToken that = (OAuth2AccessToken) o;
		return ttl == that.ttl && expireAt == that.expireAt
				&& Objects.equals(accessToken, that.accessToken);
	}

	@Override
	public int hashCode() {
		return Objects.hash(accessToken, ttl, expireAt);
	}
}
