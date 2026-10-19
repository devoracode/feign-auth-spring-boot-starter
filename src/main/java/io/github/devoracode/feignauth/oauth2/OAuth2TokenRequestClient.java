package io.github.devoracode.feignauth.oauth2;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.devoracode.feignauth.autoconfigure.FeignAuthProperties;
import io.github.devoracode.feignauth.exception.FeignAuthConfigurationException;
import io.github.devoracode.feignauth.exception.FeignAuthTokenException;
import io.github.devoracode.feignauth.header.HeaderManager;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.http.HttpEntity;
import org.apache.http.NameValuePair;
import org.apache.http.ParseException;
import org.apache.http.client.entity.UrlEncodedFormEntity;
import org.apache.http.client.config.RequestConfig;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.client.methods.HttpUriRequest;
import org.apache.http.client.utils.URIBuilder;
import org.apache.http.entity.ContentType;
import org.apache.http.entity.StringEntity;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClientBuilder;
import org.apache.http.impl.conn.PoolingHttpClientConnectionManager;
import org.apache.http.message.BasicNameValuePair;
import org.springframework.http.HttpHeaders;
import org.springframework.util.Assert;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * 使用配置的 HTTP 方法与请求字段名执行 OAuth2 令牌请求。
 *
 * @author Wenjie Liu
 * @since 1.0.0
 */
public class OAuth2TokenRequestClient implements AutoCloseable {

	private static final Log logger = LogFactory.getLog(OAuth2TokenRequestClient.class);

	// 取号响应体的读取上限：正常令牌响应远小于此，超出只可能是错误页或伪造的 Content-Length。
	// 按实际读到的字节判定，绝不按对方声明的长度预分配缓冲
	private static final int MAX_RESPONSE_BODY_BYTES = 64 * 1024;

	private static final String CLIENT_AUTHENTICATION_BASIC = "basic";

	private static final String CLIENT_AUTHENTICATION_PARAMETERS = "parameters";

	private final CloseableHttpClient httpClient;

	private final OAuth2TokenResponseParser responseParser;

	private final HeaderManager headerManager;

	private final ObjectMapper objectMapper;

	// 取号是可能反复发生的路径（令牌过期、被驱逐），同一原因的 WARN 只记一次
	private final Set<String> warnedOnce = ConcurrentHashMap.newKeySet();

	public OAuth2TokenRequestClient(OAuth2TokenResponseParser responseParser, HeaderManager headerManager,
			ObjectMapper objectMapper, FeignAuthProperties.Http http) {
		Assert.notNull(responseParser, "responseParser must not be null");
		Assert.notNull(headerManager, "headerManager must not be null");
		Assert.notNull(objectMapper, "objectMapper must not be null");
		Assert.notNull(http, "http must not be null");
		this.httpClient = createHttpClient(http);
		this.responseParser = responseParser;
		this.headerManager = headerManager;
		this.objectMapper = objectMapper;
	}

	// 测试缝：连接层可替换，用于验证响应关闭失败不会作废已解析的令牌
	OAuth2TokenRequestClient(OAuth2TokenResponseParser responseParser, HeaderManager headerManager,
			ObjectMapper objectMapper, CloseableHttpClient httpClient) {
		Assert.notNull(responseParser, "responseParser must not be null");
		Assert.notNull(headerManager, "headerManager must not be null");
		Assert.notNull(objectMapper, "objectMapper must not be null");
		Assert.notNull(httpClient, "httpClient must not be null");
		this.httpClient = httpClient;
		this.responseParser = responseParser;
		this.headerManager = headerManager;
		this.objectMapper = objectMapper;
	}

	/**
	 * 为给定服务与 Client 请求新的 OAuth2 访问令牌。
	 * @param serviceName 逻辑服务名
	 * @param service 服务配置
	 * @param client OAuth2 客户端凭证
	 * @return 装配完成的访问令牌
	 */
	public OAuth2AccessToken requestToken(String serviceName, FeignAuthProperties.Service service,
			FeignAuthProperties.Client client) {
		FeignAuthProperties.Auth auth = service.getAuth();
		if (StringUtils.isBlank(auth.getTokenUrl())) {
			throw new FeignAuthConfigurationException(
					"token-url is required for OAuth2 service baseUrl=" + service.getBaseUrl());
		}
		if (StringUtils.isBlank(client.getId()) || StringUtils.isBlank(client.getSecret())) {
			throw new FeignAuthConfigurationException(
					"OAuth2 client id and secret are required for service baseUrl=" + service.getBaseUrl());
		}
		Map<String, String> parameters = buildTokenRequestParameters(auth, client);
		String method = auth.getMethod() == null ? "post" : auth.getMethod().trim().toLowerCase();
		boolean credentialsInParameters = credentialsInParameters(auth, method);
		if (credentialsInParameters && "get".equals(method)) {
			warnCredentialsInQuery(serviceName);
		}
		if (!credentialsInParameters) {
			// RFC 6749 §2.3.1：一个请求只能使用一种客户端认证方式，走 Basic 时参数里不能再留凭证。
			// 字段名与 buildTokenRequestParameters 同源，request-fields 改名后依然生效
			parameters.remove(StringUtils.defaultString(auth.getRequestFields().getClientId(), "client_id"));
			parameters.remove(StringUtils.defaultString(auth.getRequestFields().getClientSecret(), "client_secret"));
		}
		if ("get".equals(method)) {
			return requestTokenByGet(serviceName, service, auth, client, parameters, credentialsInParameters);
		}
		if ("post".equals(method)) {
			return requestTokenByPost(serviceName, service, auth, client, parameters, credentialsInParameters);
		}
		throw new FeignAuthConfigurationException("Unsupported OAuth2 token method: " + auth.getMethod());
	}

	private OAuth2AccessToken requestTokenByGet(String serviceName, FeignAuthProperties.Service service,
			FeignAuthProperties.Auth auth, FeignAuthProperties.Client client, Map<String, String> parameters,
			boolean credentialsInParameters) {
		HttpHeaders headers = this.headerManager.buildTokenHeaders(serviceName, service, client, parameters);
		if (!credentialsInParameters) {
			applyClientSecretBasic(headers, serviceName, client);
		}

		URI uri;
		try {
			URIBuilder builder = new URIBuilder(parseTokenUrl(serviceName, auth));
			parameters.forEach(builder::addParameter);
			uri = builder.build();
		}
		catch (URISyntaxException ex) {
			throw new FeignAuthConfigurationException("Invalid token-url: " + auth.getTokenUrl());
		}

		HttpGet request = new HttpGet(uri);
		copyHeaders(headers, request);
		return execute(request, auth);
	}

	private OAuth2AccessToken requestTokenByPost(String serviceName, FeignAuthProperties.Service service,
			FeignAuthProperties.Auth auth, FeignAuthProperties.Client client, Map<String, String> parameters,
			boolean credentialsInParameters) {
		HttpHeaders headers = this.headerManager.buildTokenHeaders(serviceName, service, client, parameters);
		if (!credentialsInParameters) {
			applyClientSecretBasic(headers, serviceName, client);
		}

		ContentType contentType = resolveTokenRequestContentType(headers);

		HttpPost request = new HttpPost(parseTokenUrl(serviceName, auth));
		copyHeaders(headers, request);
		request.setEntity(buildRequestEntity(contentType, parameters));
		return execute(request, auth);
	}

	/**
	 * 判断客户端凭证是否以「参数」形式发送（GET 即 query string，POST 即请求体）。
	 *
	 * <p>默认 {@code auto}：GET 走 RFC 6749 §2.3.1 的 {@code client_secret_basic}——
	 * {@code client_secret} 出现在 URL 里会被代理、网关与服务端访问日志留存；POST 保持参数进请求体
	 * （{@code client_secret_post}）以兼容既有对接。取值大小写不敏感，未知取值按 {@code auto} 处理
	 * （配置路径上的笔误已由 {@link FeignAuthProperties} 启动期拦截）。
	 */
	private static boolean credentialsInParameters(FeignAuthProperties.Auth auth, String method) {
		String mode = StringUtils.trimToEmpty(auth.getClientAuthentication());
		if (CLIENT_AUTHENTICATION_BASIC.equalsIgnoreCase(mode)) {
			return false;
		}
		if (CLIENT_AUTHENTICATION_PARAMETERS.equalsIgnoreCase(mode)) {
			return true;
		}
		return !"get".equals(method);
	}

	// RFC 6749 §2.3.1 的 client_secret_basic：client_id:client_secret 按 UTF-8 编码后 Base64
	// （与请求体的编码口径一致）。使用方已在 token-request-headers / HeaderCustomizer 里配置了
	// Authorization 时以它为准，starter 不再代发凭证
	private static void applyClientSecretBasic(HttpHeaders headers, String serviceName,
			FeignAuthProperties.Client client) {
		if (headers.containsKey(HttpHeaders.AUTHORIZATION)) {
			if (logger.isDebugEnabled()) {
				logger.debug("FeignAuth: Authorization header already configured for the token request,"
						+ " client credentials are sent neither as parameters nor as Basic auth");
			}
			return;
		}
		try {
			headers.setBasicAuth(client.getId(), client.getSecret(), StandardCharsets.UTF_8);
		}
		catch (IllegalArgumentException ex) {
			// Spring 的 setBasicAuth 拒绝含冒号的用户名（无法与 "id:secret" 的格式区分），
			// 原样抛出会是一条没有配置键的 IllegalArgumentException
			throw new FeignAuthConfigurationException("FeignAuth: client id of service '" + serviceName
					+ "' cannot be used with client-authentication=basic (" + ex.getMessage()
					+ "); fix the client id or switch to client-authentication: parameters");
		}
	}

	// 显式选择 parameters + GET 时 secret 会重新出现在 URL 中：提示一次，便于定位泄露来源
	private void warnCredentialsInQuery(String serviceName) {
		if (this.warnedOnce.add("credentials-in-query:" + serviceName)) {
			logger.warn("FeignAuth: client-authentication=parameters with method=get puts client_id/client_secret"
					+ " into the query string for service '" + serviceName + "'; they are visible to proxies,"
					+ " gateways and access logs — the default basic mode keeps them in the Authorization header");
		}
	}

	// GET/POST 共用：POST 分支此前把原始字符串交给 HttpPost(String)，非法 URL 抛的是 URI.create 的
	// IllegalArgumentException，消息里既没有配置键、也看不出问题出在 token-url
	private URI parseTokenUrl(String serviceName, FeignAuthProperties.Auth auth) {
		String tokenUrl = auth.getTokenUrl();
		URI uri;
		try {
			uri = new URI(tokenUrl.trim());
		}
		catch (URISyntaxException ex) {
			throw new FeignAuthConfigurationException("Invalid token-url: " + tokenUrl);
		}
		warnIfInsecureTokenUrl(serviceName, uri);
		return uri;
	}

	// 非 https 的 token-url 会让 client_secret 与返回的令牌明文过网：按服务提示一次，便于定位是哪条配置。
	// 同一服务下的多个 Client 共用一个 token-url，因此每服务实际只提醒一次。
	// 回环地址是本地开发的正常用法，不提示
	private void warnIfInsecureTokenUrl(String serviceName, URI uri) {
		String scheme = uri.getScheme();
		if (scheme == null || "https".equalsIgnoreCase(scheme) || isLoopbackHost(uri.getHost())) {
			return;
		}
		if (this.warnedOnce.add("insecure-token-url:" + serviceName + ":"
				+ scheme.toLowerCase(Locale.ROOT) + "://" + uri.getAuthority())) {
			logger.warn("FeignAuth: token-url '" + uri + "' of service '" + serviceName
					+ "' is not https; client credentials and access tokens"
					+ " can be read or modified in transit");
		}
	}

	private static boolean isLoopbackHost(String host) {
		if (host == null) {
			return false;
		}
		return "localhost".equalsIgnoreCase(host) || "::1".equals(host) || "[::1]".equals(host)
				|| host.startsWith("127.");
	}

	// 配置的 Content-Type 决定请求体编码方案，避免头体错配；未配置默认 JSON
	private static ContentType resolveTokenRequestContentType(HttpHeaders headers) {
		String configured = headers.getFirst(HttpHeaders.CONTENT_TYPE);
		if (StringUtils.isBlank(configured)) {
			return ContentType.APPLICATION_JSON;
		}
		ContentType contentType;
		try {
			contentType = ContentType.parse(configured);
		}
		// ParseException 也是 RuntimeException：`;`、`,` 这类被拆成空元素的值只抛它，不一起接住就绕过下面的快速失败
		catch (IllegalArgumentException | ParseException ex) {
			contentType = null;
		}
		if (contentType == null) {
			throw new FeignAuthConfigurationException("Invalid Content-Type in token-request-headers: '" + configured + "'");
		}
		String mimeType = contentType.getMimeType().toLowerCase(Locale.ROOT);
		boolean json = mimeType.endsWith("/json") || mimeType.endsWith("+json");
		boolean form = ContentType.APPLICATION_FORM_URLENCODED.getMimeType().equals(mimeType);
		if (!json && !form) {
			throw new FeignAuthConfigurationException("Unsupported OAuth2 token request Content-Type: '" + configured
					+ "', expected application/json or application/x-www-form-urlencoded");
		}
		return contentType;
	}

	private HttpEntity buildRequestEntity(ContentType contentType, Map<String, String> parameters) {
		if (ContentType.APPLICATION_FORM_URLENCODED.getMimeType().equalsIgnoreCase(contentType.getMimeType())) {
			List<NameValuePair> pairs = new ArrayList<>();
			parameters.forEach((name, value) -> pairs.add(new BasicNameValuePair(name, value)));
			return new UrlEncodedFormEntity(pairs, StandardCharsets.UTF_8);
		}
		Charset charset = contentType.getCharset();
		return new StringEntity(writeJson(parameters), charset != null ? contentType
				: contentType.withCharset(StandardCharsets.UTF_8));
	}

	private OAuth2AccessToken execute(HttpUriRequest request, FeignAuthProperties.Auth auth) {
		CloseableHttpResponse response = null;
		try {
			response = this.httpClient.execute(request);
			int statusCode = response.getStatusLine().getStatusCode();
			HttpEntity entity = response.getEntity();
			ResponseBody body = entity == null ? new ResponseBody(null, false)
					: readBody(entity.getContent(), resolveResponseCharset(entity));
			// 2xx 却超出上限的不可能是令牌响应，残缺 JSON 交给解析器只会得到对不上真实原因的报错
			if (body.isTruncated() && statusCode >= 200 && statusCode < 300) {
				throw new FeignAuthTokenException("Token response exceeds " + MAX_RESPONSE_BODY_BYTES
						+ " bytes, cannot be a valid token response");
			}
			return this.responseParser.parse(statusCode, body.getText(), auth.getExpireAheadSeconds(),
					auth.getTokenField(), auth.getTokenExpiresInSeconds());
		}
		catch (IOException ex) {
			throw new FeignAuthTokenException("Token request failed: " + ex.getMessage(), ex);
		}
		finally {
			closeResponse(response);
		}
	}

	// 归还连接失败不该改写取号结果：正文已交解析器时令牌是有效的，请求本身失败时主因也已经抛出，
	// 这里只补一条 WARN，避免连接层的噪音盖掉真实原因
	private static void closeResponse(CloseableHttpResponse response) {
		if (response == null) {
			return;
		}
		try {
			response.close();
		}
		catch (IOException ex) {
			logger.warn("Failed to close token response: " + ex.getMessage(), ex);
		}
	}

	// 读满上限即停：未读完的正文留在流上，响应关闭时连接直接丢弃
	static ResponseBody readBody(InputStream in, Charset charset) throws IOException {
		// 与原先的 EntityUtils.toString 保持同样的容忍：null 内容流按空正文处理
		if (in == null) {
			return new ResponseBody(null, false);
		}
		ByteArrayOutputStream buffer = new ByteArrayOutputStream();
		byte[] chunk = new byte[8192];
		boolean truncated = false;
		int read;
		while ((read = in.read(chunk)) != -1) {
			int room = MAX_RESPONSE_BODY_BYTES - buffer.size();
			if (read > room) {
				if (room > 0) {
					buffer.write(chunk, 0, room);
				}
				truncated = true;
				break;
			}
			buffer.write(chunk, 0, read);
		}
		return new ResponseBody(new String(buffer.toByteArray(), charset), truncated);
	}

	static final class ResponseBody {

		private final String text;

		private final boolean truncated;

		ResponseBody(String text, boolean truncated) {
			this.text = text;
			this.truncated = truncated;
		}

		String getText() {
			return this.text;
		}

		boolean isTruncated() {
			return this.truncated;
		}

	}

	// 响应声明的字符集优先，未声明或无法解析按 UTF-8。必须用 lenient：strict 版对不认识的
	// charset 抛 UnsupportedCharsetException，属 RuntimeException，逃过本方法 IOException 兜底
	private static Charset resolveResponseCharset(HttpEntity entity) {
		ContentType contentType = ContentType.getLenient(entity);
		if (contentType != null && contentType.getCharset() != null) {
			return contentType.getCharset();
		}
		return StandardCharsets.UTF_8;
	}

	private String writeJson(Map<String, String> parameters) {
		try {
			return this.objectMapper.writeValueAsString(parameters);
		}
		catch (IOException ex) {
			throw new FeignAuthTokenException("Serialize token request failed", ex);
		}
	}

	private static void copyHeaders(HttpHeaders source, HttpUriRequest target) {
		source.forEach((name, values) -> {
			for (String value : values) {
				target.addHeader(name, value);
			}
		});
	}

	// 容器（@Bean 推断 destroyMethod）或调用方显式关闭；CloseableHttpClient.close() 会一并关闭连接池
	@Override
	public void close() throws IOException {
		this.httpClient.close();
	}

	private static CloseableHttpClient createHttpClient(FeignAuthProperties.Http http) {
		PoolingHttpClientConnectionManager connectionManager = new PoolingHttpClientConnectionManager();
		connectionManager.setMaxTotal(http.getMaxTotal());
		connectionManager.setDefaultMaxPerRoute(http.getDefaultMaxPerRoute());

		RequestConfig requestConfig = RequestConfig.custom()
				.setConnectTimeout(toMillis(http.getConnectTimeout(), "feign-auth.http.connect-timeout"))
				.setConnectionRequestTimeout(
						toMillis(http.getConnectionRequestTimeout(), "feign-auth.http.connection-request-timeout"))
				.setSocketTimeout(toMillis(http.getSocketTimeout(), "feign-auth.http.socket-timeout"))
				.build();

		return HttpClientBuilder.create()
				.setConnectionManager(connectionManager)
				.setDefaultRequestConfig(requestConfig)
				.evictIdleConnections(30, TimeUnit.SECONDS)
				// 默认会对幂等 GET 静默重试，并跟随 3xx 把 client 凭证重放到 Location 指向的地址
				.disableAutomaticRetries()
				.disableRedirectHandling()
				.build();
	}

	// RequestConfig 把 0 当无限等待，因此不足 1 毫秒的正值也必须拒绝：它会四舍五入成 0
	private static int toMillis(Duration timeout, String key) {
		// 上下界都用 compareTo 比较：toMillis() 的 seconds*1000 走 multiplyExact，超大时长会先抛
		// ArithmeticException，绕过这里点名配置键的异常口径
		if (timeout == null || timeout.compareTo(Duration.ofMillis(1)) < 0) {
			throw new FeignAuthConfigurationException("FeignAuth: invalid configuration " + key + "=" + timeout
					+ ", must be a positive duration (0 means infinite wait)");
		}
		// 超过 int 上限的超大时长截断为 Integer.MAX_VALUE（HttpClient 参数为 int 毫秒）
		if (timeout.compareTo(Duration.ofMillis(Integer.MAX_VALUE)) > 0) {
			return Integer.MAX_VALUE;
		}
		return (int) timeout.toMillis();
	}

	private static Map<String, String> buildTokenRequestParameters(FeignAuthProperties.Auth auth,
			FeignAuthProperties.Client client) {
		FeignAuthProperties.RequestFields fields = auth.getRequestFields();
		String clientIdField = StringUtils.defaultString(fields.getClientId(), "client_id");
		String clientSecretField = StringUtils.defaultString(fields.getClientSecret(), "client_secret");

		Map<String, String> parameters = new HashMap<>();
		parameters.put(clientIdField, client.getId());
		parameters.put(clientSecretField, client.getSecret());

		if (StringUtils.isBlank(fields.getClientId()) && StringUtils.isBlank(fields.getClientSecret())) {
			parameters.put("grant_type", StringUtils.defaultString(client.getGrantType(), "client_credentials"));
		}
		else if (StringUtils.isNotBlank(fields.getGrantType()) && StringUtils.isNotBlank(client.getGrantType())) {
			parameters.put(fields.getGrantType(), client.getGrantType());
		}
		return parameters;
	}

}
