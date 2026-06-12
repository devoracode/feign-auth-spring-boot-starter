package io.github.devoracode.feignauth.header;

import feign.RequestTemplate;
import feign.Target;
import io.github.devoracode.feignauth.autoconfigure.FeignAuthProperties;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Tests for {@link HeaderManager} covering both business-request and token-request paths.
 */
class HeaderManagerTest {

	// ── applyRequestHeaders — static ─────────────────────────────────────────

	@Test
	void staticRequestHeadersAreWrittenIntoTemplate() {
		FeignAuthProperties.Service service = serviceWithRequestHeaders(
				headers("X-App-Id", "my-app", "X-Version", "v2"));
		RequestTemplate template = template("https://api.example.com", "/api/data");

		new HeaderManager().applyRequestHeaders("svc", service, "/api/data", template);

		assertThat(firstValue(template, "X-App-Id")).isEqualTo("my-app");
		assertThat(firstValue(template, "X-Version")).isEqualTo("v2");
	}

	@Test
	void blankStaticRequestHeaderNameIsIgnored() {
		Map<String, String> h = new HashMap<>();
		h.put("  ", "value");
		h.put("X-Valid", "ok");
		FeignAuthProperties.Service service = serviceWithRequestHeaders(h);
		RequestTemplate template = template("https://api.example.com", "/api/data");

		new HeaderManager().applyRequestHeaders("svc", service, "/api/data", template);

		assertThat(template.headers().containsKey("  ")).isFalse();
		assertThat(firstValue(template, "X-Valid")).isEqualTo("ok");
	}

	@Test
	void nullStaticRequestHeaderValueIsIgnored() {
		Map<String, String> h = new HashMap<>();
		h.put("X-Null", null);
		h.put("X-Present", "here");
		FeignAuthProperties.Service service = serviceWithRequestHeaders(h);
		RequestTemplate template = template("https://api.example.com", "/api/data");

		new HeaderManager().applyRequestHeaders("svc", service, "/api/data", template);

		assertThat(template.headers().containsKey("X-Null")).isFalse();
		assertThat(firstValue(template, "X-Present")).isEqualTo("here");
	}

	// ── applyRequestHeaders — dynamic (HeaderCustomizer) ─────────────────────

	@Test
	void customizerCalledForRequestWhenSupportsTrue() {
		FeignAuthProperties.Service service = serviceWithRequestHeaders(Collections.emptyMap());
		RequestTemplate template = template("https://api.example.com", "/api/data");

		TrackingCustomizer customizer = new TrackingCustomizer("svc");
		new HeaderManager(Collections.singletonList(customizer))
				.applyRequestHeaders("svc", service, "/api/data", template);

		assertThat(customizer.requestCallCount).isEqualTo(1);
		assertThat(firstValue(template, "X-Tracked-Request")).isEqualTo("yes");
	}

	@Test
	void customizerSkippedForRequestWhenSupportsFalse() {
		FeignAuthProperties.Service service = serviceWithRequestHeaders(Collections.emptyMap());
		RequestTemplate template = template("https://api.example.com", "/api/data");

		TrackingCustomizer customizer = new TrackingCustomizer("other-svc");
		new HeaderManager(Collections.singletonList(customizer))
				.applyRequestHeaders("svc", service, "/api/data", template);

		assertThat(customizer.requestCallCount).isZero();
		assertThat(template.headers().containsKey("X-Tracked-Request")).isFalse();
	}

	@Test
	void customizerReceivesCorrectRequestContext() {
		FeignAuthProperties.Service service = serviceWithRequestHeaders(Collections.emptyMap());
		RequestTemplate template = template("https://api.example.com", "/api/orders/42");

		ContextCapturingCustomizer customizer = new ContextCapturingCustomizer();
		new HeaderManager(Collections.singletonList(customizer))
				.applyRequestHeaders("order-svc", service, "/api/orders/42", template);

		assertThat(customizer.capturedRequestContext).isNotNull();
		assertThat(customizer.capturedRequestContext.getServiceName()).isEqualTo("order-svc");
		assertThat(customizer.capturedRequestContext.getRequestPath()).isEqualTo("/api/orders/42");
		assertThat(customizer.capturedRequestContext.getTemplate()).isSameAs(template);
	}

	@Test
	void dynamicRequestHeaderOverridesStaticWithSameName() {
		FeignAuthProperties.Service service = serviceWithRequestHeaders(headers("X-App-Id", "static"));
		RequestTemplate template = template("https://api.example.com", "/api/data");

		HeaderCustomizer overrider = new HeaderCustomizer() {
			@Override public boolean supports(String n, FeignAuthProperties.Service s) { return true; }
			@Override public void customize(RequestContext ctx, HttpHeaders h) {
				h.set("X-App-Id", "dynamic");
			}
		};
		new HeaderManager(Collections.singletonList(overrider))
				.applyRequestHeaders("svc", service, "/api/data", template);

		assertThat(firstValue(template, "X-App-Id")).isEqualTo("dynamic");
	}

	@Test
	void multipleCustomizersCalledInOrderForRequest() {
		FeignAuthProperties.Service service = serviceWithRequestHeaders(Collections.emptyMap());
		RequestTemplate template = template("https://api.example.com", "/api/data");
		List<String> order = new java.util.ArrayList<>();

		HeaderCustomizer first = new HeaderCustomizer() {
			@Override public boolean supports(String n, FeignAuthProperties.Service s) { return true; }
			@Override public void customize(RequestContext ctx, HttpHeaders h) { order.add("first"); }
		};
		HeaderCustomizer second = new HeaderCustomizer() {
			@Override public boolean supports(String n, FeignAuthProperties.Service s) { return true; }
			@Override public void customize(RequestContext ctx, HttpHeaders h) { order.add("second"); }
		};

		new HeaderManager(Arrays.asList(first, second))
				.applyRequestHeaders("svc", service, "/api/data", template);

		assertThat(order).containsExactly("first", "second");
	}

	@Test
	void customizerExceptionPropagatesForRequest() {
		FeignAuthProperties.Service service = serviceWithRequestHeaders(Collections.emptyMap());
		RequestTemplate template = template("https://api.example.com", "/api/data");

		HeaderCustomizer boom = new HeaderCustomizer() {
			@Override public boolean supports(String n, FeignAuthProperties.Service s) { return true; }
			@Override public void customize(RequestContext ctx, HttpHeaders h) {
				throw new RuntimeException("boom");
			}
		};

		assertThatThrownBy(() ->
				new HeaderManager(Collections.singletonList(boom))
						.applyRequestHeaders("svc", service, "/api/data", template))
				.isInstanceOf(RuntimeException.class).hasMessage("boom");
	}

	// ── buildTokenHeaders — static ────────────────────────────────────────────

	@Test
	void staticTokenRequestHeadersAreReturned() {
		FeignAuthProperties.Service service = serviceWithTokenRequestHeaders(
				headers("X-App-Id", "my-app", "X-Tenant", "acme"));

		HttpHeaders result = new HeaderManager().buildTokenHeaders("svc", service, defaultClient(),
				Collections.emptyMap());

		assertThat(result.getFirst("X-App-Id")).isEqualTo("my-app");
		assertThat(result.getFirst("X-Tenant")).isEqualTo("acme");
	}

	@Test
	void blankStaticTokenHeaderNameIsIgnored() {
		Map<String, String> h = new HashMap<>();
		h.put("  ", "value");
		h.put("X-Valid", "ok");
		FeignAuthProperties.Service service = serviceWithTokenRequestHeaders(h);

		HttpHeaders result = new HeaderManager().buildTokenHeaders("svc", service, defaultClient(),
				Collections.emptyMap());

		assertThat(result.containsKey("  ")).isFalse();
		assertThat(result.getFirst("X-Valid")).isEqualTo("ok");
	}

	// ── buildTokenHeaders — dynamic (HeaderCustomizer) ────────────────────────

	@Test
	void customizerCalledForTokenWhenSupportsTrue() {
		FeignAuthProperties.Service service = serviceWithTokenRequestHeaders(Collections.emptyMap());

		TrackingCustomizer customizer = new TrackingCustomizer("svc");
		HttpHeaders result = new HeaderManager(Collections.singletonList(customizer))
				.buildTokenHeaders("svc", service, defaultClient(), Collections.emptyMap());

		assertThat(customizer.tokenCallCount).isEqualTo(1);
		assertThat(result.getFirst("X-Tracked-Token")).isEqualTo("yes");
	}

	@Test
	void customizerSkippedForTokenWhenSupportsFalse() {
		FeignAuthProperties.Service service = serviceWithTokenRequestHeaders(Collections.emptyMap());

		TrackingCustomizer customizer = new TrackingCustomizer("other-svc");
		HttpHeaders result = new HeaderManager(Collections.singletonList(customizer))
				.buildTokenHeaders("svc", service, defaultClient(), Collections.emptyMap());

		assertThat(customizer.tokenCallCount).isZero();
		assertThat(result.containsKey("X-Tracked-Token")).isFalse();
	}

	@Test
	void customizerReceivesCorrectTokenRequestContext() {
		FeignAuthProperties.Service service = serviceWithTokenRequestHeaders(Collections.emptyMap());
		FeignAuthProperties.Client client = defaultClient();
		Map<String, String> params = headers("client_id", "id", "grant_type", "client_credentials");

		ContextCapturingCustomizer customizer = new ContextCapturingCustomizer();
		new HeaderManager(Collections.singletonList(customizer))
				.buildTokenHeaders("order-svc", service, client, params);

		TokenRequestContext ctx = customizer.capturedTokenContext;
		assertThat(ctx).isNotNull();
		assertThat(ctx.getServiceName()).isEqualTo("order-svc");
		assertThat(ctx.getClient()).isSameAs(client);
		assertThat(ctx.getParameters()).containsEntry("client_id", "id");
		assertThat(ctx.getParameters()).containsEntry("grant_type", "client_credentials");
	}

	@Test
	void tokenRequestContextParametersAreUnmodifiable() {
		FeignAuthProperties.Service service = serviceWithTokenRequestHeaders(Collections.emptyMap());
		Map<String, String> params = new HashMap<>();
		params.put("k", "v");

		ContextCapturingCustomizer customizer = new ContextCapturingCustomizer();
		new HeaderManager(Collections.singletonList(customizer))
				.buildTokenHeaders("svc", service, defaultClient(), params);

		assertThatThrownBy(() -> customizer.capturedTokenContext.getParameters().put("x", "y"))
				.isInstanceOf(UnsupportedOperationException.class);
	}

	@Test
	void dynamicTokenHeaderOverridesStaticWithSameName() {
		FeignAuthProperties.Service service = serviceWithTokenRequestHeaders(
				headers("X-App-Id", "static"));

		HeaderCustomizer overrider = new HeaderCustomizer() {
			@Override public boolean supports(String n, FeignAuthProperties.Service s) { return true; }
			@Override public void customize(TokenRequestContext ctx, HttpHeaders h) {
				h.set("X-App-Id", "dynamic");
			}
		};
		HttpHeaders result = new HeaderManager(Collections.singletonList(overrider))
				.buildTokenHeaders("svc", service, defaultClient(), Collections.emptyMap());

		assertThat(result.getFirst("X-App-Id")).isEqualTo("dynamic");
	}

	@Test
	void customizerExceptionPropagatesForToken() {
		FeignAuthProperties.Service service = serviceWithTokenRequestHeaders(Collections.emptyMap());

		HeaderCustomizer boom = new HeaderCustomizer() {
			@Override public boolean supports(String n, FeignAuthProperties.Service s) { return true; }
			@Override public void customize(TokenRequestContext ctx, HttpHeaders h) {
				throw new RuntimeException("token-boom");
			}
		};

		assertThatThrownBy(() ->
				new HeaderManager(Collections.singletonList(boom))
						.buildTokenHeaders("svc", service, defaultClient(), Collections.emptyMap()))
				.isInstanceOf(RuntimeException.class).hasMessage("token-boom");
	}

	// ── helpers ───────────────────────────────────────────────────────────────

	private static FeignAuthProperties.Service serviceWithRequestHeaders(Map<String, String> requestHeaders) {
		FeignAuthProperties.Service service = new FeignAuthProperties.Service();
		service.setBaseUrl("https://api.example.com");
		FeignAuthProperties.Auth auth = new FeignAuthProperties.Auth();
		auth.setType("api-key");
		auth.setValue("sk-test");
		auth.setRequestHeaders(requestHeaders);
		auth.setTokenRequestHeaders(Collections.emptyMap());
		service.setAuth(auth);
		return service;
	}

	private static FeignAuthProperties.Service serviceWithTokenRequestHeaders(
			Map<String, String> tokenRequestHeaders) {
		FeignAuthProperties.Service service = new FeignAuthProperties.Service();
		service.setBaseUrl("https://api.example.com");
		FeignAuthProperties.Auth auth = new FeignAuthProperties.Auth();
		auth.setType("oauth2");
		auth.setTokenRequestHeaders(tokenRequestHeaders);
		auth.setRequestHeaders(Collections.emptyMap());
		service.setAuth(auth);
		return service;
	}

	private static FeignAuthProperties.Client defaultClient() {
		FeignAuthProperties.Client client = new FeignAuthProperties.Client();
		client.setId("client-id");
		client.setSecret("client-secret");
		return client;
	}

	private static RequestTemplate template(String baseUrl, String path) {
		RequestTemplate t = new RequestTemplate();
		t.feignTarget(new Target.HardCodedTarget<>(Object.class, "test", baseUrl));
		t.uri(path);
		return t;
	}

	private static Map<String, String> headers(String... kv) {
		Map<String, String> map = new LinkedHashMap<>();
		for (int i = 0; i < kv.length - 1; i += 2) map.put(kv[i], kv[i + 1]);
		return map;
	}

	private static String firstValue(RequestTemplate template, String header) {
		java.util.Collection<String> values = template.headers().get(header);
		return (values != null && !values.isEmpty()) ? values.iterator().next() : null;
	}

	// ── stubs ─────────────────────────────────────────────────────────────────

	/** Matches only the given serviceName; writes a fixed header in each customize method. */
	private static final class TrackingCustomizer implements HeaderCustomizer {
		private final String target;
		int requestCallCount;
		int tokenCallCount;

		TrackingCustomizer(String target) { this.target = target; }

		@Override
		public boolean supports(String n, FeignAuthProperties.Service s) { return target.equals(n); }

		@Override
		public void customize(RequestContext ctx, HttpHeaders h) {
			requestCallCount++;
			h.set("X-Tracked-Request", "yes");
		}

		@Override
		public void customize(TokenRequestContext ctx, HttpHeaders h) {
			tokenCallCount++;
			h.set("X-Tracked-Token", "yes");
		}
	}

	/** Captures the context objects passed to each customize method. */
	private static final class ContextCapturingCustomizer implements HeaderCustomizer {
		RequestContext capturedRequestContext;
		TokenRequestContext capturedTokenContext;

		@Override public boolean supports(String n, FeignAuthProperties.Service s) { return true; }

		@Override
		public void customize(RequestContext ctx, HttpHeaders h) {
			this.capturedRequestContext = ctx;
		}

		@Override
		public void customize(TokenRequestContext ctx, HttpHeaders h) {
			this.capturedTokenContext = ctx;
		}
	}

}