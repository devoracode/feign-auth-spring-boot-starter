package io.github.devoracode.feignauth.oauth2;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import io.github.devoracode.feignauth.autoconfigure.FeignAuthProperties;
import io.github.devoracode.feignauth.exception.FeignAuthConfigurationException;
import io.github.devoracode.feignauth.exception.FeignAuthTokenException;
import io.github.devoracode.feignauth.header.HeaderManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OAuth2TokenRequestClientTest {

	private HttpServer server;

	private String tokenUrl;

	@BeforeEach
	void startServer() throws IOException {
		this.server = HttpServer.create(new InetSocketAddress(0), 0);
		this.server.start();
		this.tokenUrl = "http://127.0.0.1:" + this.server.getAddress().getPort() + "/token";
	}

	@AfterEach
	void stopServer() {
		this.server.stop(0);
	}

	@Test
	void socketTimeoutIsTakenFromProperties() {
		this.server.createContext("/token", exchange -> {
			sleep(1000L);
			respond(exchange, 200, "{\"access_token\":\"t\",\"expires_in\":600}");
		});

		FeignAuthProperties.Http http = new FeignAuthProperties.Http();
		http.setSocketTimeout(Duration.ofMillis(200));
		OAuth2TokenRequestClient client = createClient(http);

		assertThatThrownBy(() -> client.requestToken("svc", service(this.tokenUrl), clientCredentials()))
				.isInstanceOf(FeignAuthTokenException.class)
				.hasStackTraceContaining("Read timed out");
	}

	@Test
	void closeShutsDownConnectionPool() throws IOException {
		this.server.createContext("/token",
				exchange -> respond(exchange, 200, "{\"access_token\":\"t\",\"expires_in\":600}"));

		OAuth2TokenRequestClient client = createClient(new FeignAuthProperties.Http());
		OAuth2AccessToken token = client.requestToken("svc", service(this.tokenUrl), clientCredentials());
		assertThat(token.getAccessToken()).isEqualTo("t");

		client.close();

		assertThatThrownBy(() -> client.requestToken("svc", service(this.tokenUrl), clientCredentials()))
				.hasStackTraceContaining("shut down");
	}

	@Test
	void formUrlEncodedContentTypeSwitchesRequestBodyToFormEncoding() {
		AtomicReference<String> receivedContentType = new AtomicReference<>();
		AtomicReference<String> receivedBody = new AtomicReference<>();
		this.server.createContext("/token", exchange -> {
			receivedContentType.set(exchange.getRequestHeaders().getFirst("Content-Type"));
			receivedBody.set(readRequestBody(exchange));
			respond(exchange, 200, "{\"access_token\":\"t\",\"expires_in\":600}");
		});

		FeignAuthProperties.Service service = service(this.tokenUrl);
		service.getAuth().getTokenRequestHeaders().put("Content-Type", "application/x-www-form-urlencoded");
		OAuth2TokenRequestClient client = createClient(new FeignAuthProperties.Http());

		OAuth2AccessToken token = client.requestToken("svc", service, clientCredentials());
		assertThat(token.getAccessToken()).isEqualTo("t");
		assertThat(receivedContentType.get()).startsWith("application/x-www-form-urlencoded");
		assertThat(parseFormBody(receivedBody.get())).containsEntry("client_id", "id")
				.containsEntry("client_secret", "secret")
				.containsEntry("grant_type", "client_credentials");
	}

	@Test
	void unknownTokenRequestContentTypeFailsFast() {
		FeignAuthProperties.Service service = service(this.tokenUrl);
		service.getAuth().getTokenRequestHeaders().put("Content-Type", "text/plain");
		OAuth2TokenRequestClient client = createClient(new FeignAuthProperties.Http());

		assertThatThrownBy(() -> client.requestToken("svc", service, clientCredentials()))
				.isInstanceOf(FeignAuthConfigurationException.class)
				.hasStackTraceContaining("Content-Type");
	}

	@Test
	void malformedTokenRequestContentTypeFailsFastAsConfigurationException() {
		// 这些值会被 BasicHeaderValueParser 拆成空元素，ContentType.parse 抛 ParseException
		// （RuntimeException，不是 IllegalArgumentException），旧代码的 catch 兜不住
		for (String configured : new String[] { ";", ",", ";;", " , " }) {
			FeignAuthProperties.Service service = service(this.tokenUrl);
			service.getAuth().getTokenRequestHeaders().put("Content-Type", configured);
			OAuth2TokenRequestClient client = createClient(new FeignAuthProperties.Http());

			assertThatThrownBy(() -> client.requestToken("svc", service, clientCredentials()))
					.isInstanceOf(FeignAuthConfigurationException.class)
					.hasMessageContaining("Invalid Content-Type");
		}
	}

	@Test
	void responseDecodedWithDeclaredCharset() {
		this.server.createContext("/token", exchange -> {
			try {
				byte[] bytes = "{\"access_token\":\"令牌-α\",\"expires_in\":600}".getBytes(Charset.forName("GBK"));
				exchange.getResponseHeaders().add("Content-Type", "application/json;charset=GBK");
				exchange.sendResponseHeaders(200, bytes.length);
				try (OutputStream os = exchange.getResponseBody()) {
					os.write(bytes);
				}
			}
			catch (IOException ex) {
				// ignore
			}
		});

		OAuth2TokenRequestClient client = createClient(new FeignAuthProperties.Http());
		OAuth2AccessToken token = client.requestToken("svc", service(this.tokenUrl), clientCredentials());
		assertThat(token.getAccessToken()).isEqualTo("令牌-α");
	}

	@Test
	void responseWithoutDeclaredCharsetFallsBackToUtf8() {
		this.server.createContext("/token",
				exchange -> respond(exchange, 200, "{\"access_token\":\"令牌-α\",\"expires_in\":600}"));

		OAuth2TokenRequestClient client = createClient(new FeignAuthProperties.Http());
		OAuth2AccessToken token = client.requestToken("svc", service(this.tokenUrl), clientCredentials());
		assertThat(token.getAccessToken()).isEqualTo("令牌-α");
	}

	@Test
	void unknownCharsetInResponseContentTypeFallsBackToUtf8() {
		this.server.createContext("/token", exchange -> {
			try {
				byte[] bytes = "{\"access_token\":\"令牌-α\",\"expires_in\":600}".getBytes(StandardCharsets.UTF_8);
				// UTF-9 在本 JVM 不存在，等价于 IdP/网关声明了无法解析的字符集
				exchange.getResponseHeaders().add("Content-Type", "application/json;charset=UTF-9");
				exchange.sendResponseHeaders(200, bytes.length);
				try (OutputStream os = exchange.getResponseBody()) {
					os.write(bytes);
				}
			}
			catch (IOException ex) {
				// ignore
			}
		});

		OAuth2TokenRequestClient client = createClient(new FeignAuthProperties.Http());
		OAuth2AccessToken token = client.requestToken("svc", service(this.tokenUrl), clientCredentials());
		assertThat(token.getAccessToken()).isEqualTo("令牌-α");
	}

	@Test
	void non2xxResponseBodyReachesExceptionFromClientSide() {
		this.server.createContext("/token",
				exchange -> respond(exchange, 400,
						"{\"error\":\"unauthorized_client\",\"error_description\":\"grant_type not allowed\"}"));

		OAuth2TokenRequestClient client = createClient(new FeignAuthProperties.Http());

		assertThatThrownBy(() -> client.requestToken("svc", service(this.tokenUrl), clientCredentials()))
				.isInstanceOf(FeignAuthTokenException.class)
				.hasMessageContaining("400")
				.hasMessageContaining("unauthorized_client")
				.hasMessageContaining("grant_type not allowed");
	}

	@Test
	void getTokenRequestIsNotRetriedAutomaticallyAfterIoFailure() {
		AtomicInteger hits = new AtomicInteger();
		this.server.createContext("/token", exchange -> {
			if (hits.incrementAndGet() == 1) {
				// 不写响应直接断开，令 HttpClient 把幂等 GET 判定为可自动重试
				exchange.close();
				return;
			}
			respond(exchange, 200, "{\"access_token\":\"t\",\"expires_in\":600}");
		});

		FeignAuthProperties.Service service = service(this.tokenUrl);
		service.getAuth().setMethod("get");
		OAuth2TokenRequestClient client = createClient(new FeignAuthProperties.Http());

		assertThatThrownBy(() -> client.requestToken("svc", service, clientCredentials()))
				.isInstanceOf(FeignAuthTokenException.class);
		assertThat(hits.get()).isEqualTo(1);
	}

	@Test
	void getTokenRequestDoesNotFollowRedirect() {
		// 跟随 302 会把带 client 凭证的请求重放到 Location 指向的地址
		AtomicInteger redirectHits = new AtomicInteger();
		this.server.createContext("/token", exchange -> {
			exchange.getResponseHeaders().add("Location",
					"http://127.0.0.1:" + this.server.getAddress().getPort() + "/elsewhere");
			respond(exchange, 302, "");
		});
		this.server.createContext("/elsewhere", exchange -> {
			redirectHits.incrementAndGet();
			respond(exchange, 200, "{\"access_token\":\"t\",\"expires_in\":600}");
		});

		FeignAuthProperties.Service service = service(this.tokenUrl);
		service.getAuth().setMethod("get");
		OAuth2TokenRequestClient client = createClient(new FeignAuthProperties.Http());

		assertThatThrownBy(() -> client.requestToken("svc", service, clientCredentials()))
				.isInstanceOf(FeignAuthTokenException.class)
				.hasMessageContaining("302");
		assertThat(redirectHits.get()).isEqualTo(0);
	}

	@Test
	void injectedObjectMapperIsUsedForRequestBody() {
		AtomicReference<String> receivedBody = new AtomicReference<>();
		this.server.createContext("/token", exchange -> {
			receivedBody.set(readRequestBody(exchange));
			respond(exchange, 200, "{\"access_token\":\"t\",\"expires_in\":600}");
		});

		ObjectMapper wrappingMapper = new ObjectMapper();
		wrappingMapper.enable(com.fasterxml.jackson.databind.SerializationFeature.WRAP_ROOT_VALUE);
		OAuth2TokenRequestClient client = new OAuth2TokenRequestClient(
				new OAuth2TokenResponseParser(new ObjectMapper()), new HeaderManager(), wrappingMapper,
				new FeignAuthProperties.Http());

		client.requestToken("svc", service(this.tokenUrl), clientCredentials());
		assertThat(receivedBody.get()).startsWith("{\"HashMap\":");
	}

	@Test
	void oversizedSuccessResponseIsRejectedAtTheLimit() {
		this.server.createContext("/token", exchange -> respond(exchange, 200,
				"{\"access_token\":\"" + repeat('a', 2 * 1024 * 1024) + "\",\"expires_in\":600}"));

		OAuth2TokenRequestClient client = createClient(new FeignAuthProperties.Http());

		assertThatThrownBy(() -> client.requestToken("svc", service(this.tokenUrl), clientCredentials()))
				.isInstanceOf(FeignAuthTokenException.class)
				.hasMessageContaining("65536");
	}

	@Test
	void oversizedResponseBodyIsTruncatedBeforeParsing() {
		this.server.createContext("/token", exchange -> respond(exchange, 502,
				"<html>gateway-failure" + repeat('x', 1024 * 1024) + "TAIL-MARKER</html>"));

		AtomicReference<String> captured = new AtomicReference<>();
		OAuth2TokenResponseParser capturing = new OAuth2TokenResponseParser(new ObjectMapper()) {
			@Override
			public OAuth2AccessToken parse(int statusCode, String body, long expireAheadSeconds, String tokenField,
					Long configuredExpiresInSeconds) {
				captured.set(body);
				return super.parse(statusCode, body, expireAheadSeconds, tokenField, configuredExpiresInSeconds);
			}
		};
		OAuth2TokenRequestClient client = new OAuth2TokenRequestClient(capturing, new HeaderManager(),
				new ObjectMapper(), new FeignAuthProperties.Http());

		assertThatThrownBy(() -> client.requestToken("svc", service(this.tokenUrl), clientCredentials()))
				.isInstanceOf(FeignAuthTokenException.class);
		assertThat(captured.get().length()).isLessThanOrEqualTo(65536);
		assertThat(captured.get()).contains("gateway-failure").doesNotContain("TAIL-MARKER");
	}

	@Test
	void nullContentStreamIsReadAsEmptyBodyInsteadOfThrowing() throws IOException {
		// 此前交给 EntityUtils.toString 时，内容流为 null 会直接得到 null 正文；自己读流后必须保持
		// 同样的容忍，否则 NPE 会被锁包装器改写成「Error executing with lock…」，掩盖真实原因
		OAuth2TokenRequestClient.ResponseBody body = OAuth2TokenRequestClient.readBody(null, StandardCharsets.UTF_8);

		assertThat(body.getText()).isNull();
		assertThat(body.isTruncated()).isFalse();
	}

	@Test
	void hugeTimeoutIsTruncatedToIntMaxInsteadOfOverflowing() throws IOException {
		// 超过 int 上限的时长按既有约定截断为 Integer.MAX_VALUE；修复前 toMillis() 的 multiplyExact
		// 会先抛 ArithmeticException，绕过点名配置键的异常口径
		FeignAuthProperties.Http http = new FeignAuthProperties.Http();
		http.setSocketTimeout(Duration.ofSeconds(Long.MAX_VALUE / 2));

		OAuth2TokenRequestClient client = createClient(http);
		client.close();
	}

	private static String repeat(char value, int count) {
		char[] chars = new char[count];
		Arrays.fill(chars, value);
		return new String(chars);
	}

	@Test
	void zeroSocketTimeoutIsRejectedInsteadOfWaitingForever() {
		// RequestConfig 语义：0 = 无限等待
		FeignAuthProperties.Http http = new FeignAuthProperties.Http();
		http.setSocketTimeout(Duration.ZERO);

		assertThatThrownBy(() -> createClient(http)).isInstanceOf(FeignAuthConfigurationException.class)
				.hasMessageContaining("feign.auth.http.socket-timeout");
	}

	@Test
	void negativeTimeoutIsRejectedInsteadOfClampedToZero() {
		FeignAuthProperties.Http http = new FeignAuthProperties.Http();
		http.setConnectTimeout(Duration.ofSeconds(-1));

		assertThatThrownBy(() -> createClient(http)).isInstanceOf(FeignAuthConfigurationException.class)
				.hasMessageContaining("feign.auth.http.connect-timeout");
	}

	@Test
	void subMillisecondTimeoutIsRejectedBecauseItRoundsToZero() {
		FeignAuthProperties.Http http = new FeignAuthProperties.Http();
		http.setConnectionRequestTimeout(Duration.ofNanos(500_000));

		assertThatThrownBy(() -> createClient(http)).isInstanceOf(FeignAuthConfigurationException.class)
				.hasMessageContaining("feign.auth.http.connection-request-timeout");
	}

	@Test
	void nullTimeoutIsRejectedWithItsKey() {
		FeignAuthProperties.Http http = new FeignAuthProperties.Http();
		http.setSocketTimeout(null);

		assertThatThrownBy(() -> createClient(http)).isInstanceOf(FeignAuthConfigurationException.class)
				.hasMessageContaining("feign.auth.http.socket-timeout");
	}

	private OAuth2TokenRequestClient createClient(FeignAuthProperties.Http http) {
		OAuth2TokenResponseParser parser = new OAuth2TokenResponseParser(new ObjectMapper());
		return new OAuth2TokenRequestClient(parser, new HeaderManager(), new ObjectMapper(), http);
	}

	private static FeignAuthProperties.Service service(String tokenUrl) {
		FeignAuthProperties.Service service = new FeignAuthProperties.Service();
		service.setBaseUrl("http://127.0.0.1");
		FeignAuthProperties.Auth auth = new FeignAuthProperties.Auth();
		auth.setType("oauth2");
		auth.setTokenUrl(tokenUrl);
		service.setAuth(auth);
		return service;
	}

	private static FeignAuthProperties.Client clientCredentials() {
		FeignAuthProperties.Client client = new FeignAuthProperties.Client();
		client.setId("id");
		client.setSecret("secret");
		return client;
	}

	private static void sleep(long millis) {
		try {
			Thread.sleep(millis);
		}
		catch (InterruptedException ex) {
			Thread.currentThread().interrupt();
		}
	}

	private static String readRequestBody(com.sun.net.httpserver.HttpExchange exchange) throws IOException {
		try (InputStream in = exchange.getRequestBody()) {
			ByteArrayOutputStream out = new ByteArrayOutputStream();
			byte[] buffer = new byte[1024];
			int read;
			while ((read = in.read(buffer)) > 0) {
				out.write(buffer, 0, read);
			}
			return new String(out.toByteArray(), StandardCharsets.UTF_8);
		}
	}

	private static Map<String, String> parseFormBody(String body) {
		Map<String, String> pairs = new HashMap<>();
		for (String pair : body.split("&")) {
			int index = pair.indexOf('=');
			pairs.put(pair.substring(0, index), pair.substring(index + 1));
		}
		return pairs;
	}

	private static void respond(com.sun.net.httpserver.HttpExchange exchange, int status, String body) {
		try {
			byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
			exchange.getResponseHeaders().add("Content-Type", "application/json");
			exchange.sendResponseHeaders(status, bytes.length);
			try (OutputStream os = exchange.getResponseBody()) {
				os.write(bytes);
			}
		}
		catch (IOException ex) {
			// 客户端超时后可能已断开，忽略
		}
	}

}
