package io.github.devoracode.feignauth.feign;

import feign.RequestTemplate;
import feign.Target;
import io.github.devoracode.feignauth.autoconfigure.FeignAuthProperties;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.function.BiConsumer;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for {@link FeignHeaderInjector} contract and its interaction with
 * {@link FeignAuthRequestInterceptor}.
 */
class FeignHeaderInjectorTest {

	// ── supports() filtering ─────────────────────────────────────────────────

	@Test
	void injectorOnlyCalled_whenSupportsReturnsTrue() {
		FeignAuthProperties properties = propertiesWithApiKey("svc-a", "https://api.a.com", "sk-a");
		TrackingInjector yesInjector = new TrackingInjector("svc-a");   // supports svc-a only
		TrackingInjector noInjector = new TrackingInjector("svc-b");    // supports svc-b only

		apply(properties, Arrays.asList(yesInjector, noInjector), "https://api.a.com", "/api/resource");

		assertThat(yesInjector.callCount).isEqualTo(1);
		assertThat(noInjector.callCount).isZero();
	}

	@Test
	void injectorMatchingByBaseUrlIsPossible() {
		FeignAuthProperties properties = propertiesWithApiKey("svc", "https://api.example.com", "sk");

		FeignHeaderInjector baseUrlInjector = new FeignHeaderInjector() {
			@Override
			public boolean supports(String name, FeignAuthProperties.Service service) {
				return "https://api.example.com".equalsIgnoreCase(service.getBaseUrl());
			}
			@Override
			public void inject(String name, String path, BiConsumer<String, String> h) {
				h.accept("X-BaseUrl-Matched", "yes");
			}
		};

		RequestTemplate template = apply(properties, Collections.singletonList(baseUrlInjector),
				"https://api.example.com", "/api/data");

		assertThat(template.headers()).containsKey("X-BaseUrl-Matched");
	}

	@Test
	void allServicesInjector_calledForEveryMatchedService() {
		FeignAuthProperties properties = new FeignAuthProperties();
		properties.setServices(new LinkedHashMap<>());
		properties.getServices().put("svc-a", apiKeyService("https://api.a.com", "sk-a", "/api/a"));
		properties.getServices().put("svc-b", apiKeyService("https://api.b.com", "sk-b", "/api/b"));

		TrackingInjector allInjector = new TrackingInjector(null); // null = supports all

		apply(properties, Collections.singletonList(allInjector), "https://api.a.com", "/api/a/resource");
		apply(properties, Collections.singletonList(allInjector), "https://api.b.com", "/api/b/resource");

		assertThat(allInjector.callCount).isEqualTo(2);
	}

	// ── inject() payload ──────────────────────────────────────────────

	@Test
	void injectReceivesCorrectServiceNameAndPath() {
		FeignAuthProperties properties = propertiesWithApiKey("order-svc", "https://api.order.com", "sk-order");

		TrackingInjector injector = new TrackingInjector("order-svc");
		apply(properties, Collections.singletonList(injector), "https://api.order.com", "/api/orders/42");

		assertThat(injector.lastServiceName).isEqualTo("order-svc");
		assertThat(injector.lastRequestPath).isEqualTo("/api/orders/42");
	}

	@Test
	void injectorCanSetMultipleHeaders() {
		FeignAuthProperties properties = propertiesWithApiKey("svc", "https://api.example.com", "sk");

		FeignHeaderInjector multi = new FeignHeaderInjector() {
			@Override
			public boolean supports(String n, FeignAuthProperties.Service s) { return true; }
			@Override
			public void inject(String n, String p, BiConsumer<String, String> h) {
				h.accept("X-App-Id", "my-app");
				h.accept("X-Request-Id", "req-123");
				h.accept("X-Timestamp", "1700000000000");
			}
		};

		RequestTemplate template = apply(properties, Collections.singletonList(multi),
				"https://api.example.com", "/api/data");

		assertThat(template.headers()).containsKeys("X-App-Id", "X-Request-Id", "X-Timestamp");
		assertThat(firstValue(template, "X-App-Id")).isEqualTo("my-app");
		assertThat(firstValue(template, "X-Request-Id")).isEqualTo("req-123");
	}

	@Test
	void injectorDoesNotOverwriteExistingAuthHeader() {
		FeignAuthProperties properties = propertiesWithApiKey("svc", "https://api.example.com", "sk-original");

		FeignHeaderInjector authOverwriter = new FeignHeaderInjector() {
			@Override
			public boolean supports(String n, FeignAuthProperties.Service s) { return true; }
			@Override
			public void inject(String n, String p, BiConsumer<String, String> h) {
				h.accept("X-Extra", "extra-value");
			}
		};

		RequestTemplate template = apply(properties, Collections.singletonList(authOverwriter),
				"https://api.example.com", "/api/data");

		assertThat(template.headers()).containsKey("Authorization");
		assertThat(template.headers()).containsKey("X-Extra");
	}

	// ── ordering ─────────────────────────────────────────────────────────────

	@Test
	void injectorsAreCalledInListOrder() {
		FeignAuthProperties properties = propertiesWithApiKey("svc", "https://api.example.com", "sk");
		List<String> order = new java.util.ArrayList<>();

		FeignHeaderInjector first = new FeignHeaderInjector() {
			@Override public boolean supports(String n, FeignAuthProperties.Service s) { return true; }
			@Override public void inject(String n, String p, BiConsumer<String, String> h) { order.add("first"); }
		};
		FeignHeaderInjector second = new FeignHeaderInjector() {
			@Override public boolean supports(String n, FeignAuthProperties.Service s) { return true; }
			@Override public void inject(String n, String p, BiConsumer<String, String> h) { order.add("second"); }
		};
		FeignHeaderInjector third = new FeignHeaderInjector() {
			@Override public boolean supports(String n, FeignAuthProperties.Service s) { return true; }
			@Override public void inject(String n, String p, BiConsumer<String, String> h) { order.add("third"); }
		};

		apply(properties, Arrays.asList(first, second, third), "https://api.example.com", "/api/data");

		assertThat(order).containsExactly("first", "second", "third");
	}

	// ── null / empty injector list ────────────────────────────────────────────

	@Test
	void nullInjectorListTreatedAsEmpty() {
		FeignAuthProperties properties = propertiesWithApiKey("svc", "https://api.example.com", "sk");
		FeignAuthRequestInterceptor interceptor = new FeignAuthRequestInterceptor(
				new ServiceMatcher(properties), null, null);

		RequestTemplate template = templateFor("https://api.example.com", "/api/data");
		interceptor.apply(template); // must not throw
		assertThat(template.headers()).containsKey("Authorization");
	}

	// ── helpers ───────────────────────────────────────────────────────────────

	private static RequestTemplate apply(FeignAuthProperties properties,
			List<FeignHeaderInjector> injectors, String baseUrl, String path) {
		FeignAuthRequestInterceptor interceptor = new FeignAuthRequestInterceptor(
				new ServiceMatcher(properties), null, injectors);
		RequestTemplate template = templateFor(baseUrl, path);
		interceptor.apply(template);
		return template;
	}

	private static RequestTemplate templateFor(String baseUrl, String path) {
		RequestTemplate template = new RequestTemplate();
		template.feignTarget(new Target.HardCodedTarget<>(Object.class, "test", baseUrl));
		template.uri(path);
		return template;
	}

	private static FeignAuthProperties propertiesWithApiKey(String serviceName, String baseUrl, String apiKey) {
		FeignAuthProperties properties = new FeignAuthProperties();
		properties.setServices(new LinkedHashMap<>());
		properties.getServices().put(serviceName, apiKeyService(baseUrl, apiKey));
		return properties;
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

	private static String firstValue(RequestTemplate template, String header) {
		java.util.Collection<String> values = template.headers().get(header);
		return (values != null && !values.isEmpty()) ? values.iterator().next() : null;
	}

	/**
	 * Tracks injection calls; pass {@code null} as targetService to support all services.
	 */
	private static final class TrackingInjector implements FeignHeaderInjector {

		private final String targetService;
		int callCount;
		String lastServiceName;
		String lastRequestPath;

		TrackingInjector(String targetService) {
			this.targetService = targetService;
		}

		@Override
		public boolean supports(String serviceName, FeignAuthProperties.Service service) {
			return this.targetService == null || this.targetService.equals(serviceName);
		}

		@Override
		public void inject(String serviceName, String requestPath, BiConsumer<String, String> header) {
			this.callCount++;
			this.lastServiceName = serviceName;
			this.lastRequestPath = requestPath;
			header.accept("X-Tracked", String.valueOf(this.callCount));
		}

	}

}
