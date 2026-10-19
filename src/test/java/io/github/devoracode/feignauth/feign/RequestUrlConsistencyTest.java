package io.github.devoracode.feignauth.feign;

import com.fasterxml.jackson.databind.ObjectMapper;
import feign.Request;
import feign.RequestTemplate;
import feign.Target;
import io.github.devoracode.feignauth.autoconfigure.FeignAuthProperties;
import io.github.devoracode.feignauth.header.HeaderManager;
import io.github.devoracode.feignauth.oauth2.TokenFetcher;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.Collections;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * 锁住「正向拼接」与「反向反推」两条路径对同一个 URL 的拆解口径。
 *
 * <p>拦截器用 {@code feignTarget().url() + template.path()} 拼出请求路径，错误解码器用 Feign
 * 实际发出的最终 URL 反推路径。两者必须得到完全相同的 {@code (baseUrl, requestPath)}，
 * 否则 4xx 时匹配不到同一个服务，既不驱逐缓存也不重放。
 *
 * <p>这里刻意不配置任何服务并关闭 {@code fail-on-no-match}：断言的是拆解结果本身，
 * 匹配与驱逐行为由 {@code FeignAuthErrorDecoderTest} 覆盖。
 */
class RequestUrlConsistencyTest {

	@Test
	void plainTargetAndMethodPath() {
		assertBothSidesAgree("https://api.example.com", "/api/orders/1",
				"https://api.example.com", "/api/orders/1");
	}

	@Test
	void targetWithTrailingSlash() {
		assertBothSidesAgree("https://api.example.com/", "/api/orders/1",
				"https://api.example.com", "/api/orders/1");
	}

	@Test
	void targetCarriesConfiguredBasePath() {
		// Feign 把 base-url 的路径段拼进最终 URL，两侧都必须把它留在 requestPath 里，
		// 由 ServiceMatcher 按配置剥外层前缀
		assertBothSidesAgree("https://api.example.com/v1", "/api/orders/1",
				"https://api.example.com", "/v1/api/orders/1");
	}

	@Test
	void targetBasePathWithTrailingSlash() {
		assertBothSidesAgree("https://api.example.com/v1/", "/api/orders/1",
				"https://api.example.com", "/v1/api/orders/1");
	}

	@Test
	void methodPathWithRepeatedSlashes() {
		assertBothSidesAgree("https://api.example.com", "/api//orders/1",
				"https://api.example.com", "/api/orders/1");
	}

	@Test
	void methodPathWithTrailingSlash() {
		assertBothSidesAgree("https://api.example.com", "/api/orders/",
				"https://api.example.com", "/api/orders");
	}

	@Test
	void methodPathRoot() {
		assertBothSidesAgree("https://api.example.com", "/",
				"https://api.example.com", "/");
	}

	@Test
	void explicitDefaultPortAndHostCaseAreKeptRawOnBothSides() {
		// 两侧都是原样的 scheme://authority，大小写与显式端口由 ServiceMatcher 的 authority 记忆化负责归一
		assertBothSidesAgree("https://API.Example.COM:443", "/api/orders/1",
				"https://API.Example.COM:443", "/api/orders/1");
	}

	@Test
	void nonDefaultPort() {
		assertBothSidesAgree("http://localhost:8080", "/api/orders/1",
				"http://localhost:8080", "/api/orders/1");
	}

	@Test
	void queryStringIsDroppedOnBothSides() {
		// 最终 URL 带查询串，反向必须与正向一样只留路径段
		assertBothSidesAgree("https://api.example.com", "/api/orders", "?id=1&tag=a%20b",
				"https://api.example.com", "/api/orders");
	}

	private static void assertBothSidesAgree(String targetUrl, String methodPath, String expectedBaseUrl,
			String expectedRequestPath) {
		assertBothSidesAgree(targetUrl, methodPath, null, expectedBaseUrl, expectedRequestPath);
	}

	private static void assertBothSidesAgree(String targetUrl, String methodPath, String query,
			String expectedBaseUrl, String expectedRequestPath) {

		// 严格按 Feign 的时序搭模板：BuildTemplateByResolvingArgs 只挂 feignTarget（无 @Param(url=true) 时
		// 不设 target），拦截器在此刻执行，所以它看到的 path() 就是方法路径本身；之后才由
		// HardCodedTarget.apply 补上 target 并生成最终请求，那才是 ErrorDecoder 侧 response.request().url()
		Target.HardCodedTarget<Object> target =
				new Target.HardCodedTarget<>(Object.class, "order-client", targetUrl);
		RequestTemplate template = new RequestTemplate();
		template.feignTarget(target);
		template.method(Request.HttpMethod.GET);
		template.uri(query == null ? methodPath : methodPath + query);

		RecordingServiceMatcher forwardMatcher = new RecordingServiceMatcher();
		FeignAuthProperties properties = new FeignAuthProperties();
		properties.setFailOnNoMatch(false);
		new FeignAuthRequestInterceptor(forwardMatcher, null, new HeaderManager(), properties).apply(template);

		// 与 HardCodedTarget.apply 同一动作：补 target 后 url() 即 Feign 真正发出的 URL
		template.target(target.url());
		String finalUrl = template.url();

		RecordingServiceMatcher reverseMatcher = new RecordingServiceMatcher();
		FeignAuthStatusHandler handler = new FeignAuthStatusHandler(reverseMatcher, mock(TokenFetcher.class),
				new ObjectMapper());
		handler.handle("OrderClient#get", response(401, finalUrl));

		// 核心不变量：Feign 实际发出的 URL，正反两侧拆出来的结果必须一模一样
		assertThat(reverseMatcher.baseUrl)
				.as("baseUrl of %s must agree with the interceptor side", finalUrl)
				.isEqualTo(forwardMatcher.baseUrl);
		assertThat(reverseMatcher.requestPath)
				.as("requestPath of %s must agree with the interceptor side", finalUrl)
				.isEqualTo(forwardMatcher.requestPath);

		assertThat(forwardMatcher.baseUrl).isEqualTo(expectedBaseUrl);
		assertThat(forwardMatcher.requestPath).isEqualTo(expectedRequestPath);
	}

	private static feign.Response response(int status, String url) {
		Request request = Request.create(Request.HttpMethod.GET, url,
				Collections.<String, Collection<String>>emptyMap(),
				null, StandardCharsets.UTF_8, new RequestTemplate());
		return feign.Response.builder()
				.status(status)
				.reason("test")
				.request(request)
				.headers(Collections.<String, Collection<String>>emptyMap())
				.build();
	}

	/** 只记录传给 {@code match} 的两个参数，空服务表使其恒返回 {@code null}，不影响拆解结果 */
	private static final class RecordingServiceMatcher extends ServiceMatcher {

		private String baseUrl;

		private String requestPath;

		private RecordingServiceMatcher() {
			super(new FeignAuthProperties());
		}

		@Override
		public ResolvedService match(String baseUrl, String requestPath) {
			this.baseUrl = baseUrl;
			this.requestPath = requestPath;
			return super.match(baseUrl, requestPath);
		}

	}

}
