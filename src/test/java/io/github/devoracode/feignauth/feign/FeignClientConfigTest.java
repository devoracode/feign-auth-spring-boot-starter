package io.github.devoracode.feignauth.feign;

import com.fasterxml.jackson.databind.ObjectMapper;
import feign.RetryableException;
import feign.Retryer;
import io.github.devoracode.feignauth.autoconfigure.FeignAuthProperties;
import io.github.devoracode.feignauth.exception.FeignAuthConfigurationException;
import io.github.devoracode.feignauth.header.HeaderManager;
import io.github.devoracode.feignauth.oauth2.TokenFetcher;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

import java.util.Arrays;
import java.util.LinkedHashMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

class FeignClientConfigTest {

	// ── service-matcher 路由 ──────────────────────────────────────────────

	@Test
	void matchesLongestPathPrefixBeforeFallbackForSameBaseUrl() {
		FeignAuthProperties properties = new FeignAuthProperties();
		properties.setServices(new LinkedHashMap<>());
		properties.getServices().put("fallback", apiKeyService("https://api.service-d.com", "sk-fallback"));
		properties.getServices().put("orders", apiKeyService("https://api.service-d.com", "sk-orders", "/api/orders"));

		FeignAuthRequestInterceptor interceptor = interceptor(properties);

		assertThat(interceptor.matchService("https://api.service-d.com", "/api/orders/1").getServiceName())
				.isEqualTo("orders");
		assertThat(interceptor.matchService("https://api.service-d.com/", "/api/products/1").getServiceName())
				.isEqualTo("fallback");
	}

	@Test
	void rejectsAmbiguousFallbackServicesForSameBaseUrl() {
		FeignAuthProperties properties = new FeignAuthProperties();
		properties.setServices(new LinkedHashMap<>());
		properties.getServices().put("first", apiKeyService("https://api.service-d.com", "sk-first"));
		properties.getServices().put("second", apiKeyService("https://api.service-d.com", "sk-second"));

		FeignAuthRequestInterceptor interceptor = interceptor(properties);

		assertThatThrownBy(() -> interceptor.matchService("https://api.service-d.com", "/api/anything"))
				.isInstanceOf(FeignAuthConfigurationException.class)
				.hasMessageContaining("Multiple fallback services");
	}

	@Test
	void rejectsAmbiguousSameLengthPathPrefixesForSameBaseUrl() {
		FeignAuthProperties properties = new FeignAuthProperties();
		properties.setServices(new LinkedHashMap<>());
		properties.getServices().put("first", apiKeyService("https://api.service-d.com", "sk-first", "/api/orders"));
		properties.getServices().put("second", apiKeyService("https://api.service-d.com", "sk-second", "/api/orders"));

		FeignAuthRequestInterceptor interceptor = interceptor(properties);

		assertThatThrownBy(() -> interceptor.matchService("https://api.service-d.com", "/api/orders/1"))
				.isInstanceOf(FeignAuthConfigurationException.class)
				.hasMessageContaining("Multiple services matched");
	}

	@Test
	void matchesWhenConfiguredBaseUrlCarriesPathAndTargetInterpolatesIt() {
		FeignAuthProperties properties = new FeignAuthProperties();
		properties.setServices(new LinkedHashMap<>());
		properties.getServices().put("orders", apiKeyService("https://api.service-d.com/v1", "sk-orders", "/api/orders"));

		FeignAuthRequestInterceptor interceptor = interceptor(properties);

		// 典型写法：@FeignClient(url = "${feign.services.orders.base-url}")，target URL 与配置完全一致
		assertThat(interceptor.matchService("https://api.service-d.com", "/v1/api/orders/1").getServiceName())
				.isEqualTo("orders");
	}

	@Test
	void matchesIgnoringDefaultPortAndHostCase() {
		FeignAuthProperties properties = new FeignAuthProperties();
		properties.setServices(new LinkedHashMap<>());
		properties.getServices().put("orders", apiKeyService("https://API.Service-D.com:443", "sk-orders", "/api/orders"));

		FeignAuthRequestInterceptor interceptor = interceptor(properties);

		assertThat(interceptor.matchService("https://api.service-d.com", "/api/orders/1").getServiceName())
				.isEqualTo("orders");
		assertThat(interceptor.matchService("https://api.service-d.com:443", "/api/orders/1").getServiceName())
				.isEqualTo("orders");
	}

	@Test
	void doesNotMatchWhenRequestPathFallsOutsideConfiguredBasePath() {
		FeignAuthProperties properties = new FeignAuthProperties();
		properties.setServices(new LinkedHashMap<>());
		properties.getServices().put("orders", apiKeyService("https://api.service-d.com/v1", "sk-orders", "/api/orders"));

		FeignAuthRequestInterceptor interceptor = interceptor(properties);

		assertThat(interceptor.matchService("https://api.service-d.com", "/other/1")).isNull();
	}

	@Test
	void rejectsUnsupportedAuthTypeEvenWhenFailOnNoMatchDisabled() {
		FeignAuthProperties properties = new FeignAuthProperties();
		properties.getAuth().setFailOnNoMatch(false);
		properties.setServices(new LinkedHashMap<>());
		FeignAuthProperties.Service service = new FeignAuthProperties.Service();
		service.setBaseUrl("https://api.service-d.com");
		FeignAuthProperties.Auth auth = new FeignAuthProperties.Auth();
		auth.setType("apikey");
		service.setAuth(auth);
		properties.getServices().put("typo", service);

		FeignAuthRequestInterceptor interceptor = interceptor(properties);

		assertThatThrownBy(() -> interceptor.matchService("https://api.service-d.com", "/api/anything"))
				.isInstanceOf(FeignAuthConfigurationException.class)
				.hasMessageContaining("auth.type")
				.hasMessageContaining("typo");
	}

	@Test
	void rejectsMatchedServiceWithoutAuthConfig() {
		FeignAuthProperties properties = new FeignAuthProperties();
		properties.setServices(new LinkedHashMap<>());
		FeignAuthProperties.Service service = new FeignAuthProperties.Service();
		service.setBaseUrl("https://api.service-d.com");
		properties.getServices().put("noauth", service);

		FeignAuthRequestInterceptor interceptor = interceptor(properties);

		assertThatThrownBy(() -> interceptor.matchService("https://api.service-d.com", "/api/anything"))
				.isInstanceOf(FeignAuthConfigurationException.class)
				.hasMessageContaining("auth");
	}

	// ── 辅助方法 ──────────────────────────────────────────────────────────

	@Test
	void userDefinedRetryerReplacesDefaultWhenRegisteredFirst() {
		Retryer custom = new Retryer() {
			@Override
			public void continueOrPropagate(RetryableException e) {
				throw e;
			}

			@Override
			public Retryer clone() {
				return this;
			}
		};

		AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext();
		FeignAuthProperties properties = new FeignAuthProperties();
		context.registerBean(FeignAuthProperties.class, () -> properties);
		context.registerBean(ServiceMatcher.class, () -> new ServiceMatcher(properties));
		context.registerBean(HeaderManager.class, () -> new HeaderManager());
		context.registerBean(ObjectMapper.class, () -> new ObjectMapper());
		context.registerBean(Retryer.class, () -> custom);
		context.registerBean(TokenFetcher.class, () -> mock(TokenFetcher.class));
		context.register(FeignClientConfig.class);
		context.refresh();

		try {
			assertThat(context.getBeansOfType(Retryer.class).values()).containsExactly(custom);
		}
		finally {
			context.close();
		}
	}

	@Test
	void defaultRetryerRegisteredWhenUserHasNone() {
		AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext();
		FeignAuthProperties properties = new FeignAuthProperties();
		context.registerBean(FeignAuthProperties.class, () -> properties);
		context.registerBean(ServiceMatcher.class, () -> new ServiceMatcher(properties));
		context.registerBean(HeaderManager.class, () -> new HeaderManager());
		context.registerBean(ObjectMapper.class, () -> new ObjectMapper());
		context.registerBean(TokenFetcher.class, () -> mock(TokenFetcher.class));
		context.register(FeignClientConfig.class);
		context.refresh();

		try {
			assertThat(context.getBeansOfType(Retryer.class).values()).hasSize(1);
			assertThat(context.getBean(Retryer.class)).isInstanceOf(Retryer.Default.class);
		}
		finally {
			context.close();
		}
	}

	// ── 装配 ──────────────────────────────────────────────────────────────

	private static FeignAuthRequestInterceptor interceptor(FeignAuthProperties properties) {
		return new FeignAuthRequestInterceptor(new ServiceMatcher(properties), null, new HeaderManager(),
			properties);
	}

	private static FeignAuthProperties.Service apiKeyService(String baseUrl, String apiKey, String... pathPrefixes) {
		FeignAuthProperties.Service service = new FeignAuthProperties.Service();
		service.setBaseUrl(baseUrl);
		FeignAuthProperties.Auth auth = new FeignAuthProperties.Auth();
		auth.setType("api-key");
		auth.setHeaderName("Authorization");
		auth.setValue(apiKey);
		auth.setPathPrefixes(Arrays.asList(pathPrefixes));
		service.setAuth(auth);
		return service;
	}

}
