package io.github.devoracode.feignauth.token;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.devoracode.feignauth.autoconfigure.FeignAuthProperties;
import io.github.devoracode.feignauth.exception.FeignAuthConfigurationException;
import io.github.devoracode.feignauth.header.HeaderManager;
import io.github.devoracode.feignauth.oauth2.OAuth2ClientMatcher;
import io.github.devoracode.feignauth.oauth2.OAuth2TokenRequestClient;
import io.github.devoracode.feignauth.oauth2.OAuth2TokenResponseParser;
import io.github.devoracode.feignauth.oauth2.TokenFetcher;
import io.github.devoracode.feignauth.oauth2.lock.LocalLockProvider;
import io.github.devoracode.feignauth.oauth2.lock.LockProvider;
import io.github.devoracode.feignauth.oauth2.store.LocalTokenStore;
import io.github.devoracode.feignauth.oauth2.store.TokenStore;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestTemplate;

import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TokenFetcherTest {

	@Test
	void resolvesOAuth2ClientByLongestPathPrefixThenDefaultClient() {
		FeignAuthProperties properties = new FeignAuthProperties();
		TokenFetcher tokenFetcher = createTokenFetcher(properties);

		FeignAuthProperties.Service service = oauth2Service(
				client("event", "/api/measure"),
				client("telemetry", "/api/measure/telemetry"),
				client("default"));

		assertThat(tokenFetcher.resolveClient("measure", service, "/api/measure/telemetry/1").getId())
				.isEqualTo("telemetry");
		assertThat(tokenFetcher.resolveClient("measure", service, "/api/other/1").getId())
				.isEqualTo("default");
	}

	@Test
	void rejectsMultipleDefaultOAuth2Clients() {
		FeignAuthProperties properties = new FeignAuthProperties();
		TokenFetcher tokenFetcher = createTokenFetcher(properties);
		FeignAuthProperties.Service service = oauth2Service(client("first"), client("second"));

		assertThatThrownBy(() -> tokenFetcher.resolveClient("measure", service, "/api/other"))
				.isInstanceOf(FeignAuthConfigurationException.class)
				.hasMessageContaining("Multiple default OAuth2 clients");
	}

	private static TokenFetcher createTokenFetcher(FeignAuthProperties properties) {
		ObjectMapper objectMapper = new ObjectMapper();
		OAuth2TokenRequestClient tokenRequestClient = new OAuth2TokenRequestClient(
				new RestTemplate(), new OAuth2TokenResponseParser(objectMapper), new HeaderManager());
		TokenStore tokenStore = new LocalTokenStore();
		LockProvider lockProvider = new LocalLockProvider();
		return new TokenFetcher(properties, new OAuth2ClientMatcher(), tokenRequestClient, tokenStore, lockProvider);
	}

	private static FeignAuthProperties.Service oauth2Service(FeignAuthProperties.Client... clients) {
		FeignAuthProperties.Service service = new FeignAuthProperties.Service();
		service.setBaseUrl("https://api.service-a.com");

		FeignAuthProperties.Auth auth = new FeignAuthProperties.Auth();
		auth.setType("oauth2");
		auth.setTokenUrl("https://api.service-a.com/oauth/token");
		auth.setClients(Arrays.asList(clients));
		service.setAuth(auth);
		return service;
	}

	private static FeignAuthProperties.Client client(String id, String... pathPrefixes) {
		FeignAuthProperties.Client client = new FeignAuthProperties.Client();
		client.setId(id);
		client.setSecret("secret-" + id);
		client.setPathPrefixes(Arrays.asList(pathPrefixes));
		return client;
	}

}
