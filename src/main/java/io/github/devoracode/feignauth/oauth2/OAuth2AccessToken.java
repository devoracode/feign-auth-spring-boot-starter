package io.github.devoracode.feignauth.oauth2;

/**
 * Cached OAuth2 access token with an absolute expiry timestamp.
 *
 * @author Wenjie Liu
 * @since 1.0.0
 */
public class OAuth2AccessToken {

	private String accessToken;

	private long expireAt;

	public String getAccessToken() {
		return this.accessToken;
	}

	public void setAccessToken(String accessToken) {
		this.accessToken = accessToken;
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

}
