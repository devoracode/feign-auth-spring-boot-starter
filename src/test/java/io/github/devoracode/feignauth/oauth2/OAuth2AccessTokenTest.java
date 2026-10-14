package io.github.devoracode.feignauth.oauth2;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class OAuth2AccessTokenTest {

	@Test
	void toStringDoesNotLeakAccessToken() {
		OAuth2AccessToken token = new OAuth2AccessToken();
		token.setAccessToken("SECRET-TOKEN-VALUE");
		token.setTtl(60L);
		token.setExpireAt(123456L);

		assertThat(token.toString()).doesNotContain("SECRET-TOKEN-VALUE")
				.contains("ttl=60");
	}

	@Test
	void equalsAndHashCodeStillCompareAllFields() {
		OAuth2AccessToken a = token("t1", 60L, 100L);
		OAuth2AccessToken b = token("t1", 60L, 100L);
		OAuth2AccessToken c = token("t2", 60L, 100L);

		assertThat(a).isEqualTo(b).hasSameHashCodeAs(b);
		assertThat(a).isNotEqualTo(c);
	}

	private static OAuth2AccessToken token(String accessToken, long ttl, long expireAt) {
		OAuth2AccessToken token = new OAuth2AccessToken();
		token.setAccessToken(accessToken);
		token.setTtl(ttl);
		token.setExpireAt(expireAt);
		return token;
	}

}
