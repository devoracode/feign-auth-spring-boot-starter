package io.github.devoracode.feignauth.oauth2;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.devoracode.feignauth.autoconfigure.FeignAuthProperties;
import io.github.devoracode.feignauth.exception.FeignAuthConfigurationException;
import io.github.devoracode.feignauth.exception.FeignAuthTokenException;
import io.github.devoracode.feignauth.header.HeaderManager;
import io.github.devoracode.feignauth.oauth2.lock.LocalLockProvider;
import io.github.devoracode.feignauth.oauth2.lock.LockProvider;
import io.github.devoracode.feignauth.oauth2.store.LocalTokenStore;
import io.github.devoracode.feignauth.oauth2.store.TokenStore;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.RedisConnectionFailureException;

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
		when(tokenStore.remove("svc:c1")).thenThrow(new RedisConnectionFailureException("redis down")).thenReturn(true);
		TokenFetcher tokenFetcher = createTokenFetcher(properties, tokenStore);

		// 存储故障必须转成 starter 自己的异常类型再交给业务方，而不是让 spring-data 的异常原样穿过
		// ErrorDecoder；同时保留 cause，排查时还能看出真实原因是 Redis 而非令牌本身
		assertThatThrownBy(() -> tokenFetcher.invalidateToken("svc", "/api/x"))
				.isInstanceOf(FeignAuthTokenException.class)
				.hasMessageContaining("redis down")
				.hasCauseInstanceOf(RedisConnectionFailureException.class);
		assertThat(tokenFetcher.invalidateToken("svc", "/api/x")).isTrue();
	}

	@Test
	void customStoreBusinessExceptionIsPassedThroughUnwrapped() {
		FeignAuthProperties properties = new FeignAuthProperties();
		properties.setServices(services(oauth2Service(client("c1"))));
		TokenStore tokenStore = mock(TokenStore.class);
		FeignAuthTokenException original = new FeignAuthTokenException("token revoked by admin");
		when(tokenStore.remove("svc:c1")).thenThrow(original);
		TokenFetcher tokenFetcher = createTokenFetcher(properties, tokenStore);

		// 使用方自建 TokenStore 主动抛出的业务异常不该被包装成「驱逐失败」
		assertThatThrownBy(() -> tokenFetcher.invalidateToken("svc", "/api/x")).isSameAs(original);
	}

	@Test
	void customStoreConfigurationExceptionIsPassedThroughUnwrapped() {
		FeignAuthProperties properties = new FeignAuthProperties();
		properties.setServices(services(oauth2Service(client("c1"))));
		TokenStore tokenStore = mock(TokenStore.class);
		FeignAuthConfigurationException original = new FeignAuthConfigurationException("store misconfigured");
		when(tokenStore.remove("svc:c1")).thenThrow(original);
		TokenFetcher tokenFetcher = createTokenFetcher(properties, tokenStore);

		// starter 自己声明的两类异常都不该被二次包装：包装后业务方按配置错误处理的分支就接不到了
		assertThatThrownBy(() -> tokenFetcher.invalidateToken("svc", "/api/x")).isSameAs(original);
	}

	@Test
	void resolveClientCollapsesRepeatedSlashes() {
		FeignAuthProperties properties = new FeignAuthProperties();
		TokenFetcher tokenFetcher = createTokenFetcher(properties);
		FeignAuthProperties.Service service = oauth2Service(
				client("event", "/api/measure"),
				client("telemetry", "/api/measure/telemetry"),
				client("default"));

		// 直接调用方（以及 base-url 中间带 // 的场景）传入的路径也要先折叠再匹配
		assertThat(tokenFetcher.resolveClient("measure", service, "/api//measure/telemetry/1").getId())
				.isEqualTo("telemetry");
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
