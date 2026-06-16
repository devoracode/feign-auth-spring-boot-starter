package io.github.devoracode.feignauth.autoconfigure;

import com.fasterxml.jackson.databind.ObjectMapper;
import feign.RequestInterceptor;
import io.github.devoracode.feignauth.feign.ServiceMatcher;
import io.github.devoracode.feignauth.header.HeaderCustomizer;
import io.github.devoracode.feignauth.header.HeaderManager;
import io.github.devoracode.feignauth.oauth2.OAuth2ClientMatcher;
import io.github.devoracode.feignauth.oauth2.OAuth2TokenRequestClient;
import io.github.devoracode.feignauth.oauth2.OAuth2TokenResponseParser;
import io.github.devoracode.feignauth.oauth2.TokenFetcher;
import io.github.devoracode.feignauth.oauth2.lock.LocalLockProvider;
import io.github.devoracode.feignauth.oauth2.lock.LockProvider;
import io.github.devoracode.feignauth.oauth2.lock.RedisLockProvider;
import io.github.devoracode.feignauth.oauth2.store.LocalTokenStore;
import io.github.devoracode.feignauth.oauth2.store.RedisTokenStore;
import io.github.devoracode.feignauth.oauth2.store.TokenStore;
import org.apache.http.client.config.RequestConfig;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClientBuilder;
import org.apache.http.impl.conn.PoolingHttpClientConnectionManager;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.AutoConfigureAfter;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.jackson.JacksonAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.client.HttpComponentsClientHttpRequestFactory;
import org.springframework.web.client.RestTemplate;

import java.util.Collections;
import java.util.List;

/**
 * Auto-configuration for feign-auth-spring-boot-starter.
 *
 * @author Wenjie Liu
 * @since 1.0.0
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnClass(RequestInterceptor.class)
@AutoConfigureAfter(JacksonAutoConfiguration.class)
@EnableConfigurationProperties(FeignAuthProperties.class)
public class FeignAuthAutoConfiguration {

	@Bean("feignAuthRestTemplate")
	public RestTemplate feignAuthRestTemplate() {
		PoolingHttpClientConnectionManager connectionManager = new PoolingHttpClientConnectionManager();
		connectionManager.setMaxTotal(200);
		connectionManager.setDefaultMaxPerRoute(50);

		RequestConfig requestConfig = RequestConfig.custom()
				.setConnectTimeout(5000)
				.setConnectionRequestTimeout(5000)
				.setSocketTimeout(10000)
				.build();

		CloseableHttpClient httpClient = HttpClientBuilder.create()
				.setConnectionManager(connectionManager)
				.setDefaultRequestConfig(requestConfig)
				.evictIdleConnections(30, java.util.concurrent.TimeUnit.SECONDS)
				.build();

		HttpComponentsClientHttpRequestFactory factory = new HttpComponentsClientHttpRequestFactory(httpClient);
		return new RestTemplate(factory);
	}

	@Bean
	@ConditionalOnMissingBean
	public ServiceMatcher serviceMatcher(FeignAuthProperties properties) {
		return new ServiceMatcher(properties);
	}

	@Bean
	@ConditionalOnMissingBean
	public OAuth2ClientMatcher oAuth2ClientMatcher() {
		return new OAuth2ClientMatcher();
	}

	@Bean
	@ConditionalOnMissingBean
	public ObjectMapper objectMapper() {
		return new ObjectMapper();
	}

	@Bean
	@ConditionalOnMissingBean
	public HeaderManager headerManager(ObjectProvider<List<HeaderCustomizer>> customizersProvider) {
		List<HeaderCustomizer> customizers = customizersProvider.getIfAvailable(Collections::emptyList);
		return new HeaderManager(customizers);
	}

	@Bean
	@ConditionalOnMissingBean(TokenStore.class)
	@ConditionalOnProperty(prefix = "feign.auth.cache", name = "provider", havingValue = "local",
			matchIfMissing = true)
	public TokenStore tokenStore() {

		return new LocalTokenStore();
	}

	@Bean
	@ConditionalOnMissingBean(TokenStore.class)
	@ConditionalOnProperty(prefix = "feign.auth.cache", name = "provider", havingValue = "redis")
	public TokenStore redisTokenStore(RedisTemplate<String,Object> redisTemplate,
			FeignAuthProperties properties) {

		return new RedisTokenStore(redisTemplate, properties.getAuth()
						.getCache()
						.getRedis());
	}

	@Bean
	@ConditionalOnMissingBean(LockProvider.class)
	@ConditionalOnProperty(prefix = "feign.auth.cache", name = "provider",
			havingValue = "local", matchIfMissing = true)
	public LockProvider lockProvider() {

		return new LocalLockProvider();
	}

	@Bean
	@ConditionalOnMissingBean(LockProvider.class)
	@ConditionalOnProperty(prefix = "feign.auth.cache",
			name = "provider", havingValue = "redis")
	public LockProvider redisLockProvider(StringRedisTemplate redisTemplate, FeignAuthProperties.Redis redis) {
		return new RedisLockProvider(redisTemplate, redis);
	}

	@Configuration(proxyBeanMethods = false)
	@ConditionalOnMissingBean(TokenFetcher.class)
	static class TokenFetcherConfiguration {

		@Bean
		TokenFetcher tokenFetcher(FeignAuthProperties properties, OAuth2ClientMatcher clientMatcher,
				@Qualifier("feignAuthRestTemplate") RestTemplate restTemplate, ObjectMapper objectMapper,
				HeaderManager headerManager, TokenStore tokenStore, LockProvider lockProvider) {
			OAuth2TokenResponseParser responseParser = new OAuth2TokenResponseParser(objectMapper);
			OAuth2TokenRequestClient tokenRequestClient = new OAuth2TokenRequestClient(restTemplate,
					responseParser, headerManager);
			return new TokenFetcher(properties, clientMatcher, tokenRequestClient, tokenStore, lockProvider);
		}

	}

}
