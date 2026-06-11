package io.github.devoracode.feignauth.feign;

import feign.RequestTemplate;
import feign.Target;
import io.github.devoracode.feignauth.autoconfigure.FeignAuthProperties;
import io.github.devoracode.feignauth.exception.FeignAuthConfigurationException;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
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

	// ── FeignHeaderInjector integration ─────────────────────────────────────

	@Test
	void headerInjectorsAreCalledAfterAuthHeaderForMatchedService() {
		FeignAuthProperties properties = new FeignAuthProperties();
		properties.setServices(new LinkedHashMap<>());
		properties.getServices().put("orders", apiKeyService("https://api.example.com", "sk-key", "/api/orders"));

		RecordingInjector injector = new RecordingInjector(true);
		FeignAuthRequestInterceptor interceptor = interceptorWithInjectors(properties,
				Collections.singletonList(injector));

		RequestTemplate template = templateFor("https://api.example.com", "/api/orders/1");
		interceptor.apply(template);

		assertThat(injector.invokedForService).isEqualTo("orders");
		assertThat(injector.invokedForPath).isEqualTo("/api/orders/1");
		assertThat(template.headers()).containsKey("X-Custom");
		// auth header must also be present
		assertThat(template.headers()).containsKey("Authorization");
	}

	@Test
	void headerInjectorIsSkippedWhenSupportsReturnsFalse() {
		FeignAuthProperties properties = new FeignAuthProperties();
		properties.setServices(new LinkedHashMap<>());
		properties.getServices().put("orders", apiKeyService("https://api.example.com", "sk-key", "/api/orders"));

		RecordingInjector injector = new RecordingInjector(false);
		FeignAuthRequestInterceptor interceptor = interceptorWithInjectors(properties,
				Collections.singletonList(injector));

		RequestTemplate template = templateFor("https://api.example.com", "/api/orders/1");
		interceptor.apply(template);

		assertThat(injector.invokedForService).isNull();
		assertThat(template.headers()).doesNotContainKey("X-Custom");
	}

	@Test
	void multipleHeaderInjectorsAreAllCalledInOrder() {
		FeignAuthProperties properties = new FeignAuthProperties();
		properties.setServices(new LinkedHashMap<>());
		properties.getServices().put("svc", apiKeyService("https://api.example.com", "sk-key"));

		java.util.List<String> callOrder = new java.util.ArrayList<>();
		FeignHeaderInjector first = new FeignHeaderInjector() {
			@Override
			public boolean supports(String n, FeignAuthProperties.Service s) { return true; }
			@Override
			public void inject(String n, String p, RequestTemplate t) {
				callOrder.add("first");
				t.header("X-First", "1");
			}
		};
		FeignHeaderInjector second = new FeignHeaderInjector() {
			@Override
			public boolean supports(String n, FeignAuthProperties.Service s) { return true; }
			@Override
			public void inject(String n, String p, RequestTemplate t) {
				callOrder.add("second");
				t.header("X-Second", "2");
			}
		};

		FeignAuthRequestInterceptor interceptor = interceptorWithInjectors(properties, Arrays.asList(first, second));
		RequestTemplate template = templateFor("https://api.example.com", "/api/anything");
		interceptor.apply(template);

		assertThat(callOrder).containsExactly("first", "second");
		assertThat(template.headers()).containsKeys("X-First", "X-Second");
	}

	@Test
	void headerInjectorNotCalledWhenNoServiceMatches() {
		FeignAuthProperties properties = new FeignAuthProperties();
		properties.setServices(new LinkedHashMap<>());
		// service registered under a different base-url
		properties.getServices().put("other", apiKeyService("https://other.example.com", "sk-key"));

		RecordingInjector injector = new RecordingInjector(true);
		FeignAuthRequestInterceptor interceptor = interceptorWithInjectors(properties,
				Collections.singletonList(injector));

		RequestTemplate template = templateFor("https://api.example.com", "/api/orders/1");
		interceptor.apply(template);

		// no match → injector must not be called
		assertThat(injector.invokedForService).isNull();
	}

	@Test
	void interceptorWorksNormallyWithEmptyInjectorList() {
		FeignAuthProperties properties = new FeignAuthProperties();
		properties.setServices(new LinkedHashMap<>());
		properties.getServices().put("orders", apiKeyService("https://api.example.com", "sk-key", "/api/orders"));

		FeignAuthRequestInterceptor interceptor = interceptorWithInjectors(properties, Collections.emptyList());
		RequestTemplate template = templateFor("https://api.example.com", "/api/orders/1");
		interceptor.apply(template);

		assertThat(template.headers()).containsKey("Authorization");
	}

	@Test
	void headerInjectorExceptionPropagates() {
		FeignAuthProperties properties = new FeignAuthProperties();
		properties.setServices(new LinkedHashMap<>());
		properties.getServices().put("svc", apiKeyService("https://api.example.com", "sk-key"));

		FeignHeaderInjector boom = new FeignHeaderInjector() {
			@Override
			public boolean supports(String n, FeignAuthProperties.Service s) { return true; }
			@Override
			public void inject(String n, String p, RequestTemplate t) {
				throw new RuntimeException("injector exploded");
			}
		};

		FeignAuthRequestInterceptor interceptor = interceptorWithInjectors(properties,
				Collections.singletonList(boom));
		RequestTemplate template = templateFor("https://api.example.com", "/api/anything");

		assertThatThrownBy(() -> interceptor.apply(template))
				.isInstanceOf(RuntimeException.class)
				.hasMessage("injector exploded");
	}

	// ── helpers ──────────────────────────────────────────────────────────────

	private static FeignAuthRequestInterceptor interceptor(FeignAuthProperties properties) {
		return new FeignAuthRequestInterceptor(new ServiceMatcher(properties), null);
	}

	private static FeignAuthRequestInterceptor interceptorWithInjectors(FeignAuthProperties properties,
	                                                                    java.util.List<FeignHeaderInjector> injectors) {
		return new FeignAuthRequestInterceptor(new ServiceMatcher(properties), null, injectors);
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

	/** Records the last injection call; optionally returns false from supports(). */
	private static final class RecordingInjector implements FeignHeaderInjector {

		private final boolean supported;
		String invokedForService;
		String invokedForPath;

		RecordingInjector(boolean supported) {
			this.supported = supported;
		}

		@Override
		public boolean supports(String serviceName, FeignAuthProperties.Service service) {
			return this.supported;
		}

		@Override
		public void inject(String serviceName, String requestPath, RequestTemplate template) {
			this.invokedForService = serviceName;
			this.invokedForPath = requestPath;
			template.header("X-Custom", "injected");
		}

	}

}