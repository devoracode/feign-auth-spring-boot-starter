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

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

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

	@Test
	void evictingNothingKeepsTheNextEvictionAttemptAlive() {
		FeignAuthProperties properties = new FeignAuthProperties();
		properties.setServices(services(oauth2Service(client("c1"))));
		TokenStore tokenStore = mock(TokenStore.class);
		when(tokenStore.remove("svc:c1")).thenReturn(false, true);
		TokenFetcher tokenFetcher = createTokenFetcher(properties, tokenStore);

		// 缓存里的令牌已按 TTL 自行过期：本次什么都没驱逐，不该因此锁死后续真正的淘汰机会
		assertThat(tokenFetcher.invalidateToken("svc", "/api/x")).isFalse();
		assertThat(tokenFetcher.invalidateToken("svc", "/api/x")).isTrue();
	}

	@Test
	void evictionFailureLeavesNoCooldownBehind() {
		FeignAuthProperties properties = new FeignAuthProperties();
		properties.setServices(services(oauth2Service(client("c1"))));
		TokenStore tokenStore = mock(TokenStore.class);
		when(tokenStore.remove("svc:c1")).thenThrow(new RuntimeException("redis down")).thenReturn(true);
		TokenFetcher tokenFetcher = createTokenFetcher(properties, tokenStore);

		assertThatThrownBy(() -> tokenFetcher.invalidateToken("svc", "/api/x"))
				.hasMessage("redis down");
		assertThat(tokenFetcher.invalidateToken("svc", "/api/x")).isTrue();
	}

	@Test
	void productiveEvictionStartsTheCooldownWindow() {
		FeignAuthProperties properties = new FeignAuthProperties();
		properties.setServices(services(oauth2Service(client("c1"))));
		TokenStore tokenStore = mock(TokenStore.class);
		when(tokenStore.remove("svc:c1")).thenReturn(true);
		TokenFetcher tokenFetcher = createTokenFetcher(properties, tokenStore);

		assertThat(tokenFetcher.invalidateToken("svc", "/api/x")).isTrue();
		assertThat(tokenFetcher.invalidateToken("svc", "/api/x")).isFalse();
		verify(tokenStore, times(1)).remove("svc:c1");
	}

	private static TokenFetcher createTokenFetcher(FeignAuthProperties properties) {
		return createTokenFetcher(properties, new LocalTokenStore());
	}

	private static TokenFetcher createTokenFetcher(FeignAuthProperties properties, TokenStore tokenStore) {
		ObjectMapper objectMapper = new ObjectMapper();
		OAuth2TokenRequestClient tokenRequestClient = new OAuth2TokenRequestClient(
				new OAuth2TokenResponseParser(objectMapper), new HeaderManager(), objectMapper,
				new FeignAuthProperties.Http());
		LockProvider lockProvider = new LocalLockProvider();
		return new TokenFetcher(properties, new OAuth2ClientMatcher(), tokenRequestClient, tokenStore, lockProvider);
	}

	private static Map<String, FeignAuthProperties.Service> services(FeignAuthProperties.Service service) {
		Map<String, FeignAuthProperties.Service> map = new HashMap<>();
		map.put("svc", service);
		return map;
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
