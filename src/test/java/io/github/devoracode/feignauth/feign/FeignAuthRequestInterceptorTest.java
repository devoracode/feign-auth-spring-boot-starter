package io.github.devoracode.feignauth.feign;

import ch.qos.logback.classic.Level;
import feign.RequestTemplate;
import feign.Target;
import io.github.devoracode.feignauth.autoconfigure.FeignAuthProperties;
import io.github.devoracode.feignauth.header.HeaderManager;
import io.github.devoracode.feignauth.oauth2.TokenFetcher;
import org.junit.jupiter.api.Test;

import java.util.Collections;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * {@link FeignAuthRequestInterceptor} 的测试，重点验证 Feign 在重试过程中对同一模板重复应用拦截器时 header 注入的幂等性。
 */
class FeignAuthRequestInterceptorTest {

	@Test
	void apiKeyHeaderKeepsSingleValueAfterRepeatedApply() {
		FeignAuthProperties.Service service = service("api-key", auth -> {
			auth.setHeaderName("Authorization");
			auth.setTokenPrefix("Bearer ");
			auth.setValue("sk-new");
		});

		RequestTemplate template = template("https://api.example.com", "/api/orders/1");
		FeignAuthRequestInterceptor interceptor = interceptorWith(service, null);

		interceptor.apply(template);
		// 模拟重试重新应用拦截器之前发生了 token 轮换
		service.getAuth().setValue("sk-refreshed");
		interceptor.apply(template);

		assertThat(template.headers().get("Authorization")).containsExactly("Bearer sk-refreshed");
	}

	@Test
	void oauth2TokenHeaderKeepsSingleValueAfterRepeatedApply() {
		FeignAuthProperties.Service service = service("oauth2", auth -> {
			auth.setTokenHeader("x-token");
			auth.setClients(Collections.singletonList(new FeignAuthProperties.Client()));
		});
		TokenFetcher tokenFetcher = mock(TokenFetcher.class);
		when(tokenFetcher.getToken(anyString(), anyString())).thenReturn("old-token", "new-token");

		FeignAuthRequestInterceptor interceptor = interceptorWith(service, tokenFetcher);
		RequestTemplate template = template("https://api.example.com", "/api/data");

		interceptor.apply(template);
		interceptor.apply(template);

		assertThat(template.headers().get("x-token")).containsExactly("new-token");
	}

	@Test
	void customStaticHeadersKeepSingleValueAfterRepeatedApply() {
		FeignAuthProperties.Service service = service("api-key", auth -> {
			auth.setValue("sk-test");
			auth.setRequestHeaders(Collections.singletonMap("X-App-Id", "my-app"));
		});

		RequestTemplate template = template("https://api.example.com", "/api/data");
		FeignAuthRequestInterceptor interceptor = interceptorWith(service, null);

		interceptor.apply(template);
		interceptor.apply(template);

		assertThat(template.headers().get("X-App-Id")).containsExactly("my-app");
	}

	@Test
	void throwsByDefaultWhenServiceNotFound() {
		FeignAuthProperties properties = new FeignAuthProperties();
		FeignAuthRequestInterceptor interceptor = new FeignAuthRequestInterceptor(
			new ServiceMatcher(properties), null, new HeaderManager(), properties);

		RequestTemplate template = template("https://unknown.example.com", "/api/data");

		org.junit.jupiter.api.Assertions.assertThrows(
			io.github.devoracode.feignauth.exception.FeignAuthConfigurationException.class,
			() -> interceptor.apply(template));
	}

	@Test
	void warnsButProceedsWhenFailOnNoMatchDisabledAndServiceNotFound() {
		FeignAuthProperties properties = new FeignAuthProperties();
		properties.getAuth().setFailOnNoMatch(false);
		FeignAuthRequestInterceptor interceptor = new FeignAuthRequestInterceptor(
			new ServiceMatcher(properties), null, new HeaderManager(), properties);

		RequestTemplate template = template("https://unknown.example.com", "/api/data");

		interceptor.apply(template);
		assertThat(template.headers()).isEmpty();
	}

	@Test
	void nullGlobalAuthStillFailsWithConfigExceptionInsteadOfNpe() {
		FeignAuthProperties properties = new FeignAuthProperties();
		// 自定义 @Bean 场景可能把全局 auth 显式置空，此处必须仍是可读的配置异常
		properties.setAuth(null);
		FeignAuthRequestInterceptor interceptor = new FeignAuthRequestInterceptor(
			new ServiceMatcher(properties), null, new HeaderManager(), properties);

		RequestTemplate template = template("https://unknown.example.com", "/api/data");

		assertThat(org.junit.jupiter.api.Assertions.assertThrows(
			io.github.devoracode.feignauth.exception.FeignAuthConfigurationException.class,
			() -> interceptor.apply(template)).getMessage()).contains("fail-on-no-match");
	}

	@Test
	void apiKeyHeaderAppliedWhenTargetUrlCarriesConfiguredBasePath() {
		FeignAuthProperties.Service service = service("api-key", auth -> {
			auth.setHeaderName("Authorization");
			auth.setValue("sk-test");
			auth.setPathPrefixes(Collections.singletonList("/api/orders"));
		});
		service.setBaseUrl("https://api.example.com/v1");

		RequestTemplate template = template("https://api.example.com/v1", "/api/orders/1");
		FeignAuthRequestInterceptor interceptor = interceptorWith(service, null);

		interceptor.apply(template);

		assertThat(template.headers().get("Authorization")).containsExactly("sk-test");
	}

	@Test
	void noMatchFailureMessageListsConfiguredServices() {
		FeignAuthProperties.Service service = service("api-key", auth -> auth.setValue("sk-test"));
		FeignAuthProperties properties = new FeignAuthProperties();
		properties.getServices().put("order-api", service);
		FeignAuthRequestInterceptor interceptor = new FeignAuthRequestInterceptor(new ServiceMatcher(properties),
			null, new HeaderManager(), properties);

		RequestTemplate template = template("https://unknown.example.com", "/api/data");

		assertThat(org.junit.jupiter.api.Assertions.assertThrows(
			io.github.devoracode.feignauth.exception.FeignAuthConfigurationException.class,
			() -> interceptor.apply(template)).getMessage())
				.contains("order-api")
				.contains("https://api.example.com")
				.contains("fail-on-no-match");
	}

	@Test
	void throwsOnUnsupportedAuthTypeEvenWhenFailOnNoMatchDisabled() {
		FeignAuthProperties.Service service = service("oauth", auth -> auth.setValue("ignored"));
		FeignAuthProperties properties = new FeignAuthProperties();
		properties.getAuth().setFailOnNoMatch(false);
		properties.getServices().put("order", service);
		FeignAuthRequestInterceptor interceptor = new FeignAuthRequestInterceptor(new ServiceMatcher(properties),
			null, new HeaderManager(), properties);

		RequestTemplate template = template("https://api.example.com", "/api/data");

		assertThat(org.junit.jupiter.api.Assertions.assertThrows(
			io.github.devoracode.feignauth.exception.FeignAuthConfigurationException.class,
			() -> interceptor.apply(template)).getMessage()).contains("auth.type");
	}

	@Test
	void apiKeyHeaderAppliedWhenMethodPathCarriesRepeatedSlashes() {
		FeignAuthProperties.Service service = service("api-key", auth -> {
			auth.setHeaderName("Authorization");
			auth.setValue("sk-test");
			auth.setPathPrefixes(Collections.singletonList("/api/orders"));
		});

		RequestTemplate template = template("https://api.example.com", "/api//orders/1");
		FeignAuthRequestInterceptor interceptor = interceptorWith(service, null);

		interceptor.apply(template);

		// 与 FeignAuthStatusHandler 侧同一口径：不折叠重复斜杠这里就匹配不到 /api/orders，请求会裸发
		assertThat(template.headers().get("Authorization")).containsExactly("sk-test");
	}

	@Test
	void apiKeyHeaderAppliedWhenBaseUrlCarriesRepeatedSlashes() {
		FeignAuthProperties.Service service = service("api-key", auth -> {
			auth.setHeaderName("Authorization");
			auth.setValue("sk-test");
			auth.setPathPrefixes(Collections.singletonList("/orders"));
		});
		// base-url 中间带 // 时，配置侧路径也要折叠后才剥得掉外层前缀，
		// 否则匹配不到服务，会按 fail-on-no-match 直接抛配置异常
		service.setBaseUrl("https://api.example.com/api//v1");

		RequestTemplate template = template("https://api.example.com/api//v1", "/orders/1");
		FeignAuthRequestInterceptor interceptor = interceptorWith(service, null);

		interceptor.apply(template);

		assertThat(template.headers().get("Authorization")).containsExactly("sk-test");
	}

	@Test
	void noMatchWarningIsLoggedOncePerBaseUrlAndLaterOccurrenceDropsToDebug() {
		FeignAuthProperties properties = new FeignAuthProperties();
		properties.getAuth().setFailOnNoMatch(false);
		FeignAuthRequestInterceptor interceptor = new FeignAuthRequestInterceptor(
			new ServiceMatcher(properties), null, new HeaderManager(), properties);

		LogCaptures warnings = LogCaptures.attach(FeignAuthRequestInterceptor.class);
		try {
			interceptor.apply(template("https://unknown.example.com", "/api/data"));
			interceptor.apply(template("https://unknown.example.com", "/api/other"));
			interceptor.apply(template("https://another.example.com", "/api/data"));
		} finally {
			warnings.detach();
		}

		// 未匹配请求每个都会走到这里，同一 baseUrl 只提醒一次；换个 baseUrl 仍是新的一次提醒
		assertThat(warnings.count(Level.WARN)).isEqualTo(2);
		assertThat(warnings.count(Level.DEBUG)).isEqualTo(1);
	}
		
	private static FeignAuthRequestInterceptor interceptorWith(FeignAuthProperties.Service service,
			TokenFetcher tokenFetcher) {
		FeignAuthProperties properties = new FeignAuthProperties();
		properties.getServices().put("order", service);
		return new FeignAuthRequestInterceptor(new ServiceMatcher(properties), tokenFetcher, new HeaderManager(),
			properties);
	}

	private static FeignAuthProperties.Service service(String type,
			java.util.function.Consumer<FeignAuthProperties.Auth> customizer) {
		FeignAuthProperties.Service service = new FeignAuthProperties.Service();
		service.setBaseUrl("https://api.example.com");
		FeignAuthProperties.Auth auth = new FeignAuthProperties.Auth();
		auth.setType(type);
		customizer.accept(auth);
		service.setAuth(auth);
		return service;
	}

	private static RequestTemplate template(String baseUrl, String path) {
		RequestTemplate template = new RequestTemplate();
		template.feignTarget(new Target.HardCodedTarget<>(Object.class, "order-client", baseUrl));
		template.uri(path);
		return template;
	}

}
