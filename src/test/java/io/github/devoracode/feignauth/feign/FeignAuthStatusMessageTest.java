package io.github.devoracode.feignauth.feign;

import ch.qos.logback.classic.Level;
import com.fasterxml.jackson.databind.ObjectMapper;
import feign.FeignException;
import feign.Request;
import feign.RequestTemplate;
import feign.Response;
import feign.RetryableException;
import io.github.devoracode.feignauth.autoconfigure.FeignAuthProperties;
import io.github.devoracode.feignauth.exception.FeignAuthTokenException;
import io.github.devoracode.feignauth.oauth2.TokenFetcher;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 表征测试：固定 {@link FeignAuthStatusHandler} 在各种 HTTP 状态码下产出的异常类型与错误文案。
 * <p>异常文案与错误码属于对外契约，状态码分支改写为表驱动前后必须逐字节一致，
 * 因此先在原实现上跑绿再重构。
 */
class FeignAuthStatusMessageTest {

	private static final String BASE_URL = "https://api.service-b.com";

	@Test
	void everyDocumentedStatusCodeHasItsOwnAdviceForMatchedService() {
		Map<Integer, String> adviceByStatus = new LinkedHashMap<Integer, String>();
		adviceByStatus.put(401, "current credentials are not allowed to access this endpoint");
		adviceByStatus.put(403, "the remote service explicitly rejected this request");
		adviceByStatus.put(404, "the target resource or path does not exist");
		adviceByStatus.put(429, "the remote service rate limit has been exceeded");
		adviceByStatus.put(500, "the remote service is temporarily unavailable");
		adviceByStatus.put(502, "the remote service is temporarily unavailable");
		adviceByStatus.put(503, "the remote service is temporarily unavailable");
		adviceByStatus.put(599, "the remote service is temporarily unavailable");

		for (Map.Entry<Integer, String> entry : adviceByStatus.entrySet()) {
			int status = entry.getKey();
			assertThat(messageOf(handlerWith("orders", apiKeyService("orders", BASE_URL, "/api/orders"))
					.handle("OrderClient#get", response(status, BASE_URL + "/api/orders/1"))))
							.as("status " + status)
							.isEqualTo("FeignAuth: service 'orders' returned " + status + ", " + entry.getValue());
		}
	}

	@Test
	void otherClientErrorsFallBackToGenericRemoteSideAdvice() {
		FeignAuthStatusHandler handler = handlerWith("orders", apiKeyService("orders", BASE_URL, "/api/orders"));

		// 400..499 中未被单独说明的状态码（含 499 这个 5xx 边界前一位）都走通用文案
		for (int status : new int[] { 400, 402, 418, 422, 451, 499 }) {
			assertThat(messageOf(handler.handle("OrderClient#get", response(status, BASE_URL + "/api/orders/1"))))
					.as("status " + status)
					.isEqualTo("FeignAuth: service 'orders' returned " + status + ", request failed on the remote side");
		}
	}

	@Test
	void adviceUsesFeignAuthTokenExceptionWithOriginalFeignCause() {
		Exception exception = handlerWith("orders", apiKeyService("orders", BASE_URL, "/api/orders"))
				.handle("OrderClient#get", response(403, BASE_URL + "/api/orders/1"));

		// 异常类型同样是契约：不能退化成 FeignException，cause 必须保留原始错误状态
		assertThat(exception).isInstanceOf(FeignAuthTokenException.class);
		assertThat(exception.getCause()).isInstanceOf(FeignException.class);
	}

	@Test
	void unmatchedServiceIsReportedAsUnknownName() {
		FeignAuthStatusHandler handler = handlerWith("orders", apiKeyService("orders", BASE_URL, "/api/orders"));

		assertThat(messageOf(handler.handle("OrderClient#get", response(403, "https://other.example.com/api/orders/1"))))
				.isEqualTo("FeignAuth: service 'unknown' returned 403,"
						+ " the remote service explicitly rejected this request");
	}

	@Test
	void statusBelow400IsNotTurnedIntoAnError() {
		FeignAuthStatusHandler handler = handlerWith("orders", apiKeyService("orders", BASE_URL, "/api/orders"));

		// 低于 400 交给 Feign 的默认 ErrorDecoder，本类不得产出异常
		assertThat(handler.handle("OrderClient#get", response(399, BASE_URL + "/api/orders/1"))).isNull();
		assertThat(handler.handle("OrderClient#get", response(200, BASE_URL + "/api/orders/1"))).isNull();
	}

	@Test
	void apiKeyServiceNeverTouchesTokenCacheOn401() {
		TokenFetcher tokenFetcher = mock(TokenFetcher.class);
		Exception exception = handler("orders", apiKeyService("orders", BASE_URL, "/api/orders"), tokenFetcher)
				.handle("OrderClient#get", response(401, BASE_URL + "/api/orders/1"));

		// 只有 oauth2 服务才驱逐令牌；api-key 服务即使是 401 也只报文案
		assertThat(exception).isInstanceOf(FeignAuthTokenException.class);
		verifyNoInteractions(tokenFetcher);
	}

	@Test
	void evictedTokenMessageAnnouncesTheRetry() {
		TokenFetcher tokenFetcher = mock(TokenFetcher.class);
		when(tokenFetcher.invalidateToken(anyString(), anyString())).thenReturn(true);

		Exception exception = handler("measure", oauth2Service("measure", BASE_URL, "/api/measure"), tokenFetcher)
				.handle("MeasureClient#get", response(401, BASE_URL + "/api/measure/read/1"));

		assertThat(exception).isInstanceOf(RetryableException.class);
		assertThat(messageOf(exception)).isEqualTo("FeignAuth: service 'measure' returned 401,"
				+ " OAuth2 token cache evicted, retrying request");
	}

	@Test
	void suppressedEvictionMessageAnnouncesNoRetry() {
		TokenFetcher tokenFetcher = mock(TokenFetcher.class);
		when(tokenFetcher.invalidateToken(anyString(), anyString())).thenReturn(false);

		Exception exception = handler("measure", oauth2Service("measure", BASE_URL, "/api/measure"), tokenFetcher)
				.handle("MeasureClient#get", response(423, BASE_URL + "/api/measure/read/1"));

		assertThat(exception).isInstanceOf(FeignAuthTokenException.class);
		assertThat(exception instanceof RetryableException).isFalse();
		assertThat(messageOf(exception)).isEqualTo("FeignAuth: service 'measure' returned 423,"
				+ " OAuth2 token eviction skipped (cooldown or cache empty), request not retried");
	}

	// ── 远程正文的读取 ──────────────────────────────────────────────────────

	@Test
	void partialBodySurvivesAReadFailureForTheFeignCause() {
		FeignAuthProperties.Service service = oauth2Service("measure", BASE_URL, "/api/measure");
		service.getAuth().setResponseStatusField("code");
		TokenFetcher tokenFetcher = mock(TokenFetcher.class);
		LogCaptures logs = LogCaptures.attach(FeignAuthStatusHandler.class);
		try {
			// 正文读了一半连接就断了：这些字节已经在流上消费掉，不回灌就永远丢了
			Response response = response(500, BASE_URL + "/api/measure/read/1",
					bodyStream("{\"code\":421,\"message\":\"boom\"}", 10));

			Exception exception = handler("measure", service, tokenFetcher).handle("MeasureClient#get", response);

			assertThat(exception).isInstanceOf(FeignAuthTokenException.class);
			assertThat(((FeignException) exception.getCause()).contentUTF8()).isEqualTo("{\"code\":42");
			assertThat(logs.contains(Level.WARN, "Failed to read response body")).isTrue();
			// 截断的正文不足以判定令牌过期，不能据此清缓存重放
			verifyNoInteractions(tokenFetcher);
		}
		finally {
			logs.detach();
		}
	}

	@Test
	void readFailureAfterTheWholePayloadKeepsItForTheCauseWithoutRetrying() {
		FeignAuthProperties.Service service = oauth2Service("measure", BASE_URL, "/api/measure");
		service.getAuth().setResponseStatusField("code");
		TokenFetcher tokenFetcher = mock(TokenFetcher.class);
		Response response = response(500, BASE_URL + "/api/measure/read/1", bodyStream("{\"code\":421}", 12));

		Exception exception = handler("measure", service, tokenFetcher).handle("MeasureClient#get", response);

		// 只有完整读完的正文才参与过期判定：读取既然失败了，这条响应就按普通远程错误处理
		assertThat(exception).isInstanceOf(FeignAuthTokenException.class);
		assertThat(exception instanceof RetryableException).isFalse();
		assertThat(((FeignException) exception.getCause()).contentUTF8()).isEqualTo("{\"code\":421}");
		verifyNoInteractions(tokenFetcher);
	}

	@Test
	void fullyReadBodyStillDrivesExpiredTokenDetection() {
		FeignAuthProperties.Service service = oauth2Service("measure", BASE_URL, "/api/measure");
		service.getAuth().setResponseStatusField("code");
		TokenFetcher tokenFetcher = mock(TokenFetcher.class);
		when(tokenFetcher.invalidateToken(anyString(), anyString())).thenReturn(true);
		Response response = response(200, BASE_URL + "/api/measure/read/1", bodyStream("{\"code\":421}", -1));

		Exception exception = handler("measure", service, tokenFetcher).handle("MeasureClient#get", response);

		assertThat(exception).isInstanceOf(RetryableException.class);
		verify(tokenFetcher).invalidateToken("measure", "/api/measure/read/1");
	}

	// ── 装配与辅助 ────────────────────────────────────────────────────────

	private static FeignAuthStatusHandler handlerWith(String name, FeignAuthProperties.Service service) {
		return handler(name, service, mock(TokenFetcher.class));
	}

	// 异常文案里的服务名取自配置 map 的键，与 Service 对象本身无关
	private static FeignAuthStatusHandler handler(String name, FeignAuthProperties.Service service,
			TokenFetcher tokenFetcher) {
		FeignAuthProperties properties = new FeignAuthProperties();
		properties.setServices(new LinkedHashMap<String, FeignAuthProperties.Service>());
		properties.getServices().put(name, service);
		return new FeignAuthStatusHandler(new ServiceMatcher(properties), tokenFetcher, new ObjectMapper());
	}

	private static String messageOf(Exception exception) {
		assertThat(exception).isNotNull();
		return exception.getMessage();
	}

	private static FeignAuthProperties.Service apiKeyService(String name, String baseUrl, String prefix) {
		FeignAuthProperties.Auth auth = new FeignAuthProperties.Auth();
		auth.setType("api-key");
		auth.setValue("sk-" + name);
		auth.setPathPrefixes(Collections.singletonList(prefix));
		return service(baseUrl, auth);
	}

	private static FeignAuthProperties.Service oauth2Service(String name, String baseUrl, String prefix) {
		FeignAuthProperties.Client client = new FeignAuthProperties.Client();
		client.setId(name);
		client.setSecret("secret-" + name);
		client.setPathPrefixes(Collections.singletonList(prefix));

		FeignAuthProperties.Auth auth = new FeignAuthProperties.Auth();
		auth.setType("oauth2");
		auth.setTokenUrl(baseUrl + "/oauth/token");
		auth.setClients(Arrays.asList(client));
		return service(baseUrl, auth);
	}

	private static FeignAuthProperties.Service service(String baseUrl, FeignAuthProperties.Auth auth) {
		FeignAuthProperties.Service service = new FeignAuthProperties.Service();
		service.setBaseUrl(baseUrl);
		service.setAuth(auth);
		return service;
	}

	private static Response response(int status, String url) {
		Request request = Request.create(Request.HttpMethod.GET, url,
				Collections.<String, Collection<String>>emptyMap(),
				null, StandardCharsets.UTF_8, new RequestTemplate());
		return Response.builder()
				.status(status)
				.reason("test")
				.request(request)
				.headers(Collections.<String, Collection<String>>emptyMap())
				.build();
	}

	private static Response response(int status, String url, InputStream body) {
		Request request = Request.create(Request.HttpMethod.GET, url,
				Collections.<String, Collection<String>>emptyMap(),
				null, StandardCharsets.UTF_8, new RequestTemplate());
		return Response.builder()
				.status(status)
				.reason("test")
				.request(request)
				.headers(Collections.<String, Collection<String>>emptyMap())
				.body(body, null)
				.build();
	}

	// failAt 为负数表示正常读完；否则交付 failAt 个字节后即抛 IOException，模拟连接中途断开
	private static InputStream bodyStream(String content, final int failAt) {
		final byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
		return new InputStream() {

			private int index;

			@Override
			public int read() throws IOException {
				if (failAt >= 0 && this.index == failAt) {
					throw new IOException("connection reset while reading the response body");
				}
				if (this.index >= bytes.length) {
					return -1;
				}
				return bytes[this.index++] & 0xff;
			}

		};
	}

}
