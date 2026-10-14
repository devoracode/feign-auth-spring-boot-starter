package io.github.devoracode.feignauth.feign;

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
 * Tests for {@link FeignAuthRequestInterceptor}, focusing on header injection idempotency
 * when Feign re-applies interceptors on the same template during a retry.
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
		// simulate a token rotation before the retry re-applies the interceptor
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
