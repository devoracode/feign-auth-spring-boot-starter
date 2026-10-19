package io.github.devoracode.feignauth.oauth2;

import ch.qos.logback.classic.Level;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import io.github.devoracode.feignauth.autoconfigure.FeignAuthProperties;
import io.github.devoracode.feignauth.exception.FeignAuthConfigurationException;
import io.github.devoracode.feignauth.exception.FeignAuthTokenException;
import io.github.devoracode.feignauth.feign.LogCaptures;
import io.github.devoracode.feignauth.header.HeaderManager;
import org.apache.http.HttpHost;
import org.apache.http.HttpRequest;
import org.apache.http.HttpVersion;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.conn.ClientConnectionManager;
import org.apache.http.entity.AbstractHttpEntity;
import org.apache.http.entity.ContentType;
import org.apache.http.entity.StringEntity;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.message.BasicHttpResponse;
import org.apache.http.message.BasicStatusLine;
import org.apache.http.params.HttpParams;
import org.apache.http.protocol.HttpContext;
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
				.hasMessageContaining("feign-auth.http.socket-timeout");
	}

	@Test
	void negativeTimeoutIsRejectedInsteadOfClampedToZero() {
		FeignAuthProperties.Http http = new FeignAuthProperties.Http();
		http.setConnectTimeout(Duration.ofSeconds(-1));

		assertThatThrownBy(() -> createClient(http)).isInstanceOf(FeignAuthConfigurationException.class)
				.hasMessageContaining("feign-auth.http.connect-timeout");
	}

	@Test
	void subMillisecondTimeoutIsRejectedBecauseItRoundsToZero() {
		FeignAuthProperties.Http http = new FeignAuthProperties.Http();
		http.setConnectionRequestTimeout(Duration.ofNanos(500_000));

		assertThatThrownBy(() -> createClient(http)).isInstanceOf(FeignAuthConfigurationException.class)
				.hasMessageContaining("feign-auth.http.connection-request-timeout");
	}

	@Test
	void nullTimeoutIsRejectedWithItsKey() {
		FeignAuthProperties.Http http = new FeignAuthProperties.Http();
		http.setSocketTimeout(null);

		assertThatThrownBy(() -> createClient(http)).isInstanceOf(FeignAuthConfigurationException.class)
				.hasMessageContaining("feign-auth.http.socket-timeout");
	}

	@Test
	void defaultGetSendsClientCredentialsAsBasicAuthInsteadOfQuery() {
		AtomicReference<RecordedRequest> recorded = recordTokenRequests();

		FeignAuthProperties.Service service = service(this.tokenUrl);
		service.getAuth().setMethod("get");

		createClient(new FeignAuthProperties.Http()).requestToken("svc", service, clientCredentials());

		assertThat(recorded.get().authorization).isEqualTo("Basic " + basicCredentials("id", "secret"));
		assertThat(recorded.get().query).doesNotContain("client_secret").doesNotContain("client_id")
				.contains("grant_type=client_credentials");
	}

	@Test
	void parametersModePutsCredentialsBackIntoTheQueryAndWarnsOnce() {
		LogCaptures logs = LogCaptures.attach(OAuth2TokenRequestClient.class);
		try {
			AtomicReference<RecordedRequest> recorded = recordTokenRequests();

			FeignAuthProperties.Service service = service(this.tokenUrl);
			service.getAuth().setMethod("get");
			service.getAuth().setClientAuthentication("parameters");
			OAuth2TokenRequestClient client = createClient(new FeignAuthProperties.Http());

			// 旧服务端只读 query：这一行配置就是它退回 1.18 行为的开关
			client.requestToken("svc", service, clientCredentials());
			client.requestToken("svc", service, clientCredentials());

			assertThat(recorded.get().query).contains("client_secret=secret");
			assertThat(recorded.get().authorization).isNull();
			assertThat(logs.contains(Level.WARN, "into the query string")).isTrue();
			assertThat(logs.count(Level.WARN)).isEqualTo(1L);
		}
		finally {
			logs.detach();
		}
	}

	@Test
	void basicModeOnPostMovesCredentialsOutOfTheRequestBody() {
		AtomicReference<RecordedRequest> recorded = recordTokenRequests();

		FeignAuthProperties.Service service = service(this.tokenUrl);
		service.getAuth().setClientAuthentication("basic");

		createClient(new FeignAuthProperties.Http()).requestToken("svc", service, clientCredentials());

		assertThat(recorded.get().authorization).isEqualTo("Basic " + basicCredentials("id", "secret"));
		assertThat(parseJsonBody(recorded.get().body)).doesNotContainKey("client_secret")
				.containsEntry("grant_type", "client_credentials");
	}

	@Test
	void autoModeOnPostKeepsCredentialsInTheBodyAndSendsNoAuthorizationHeader() {
		AtomicReference<RecordedRequest> recorded = recordTokenRequests();

		// POST 是多数派，且不少老 IdP 只认请求体里的 client_secret：auto 不得改变它的线上形态
		createClient(new FeignAuthProperties.Http()).requestToken("svc", service(this.tokenUrl), clientCredentials());

		assertThat(recorded.get().authorization).isNull();
		assertThat(parseJsonBody(recorded.get().body)).containsEntry("client_id", "id")
				.containsEntry("client_secret", "secret");
	}

	@Test
	void basicModeDropsCustomNamedCredentialFieldsFromTheQuery() {
		AtomicReference<RecordedRequest> recorded = recordTokenRequests();

		FeignAuthProperties.Service service = service(this.tokenUrl);
		service.getAuth().setMethod("get");
		service.getAuth().getRequestFields().setClientId("appId");
		service.getAuth().getRequestFields().setClientSecret("appSecret");
		service.getAuth().getRequestFields().setGrantType("grantType");

		FeignAuthProperties.Client client = clientCredentials();
		client.setGrantType("client_credentials");

		createClient(new FeignAuthProperties.Http()).requestToken("svc", service, client);

		// 改名后的凭证字段同样要被摘掉，非凭证参数（grantType）保持原样发送
		assertThat(recorded.get().query).isEqualTo("grantType=client_credentials");
		assertThat(recorded.get().authorization).isEqualTo("Basic " + basicCredentials("id", "secret"));
	}

	@Test
	void configuredAuthorizationHeaderIsNotOverwrittenByClientSecretBasic() {
		// HttpHeaders 的 key 比较不区分大小写，配成 authorization 同样命中
		AtomicReference<RecordedRequest> recorded = recordTokenRequests();
		for (String headerName : new String[] { "Authorization", "authorization" }) {
			FeignAuthProperties.Service service = service(this.tokenUrl);
			service.getAuth().setMethod("get");
			service.getAuth().getTokenRequestHeaders().put(headerName, "Bearer pre-configured");
			recorded.set(null);

			createClient(new FeignAuthProperties.Http()).requestToken("svc", service, clientCredentials());

			assertThat(recorded.get().authorization).isEqualTo("Bearer pre-configured");
			assertThat(recorded.get().query).doesNotContain("client_secret");
		}
	}

	@Test
	void clientIdWithColonFailsFastNamingTheServiceAndTheWayOut() {
		// Spring 的 setBasicAuth 拒绝含冒号的用户名（实测消息为 "Username must not contain a colon"），
		// 裸 IllegalArgumentException 里没有服务名、也看不出是认证方式的问题
		FeignAuthProperties.Service service = service(this.tokenUrl);
		service.getAuth().setMethod("get");
		FeignAuthProperties.Client client = clientCredentials();
		client.setId("id:with-colon");

		assertThatThrownBy(() -> createClient(new FeignAuthProperties.Http()).requestToken("ast", service, client))
				.isInstanceOf(FeignAuthConfigurationException.class)
				.hasMessageContaining("ast")
				.hasMessageContaining("client-authentication");
	}

	@Test
	void tokenUrlWithoutSchemeFailsFastWithItsConfigKey() {
		// 缺协议名时旧写法：GET 走 URIBuilder 尚可，POST 的 new HttpPost(String) 抛的是不含配置键的
		// IllegalArgumentException（M10）
		for (String method : new String[] { "get", "post" }) {
			FeignAuthProperties.Service service = service("127.0.0.1:" + this.server.getAddress().getPort() + "/token");
			service.getAuth().setMethod(method);

			assertThatThrownBy(() -> createClient(new FeignAuthProperties.Http())
					.requestToken("svc", service, clientCredentials()))
					.isInstanceOf(FeignAuthConfigurationException.class)
					.hasMessageContaining("Invalid token-url");
		}
	}

	@Test
	void insecureTokenUrlWarnsOncePerServiceAndStaysQuietOnLoopback() {
		LogCaptures logs = LogCaptures.attach(OAuth2TokenRequestClient.class);
		try {
			OAuth2TokenRequestClient client = createClient(shortTimeoutHttp());
			// 192.0.2.0/24 是保留的文档网段，不会真的连出去
			FeignAuthProperties.Service first = service("http://192.0.2.1:9/token");
			FeignAuthProperties.Service sameAuthority = service("http://192.0.2.1:9/token");
			FeignAuthProperties.Service otherAuthority = service("http://192.0.2.2:9/token");

			// 去重键含服务名：同一个 authority 下的两个服务各提醒一次（否则第二个服务的配置无从定位），
			// 同一服务反复取号只提醒一次
			probeTokenRequest(client, "alpha", first);
			probeTokenRequest(client, "beta", sameAuthority);
			probeTokenRequest(client, "gamma", otherAuthority);
			probeTokenRequest(client, "gamma", otherAuthority);
			assertThat(logs.count(Level.WARN)).isEqualTo(3L);
			assertThat(logs.contains(Level.WARN, "token-url 'http://192.0.2.1:9/token' of service 'beta'")).isTrue();

			// 本地开发的明文回环地址是正常用法，不该刷 WARN
			probeTokenRequest(client, "loopback", service(this.tokenUrl));
			assertThat(logs.count(Level.WARN)).isEqualTo(3L);
		}
		finally {
			logs.detach();
		}
	}

	@Test
	void responseCloseFailureAfterAParsedTokenKeepsTheTokenAndOnlyWarns() {
		LogCaptures logs = LogCaptures.attach(OAuth2TokenRequestClient.class);
		try {
			OAuth2TokenRequestClient client = clientReturning(tokenResponse(200), null);

			// 令牌已经解析到手，归还连接时再出问题只该提醒，不该把取号结果一起作废
			OAuth2AccessToken token = client.requestToken("svc", service(this.tokenUrl), clientCredentials());

			assertThat(token.getAccessToken()).isEqualTo("t");
			assertThat(logs.contains(Level.WARN, "Failed to close token response")).isTrue();
			assertThat(logs.count(Level.WARN)).isEqualTo(1L);
		}
		finally {
			logs.detach();
		}
	}

	@Test
	void transportFailureStillFailsTheTokenRequest() {
		OAuth2TokenRequestClient client = clientReturning(null, new IOException("connection refused"));

		assertThatThrownBy(() -> client.requestToken("svc", service(this.tokenUrl), clientCredentials()))
				.isInstanceOf(FeignAuthTokenException.class)
				.hasStackTraceContaining("Token request failed: connection refused");
	}

	@Test
	void bodyReadFailureKeepsItsOwnCauseWhenCloseAlsoFails() {
		LogCaptures logs = LogCaptures.attach(OAuth2TokenRequestClient.class);
		try {
			FaultyCloseResponse response = tokenResponse(200);
			response.setEntity(new UnreadableEntity());
			OAuth2TokenRequestClient client = clientReturning(response, null);

			// 读正文先失败时，主因仍是读失败；close 的那条只能进日志，不能顶替或改写它
			assertThatThrownBy(() -> client.requestToken("svc", service(this.tokenUrl), clientCredentials()))
					.isInstanceOf(FeignAuthTokenException.class)
					.hasStackTraceContaining("Token request failed: body stream broke halfway");
			assertThat(logs.contains(Level.WARN, "Failed to close token response")).isTrue();
		}
		finally {
			logs.detach();
		}
	}

	@Test
	void errorResponseIsStillDescribedWhenCloseFails() {
		OAuth2TokenRequestClient client = clientReturning(tokenResponse(500), null);

		// 非 2xx 由解析器给出原因，close 失败不参与结果
		assertThatThrownBy(() -> client.requestToken("svc", service(this.tokenUrl), clientCredentials()))
				.isInstanceOf(FeignAuthTokenException.class)
				.hasStackTraceContaining("Token request failed: 500");
	}

	private static void probeTokenRequest(OAuth2TokenRequestClient client, String serviceName,
			FeignAuthProperties.Service service) {
		try {
			client.requestToken(serviceName, service, clientCredentials());
		}
		catch (Exception ex) {
			// 本测试只关心取号前发出的 WARN，连不上是预期内的
		}
	}

	private static FeignAuthProperties.Http shortTimeoutHttp() {
		FeignAuthProperties.Http http = new FeignAuthProperties.Http();
		http.setConnectTimeout(Duration.ofMillis(200));
		http.setConnectionRequestTimeout(Duration.ofMillis(200));
		http.setSocketTimeout(Duration.ofMillis(200));
		return http;
	}

	private AtomicReference<RecordedRequest> recordTokenRequests() {
		AtomicReference<RecordedRequest> recorded = new AtomicReference<>();
		this.server.createContext("/token", exchange -> {
			RecordedRequest request = new RecordedRequest();
			request.method = exchange.getRequestMethod();
			request.query = exchange.getRequestURI().getRawQuery();
			request.authorization = exchange.getRequestHeaders().getFirst("Authorization");
			request.body = readRequestBody(exchange);
			recorded.set(request);
			respond(exchange, 200, "{\"access_token\":\"t\",\"expires_in\":600}");
		});
		return recorded;
	}

	private static final class RecordedRequest {

		private String method;

		private String query;

		private String authorization;

		private String body;

	}

	private OAuth2TokenRequestClient clientReturning(CloseableHttpResponse response, IOException transportFailure) {
		return new OAuth2TokenRequestClient(new OAuth2TokenResponseParser(new ObjectMapper()), new HeaderManager(),
				new ObjectMapper(), new StubHttpClient(response, transportFailure));
	}

	private static FaultyCloseResponse tokenResponse(int status) {
		FaultyCloseResponse response = new FaultyCloseResponse(status);
		response.setEntity(new StringEntity("{\"access_token\":\"t\",\"expires_in\":600}", ContentType.APPLICATION_JSON));
		return response;
	}

	// 无论取号结果如何都要抛出的 close()，用来验证响应归还失败不改变结果
	private static final class FaultyCloseResponse extends BasicHttpResponse implements CloseableHttpResponse {

		private FaultyCloseResponse(int status) {
			super(new BasicStatusLine(HttpVersion.HTTP_1_1, status, "stub"));
		}

		@Override
		public void close() throws IOException {
			throw new IOException("connection is being destroyed");
		}

	}

	private static final class UnreadableEntity extends AbstractHttpEntity {

		@Override
		public boolean isRepeatable() {
			return false;
		}

		@Override
		public long getContentLength() {
			return -1L;
		}

		@Override
		public InputStream getContent() {
			return new InputStream() {
				@Override
				public int read() throws IOException {
					throw new IOException("body stream broke halfway");
				}
			};
		}

		@Override
		public boolean isStreaming() {
			return true;
		}

		@Override
		public void writeTo(OutputStream outStream) {
			throw new UnsupportedOperationException();
		}

	}

	private static final class StubHttpClient extends CloseableHttpClient {

		private final CloseableHttpResponse response;

		private final IOException transportFailure;

		private StubHttpClient(CloseableHttpResponse response, IOException transportFailure) {
			this.response = response;
			this.transportFailure = transportFailure;
		}

		@Override
		protected CloseableHttpResponse doExecute(HttpHost target, HttpRequest request, HttpContext context)
				throws IOException {
			if (this.transportFailure != null) {
				throw this.transportFailure;
			}
			return this.response;
		}

		@Override
		public void close() {
			// 这里没有连接池要关
		}

		@Override
		@SuppressWarnings("deprecation")
		public ClientConnectionManager getConnectionManager() {
			return null;
		}

		@Override
		@SuppressWarnings("deprecation")
		public HttpParams getParams() {
			return null;
		}

	}

	private static String basicCredentials(String id, String secret) {
		return java.util.Base64.getEncoder()
				.encodeToString((id + ":" + secret).getBytes(StandardCharsets.UTF_8));
	}

	private static Map<String, String> parseJsonBody(String body) {
		try {
			Map<String, String> values = new ObjectMapper().readValue(body,
					new com.fasterxml.jackson.core.type.TypeReference<Map<String, String>>() {
					});
			return values;
		}
		catch (IOException ex) {
			throw new IllegalStateException("Unexpected request body: " + body, ex);
		}
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
