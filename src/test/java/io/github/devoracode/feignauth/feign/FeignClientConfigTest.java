package io.github.devoracode.feignauth.feign;

import feign.RequestTemplate;
import feign.Target;
import io.github.devoracode.feignauth.autoconfigure.FeignAuthProperties;
import io.github.devoracode.feignauth.exception.FeignAuthConfigurationException;
import io.github.devoracode.feignauth.header.HeaderManager;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.LinkedHashMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FeignClientConfigTest {

	// ── service-matcher routing ──────────────────────────────────────────────

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

	// ── helpers ──────────────────────────────────────────────────────────────

	private static FeignAuthRequestInterceptor interceptor(FeignAuthProperties properties) {
		return new FeignAuthRequestInterceptor(new ServiceMatcher(properties), null, new HeaderManager());
	}

	private static RequestTemplate templateFor(String baseUrl, String path) {
		RequestTemplate template = new RequestTemplate();
		template.feignTarget(new Target.HardCodedTarget<>(Object.class, "test", baseUrl));
		template.uri(path);
		return template;
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
