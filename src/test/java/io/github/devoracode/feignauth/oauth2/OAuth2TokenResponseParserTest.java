package io.github.devoracode.feignauth.oauth2;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import static org.assertj.core.api.Assertions.assertThat;

class OAuth2TokenResponseParserTest {

	private final OAuth2TokenResponseParser parser = new OAuth2TokenResponseParser(new ObjectMapper());

	@Test
	void usesConfiguredTokenExpiresInSecondsWhenSet() {
		String body = "{\"access_token\":\"token-value\",\"expires_in\":3600}";
		OAuth2AccessToken accessToken = this.parser.parse(ok(body), 60L, null, 1800L);

		long ttlMillis = accessToken.getExpireAt() - System.currentTimeMillis();
		assertThat(ttlMillis).isBetween(1730000L, 1800000L);
	}

	@Test
	void findsExpiresInAtRootWhenNotConfigured() {
		String body = "{\"access_token\":\"token-value\",\"expiresIn\":900}";
		OAuth2AccessToken accessToken = this.parser.parse(ok(body), 60L, null, null);

		long ttlMillis = accessToken.getExpireAt() - System.currentTimeMillis();
		assertThat(ttlMillis).isBetween(840000L, 900000L);
	}

	@Test
	void findsExpiresInInNestedObjectWithinThreeLevels() {
		String body = "{\"result\":{\"payload\":{\"expire_in\":1200}},\"access_token\":\"token-value\"}";
		OAuth2AccessToken accessToken = this.parser.parse(ok(body), 0L, null, null);

		long ttlMillis = accessToken.getExpireAt() - System.currentTimeMillis();
		assertThat(ttlMillis).isBetween(1190000L, 1200000L);
	}

	@Test
	void prefersShallowerExpiresInFieldWhenSearchingLayerByLayer() {
		String body = "{\"data\":{\"expires_in\":300},\"result\":{\"expires_in\":600},\"access_token\":\"token-value\"}";
		OAuth2AccessToken accessToken = this.parser.parse(ok(body), 0L, null, null);

		long ttlMillis = accessToken.getExpireAt() - System.currentTimeMillis();
		assertThat(ttlMillis).isBetween(290000L, 300000L);
	}

	@Test
	void doesNotSearchBeyondThreeLevels() {
		String body = "{\"l1\":{\"l2\":{\"l3\":{\"l4\":{\"expires_in\":500}}}},\"access_token\":\"token-value\"}";
		OAuth2AccessToken accessToken = this.parser.parse(ok(body), 0L, null, null);

		long ttlMillis = accessToken.getExpireAt() - System.currentTimeMillis();
		assertThat(ttlMillis).isBetween(7190000L, 7200000L);
	}

	private static ResponseEntity<String> ok(String body) {
		return new ResponseEntity<>(body, HttpStatus.OK);
	}

}
