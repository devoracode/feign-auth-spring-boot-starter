package io.github.devoracode.feignauth.autoconfigure;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.devoracode.feignauth.exception.FeignAuthConfigurationException;
import io.github.devoracode.feignauth.header.HeaderManager;
import io.github.devoracode.feignauth.oauth2.OAuth2TokenRequestClient;
import io.github.devoracode.feignauth.oauth2.OAuth2TokenResponseParser;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FeignAuthAutoConfigurationTest {

	private final ApplicationContextRunner runner = new ApplicationContextRunner()
			.withUserConfiguration(FeignAuthAutoConfiguration.class);

	@Test
	void tokenRequestClientIsManagedAndClosedOnShutdown() {
		AtomicReference<OAuth2TokenRequestClient> reference = new AtomicReference<>();
		this.runner.run(context -> {
			assertThat(context).hasSingleBean(OAuth2TokenRequestClient.class);
			reference.set(context.getBean(OAuth2TokenRequestClient.class));
		});

		// 容器关闭后，内部 HttpClient 连接池应已被容器调用 close() 关闭
		assertThatThrownBy(() -> reference.get().requestToken("svc", oauth2Service(), client()))
				.hasStackTraceContaining("shut down");
	}

	@Test
	void userDefinedTokenRequestClientReplacesDefault() throws Exception {
		OAuth2TokenRequestClient custom = new OAuth2TokenRequestClient(
				new OAuth2TokenResponseParser(new ObjectMapper()), new HeaderManager(), new ObjectMapper(),
				new FeignAuthProperties.Http());
		try {
			this.runner.withBean(OAuth2TokenRequestClient.class, () -> custom).run(context -> {
				assertThat(context).hasSingleBean(OAuth2TokenRequestClient.class);
				assertThat(context.getBean(OAuth2TokenRequestClient.class)).isSameAs(custom);
			});
		}
		finally {
			custom.close();
		}
	}

	@Test
	void httpPropertiesBindFromConfiguration() {
		this.runner.withPropertyValues("feign.auth.http.connect-timeout=2s", "feign.auth.http.max-total=5",
				"feign.auth.http.default-max-per-route=3")
				.run(context -> {
					FeignAuthProperties.Http http = context.getBean(FeignAuthProperties.class).getAuth().getHttp();
					assertThat(http.getConnectTimeout()).isEqualTo(java.time.Duration.ofSeconds(2));
					assertThat(http.getMaxTotal()).isEqualTo(5);
					assertThat(http.getDefaultMaxPerRoute()).isEqualTo(3);
				});
	}

	@Test
	void redisProviderWorksViaConnectionFactoryWithoutAnyUserTemplates() {
		this.runner.withPropertyValues("feign.auth.cache.provider=redis")
				.withBean(org.springframework.data.redis.connection.RedisConnectionFactory.class,
						() -> org.mockito.Mockito.mock(org.springframework.data.redis.connection.RedisConnectionFactory.class))
				.run(context -> {
					assertThat(context).hasSingleBean(
							io.github.devoracode.feignauth.oauth2.store.RedisTokenStore.class);
					assertThat(context).hasSingleBean(
							io.github.devoracode.feignauth.oauth2.lock.RedisLockProvider.class);
					assertThat(context).hasSingleBean(io.github.devoracode.feignauth.oauth2.TokenFetcher.class);
				});
	}

	@Test
	void invalidHttpPropertyFailsStartupNamingTheKey() {
		this.runner.withPropertyValues("feign.auth.http.socket-timeout=0s").run(context -> {
			assertThat(context).hasFailed();
			assertThat(context.getStartupFailure()).hasStackTraceContaining("feign.auth.http.socket-timeout");
		});
	}

	@Test
	void redisProviderWithoutConnectionFactoryNamesTheRootCause() {
		// provider=redis 却没有 RedisConnectionFactory 时，两个 redis Bean 的条件都不成立，
		// 使用方只能看到「TokenStore 注入不上」这条与根因无关的 NoSuchBeanDefinitionException
		this.runner.withPropertyValues("feign.auth.cache.provider=redis").run(context -> {
			assertThat(context).hasFailed();
			assertThat(context.getStartupFailure()).hasStackTraceContaining("feign.auth.cache.provider=redis")
					.hasStackTraceContaining("RedisConnectionFactory")
					.hasStackTraceContaining(FeignAuthConfigurationException.class.getName());
		});
	}

	@Test
	void redisProviderKeepsUserDefinedTokenStoreWhenNoConnectionFactoryExists() {
		// 使用方自备 TokenStore + LockProvider 时不该被快速失败拦截，哪怕容器里没有 Redis 连接
		this.runner.withPropertyValues("feign.auth.cache.provider=redis")
				.withBean(io.github.devoracode.feignauth.oauth2.store.TokenStore.class,
						io.github.devoracode.feignauth.oauth2.store.LocalTokenStore::new)
				.withBean(io.github.devoracode.feignauth.oauth2.lock.LockProvider.class,
						io.github.devoracode.feignauth.oauth2.lock.LocalLockProvider::new)
				.run(context -> assertThat(context).hasNotFailed());
	}

	private static FeignAuthProperties.Service oauth2Service() {
		FeignAuthProperties.Service service = new FeignAuthProperties.Service();
		service.setBaseUrl("http://127.0.0.1");
		FeignAuthProperties.Auth auth = new FeignAuthProperties.Auth();
		auth.setType("oauth2");
		auth.setTokenUrl("http://127.0.0.1:1/token");
		service.setAuth(auth);
		return service;
	}

	private static FeignAuthProperties.Client client() {
		FeignAuthProperties.Client client = new FeignAuthProperties.Client();
		client.setId("id");
		client.setSecret("secret");
		return client;
	}

}
