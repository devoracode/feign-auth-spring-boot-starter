package io.github.devoracode.feignauth.oauth2;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.devoracode.feignauth.autoconfigure.FeignAuthProperties;
import io.github.devoracode.feignauth.exception.FeignAuthConfigurationException;
import io.github.devoracode.feignauth.exception.FeignAuthTokenException;
import io.github.devoracode.feignauth.header.HeaderManager;
import org.apache.commons.lang3.StringUtils;
import org.apache.http.HttpEntity;
import org.apache.http.NameValuePair;
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
import org.apache.http.util.EntityUtils;
import org.springframework.http.HttpHeaders;
import org.springframework.util.Assert;

import java.io.IOException;
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
import java.util.concurrent.TimeUnit;

/**
 * 使用配置的 HTTP 方法与请求字段名执行 OAuth2 令牌请求。
 *
 * @author Wenjie Liu
 * @since 1.0.0
 */
public class OAuth2TokenRequestClient implements AutoCloseable {

	private final CloseableHttpClient httpClient;

	private final OAuth2TokenResponseParser responseParser;

	private final HeaderManager headerManager;

	private final ObjectMapper objectMapper;

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
		if ("get".equals(method)) {
			return requestTokenByGet(serviceName, service, auth, client, parameters);
		}
		if ("post".equals(method)) {
			return requestTokenByPost(serviceName, service, auth, client, parameters);
		}
		throw new FeignAuthConfigurationException("Unsupported OAuth2 token method: " + auth.getMethod());
	}

	private OAuth2AccessToken requestTokenByGet(String serviceName, FeignAuthProperties.Service service,
			FeignAuthProperties.Auth auth, FeignAuthProperties.Client client, Map<String, String> parameters) {
		HttpHeaders headers = this.headerManager.buildTokenHeaders(serviceName, service, client, parameters);

		URI uri;
		try {
			URIBuilder builder = new URIBuilder(auth.getTokenUrl());
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
			FeignAuthProperties.Auth auth, FeignAuthProperties.Client client, Map<String, String> parameters) {
		HttpHeaders headers = this.headerManager.buildTokenHeaders(serviceName, service, client, parameters);

		ContentType contentType = resolveTokenRequestContentType(headers);

		HttpPost request = new HttpPost(auth.getTokenUrl());
		copyHeaders(headers, request);
		request.setEntity(buildRequestEntity(contentType, parameters));
		return execute(request, auth);
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
		catch (IllegalArgumentException ex) {
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
		try (CloseableHttpResponse response = this.httpClient.execute(request)) {
			int statusCode = response.getStatusLine().getStatusCode();
			HttpEntity entity = response.getEntity();
			String body = entity == null ? null : EntityUtils.toString(entity, resolveResponseCharset(entity));
			return this.responseParser.parse(statusCode, body, auth.getExpireAheadSeconds(), auth.getTokenField(),
					auth.getTokenExpiresInSeconds());
		}
		catch (IOException ex) {
			throw new FeignAuthTokenException("Token request failed: " + ex.getMessage(), ex);
		}
	}

	// 响应声明的字符集优先，未声明（或无法解析）按 UTF-8
	private static Charset resolveResponseCharset(HttpEntity entity) {
		ContentType contentType = ContentType.get(entity);
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
				.setConnectTimeout(toMillis(http.getConnectTimeout()))
				.setConnectionRequestTimeout(toMillis(http.getConnectionRequestTimeout()))
				.setSocketTimeout(toMillis(http.getSocketTimeout()))
				.build();

		return HttpClientBuilder.create()
				.setConnectionManager(connectionManager)
				.setDefaultRequestConfig(requestConfig)
				.evictIdleConnections(30, TimeUnit.SECONDS)
				.build();
	}

	private static int toMillis(Duration timeout) {
		// 超过 int 上限的超大时长截断为 Integer.MAX_VALUE（HttpClient 参数为 int 毫秒）
		return (int) Math.min(Math.max(timeout.toMillis(), 0L), Integer.MAX_VALUE);
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
