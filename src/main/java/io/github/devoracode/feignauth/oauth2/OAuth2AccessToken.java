package io.github.devoracode.feignauth.oauth2;

import lombok.Data;

import java.io.Serializable;

/**
 * Cached OAuth2 access token with an absolute expiry timestamp.
 *
 * @author Wenjie Liu
 * @since 1.0.0
 */
@Data
public class OAuth2AccessToken implements Serializable {

	private static final long serialVersionUID = 1L;

	private String accessToken;

	private long ttl;

	private long expireAt;

	public boolean isExpired() {
		return System.currentTimeMillis() >= this.expireAt;
	}

}
