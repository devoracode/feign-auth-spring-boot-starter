package io.github.devoracode.feignauth.feign;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import feign.FeignException;
import feign.Request;
import feign.Response;
import feign.RetryableException;
import feign.Util;
import io.github.devoracode.feignauth.autoconfigure.FeignAuthProperties;
import io.github.devoracode.feignauth.exception.FeignAuthTokenException;
import io.github.devoracode.feignauth.oauth2.TokenFetcher;
import io.github.devoracode.feignauth.support.PathUtils;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.springframework.util.Assert;
import org.apache.commons.lang3.StringUtils;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Date;

/**
 * 处理 Feign 请求的 HTTP 错误响应，包括在 token 过期状态码下淘汰
 * OAuth2 令牌缓存，并为常见 HTTP 错误生成清晰的错误信息。
 *
 * @author Wenjie Liu
 * @since 1.0.0
 */
final class FeignAuthStatusHandler {

	private static final Log logger = LogFactory.getLog(FeignAuthStatusHandler.class);

	private final ServiceMatcher serviceMatcher;

	private final TokenFetcher tokenFetcher;

	private final ObjectMapper objectMapper;

	FeignAuthStatusHandler(ServiceMatcher serviceMatcher, TokenFetcher tokenFetcher, ObjectMapper objectMapper) {
		Assert.notNull(serviceMatcher, "serviceMatcher must not be null");
		Assert.notNull(tokenFetcher, "tokenFetcher must not be null");
		Assert.notNull(objectMapper, "objectMapper must not be null");
		this.serviceMatcher = serviceMatcher;
		this.tokenFetcher = tokenFetcher;
		this.objectMapper = objectMapper;
	}

	/**
	 * 处理 HTTP 错误响应。
	 * <p>当状态码为 421 或 423 且服务使用 OAuth2 时，会淘汰缓存的 token 并返回
	 * {@link RetryableException} 以触发重试。对于其他 4xx/5xx 状态码，返回带有
	 * 描述信息的 {@link FeignAuthTokenException}。
	 * @param methodKey Feign 方法键（用于构造 cause 异常）
	 * @param response Feign 响应
	 * @return 需要向上抛出的异常；状态码低于 400 时返回 {@code null}
	 */
	Exception handle(String methodKey, Response response) {
		if (response == null || response.request() == null) {
			return null;
		}

		int status = response.status();
		ResolvedRequest request = ResolvedRequest.from(response.request());
		ResolvedService resolved = (request != null)
				? this.serviceMatcher.match(request.getBaseUrl(), request.getRequestPath()) : null;

		// 检查是否需要读取响应体
		byte[] bodyBytes = null;
		if (needResponseBodyCheck(resolved, status) && response.body() != null) {
			try {
				bodyBytes = Util.toByteArray(response.body().asInputStream());
				// 创建响应体已恢复的新 Response，供后续使用
				response = response.toBuilder()
						.body(bodyBytes)
						.build();
			} catch (IOException e) {
				if (logger.isWarnEnabled()) {
					logger.warn("Failed to read response body for expired token check", e);
				}
			}
		}

		// 检查 token 是否过期（HTTP 状态码或响应体状态）
		Integer detectedStatus = detectExpiredTokenStatus(resolved, status, bodyBytes);
		if (detectedStatus != null && isOAuth2Service(resolved)) {
			return handleExpiredToken(response, resolved, request, detectedStatus);
		}

		if (status < 400) {
			return null;
		}

		String serviceName = (resolved != null) ? resolved.getServiceName() : "unknown";
		Exception cause = FeignException.errorStatus(methodKey, response);
		if (status == 401) {
			return new FeignAuthTokenException("FeignAuth: service '" + serviceName + "' returned " + status
					+ ", current credentials are not allowed to access this endpoint", cause);
		}
		if (status == 403) {
			return new FeignAuthTokenException("FeignAuth: service '" + serviceName + "' returned " + status
					+ ", the remote service explicitly rejected this request", cause);
		}
		if (status == 404) {
			return new FeignAuthTokenException("FeignAuth: service '" + serviceName + "' returned " + status
					+ ", the target resource or path does not exist", cause);
		}
		if (status == 429) {
			return new FeignAuthTokenException("FeignAuth: service '" + serviceName + "' returned " + status
					+ ", the remote service rate limit has been exceeded", cause);
		}
		if (status >= 500) {
			return new FeignAuthTokenException("FeignAuth: service '" + serviceName + "' returned " + status
					+ ", the remote service is temporarily unavailable", cause);
		}
		return new FeignAuthTokenException("FeignAuth: service '" + serviceName + "' returned " + status
				+ ", request failed on the remote side", cause);
	}

	private boolean needResponseBodyCheck(ResolvedService resolved, int status) {
		if (resolved == null || resolved.getService() == null || resolved.getService().getAuth() == null) {
			return false;
		}
		FeignAuthProperties.Auth auth = resolved.getService().getAuth();
		// 仅在以下条件同时满足时才检查响应体：
		// 1. HTTP 状态码本身不是 token 过期状态
		// 2. 该服务为 OAuth2 服务
		// 3. 已显式配置 responseStatusField
		return !isExpiredTokenStatus(resolved, status) && isOAuth2Service(resolved)
				&& StringUtils.isNotBlank(auth.getResponseStatusField());
	}

	private Integer detectExpiredTokenStatus(ResolvedService resolved, int status, byte[] bodyBytes) {
		// 首先检查 HTTP 状态码
		if (isExpiredTokenStatus(resolved, status)) {
			return status;
		}

		// 若响应体可用，再检查响应体中的状态
		if (bodyBytes == null || bodyBytes.length == 0) {
			return null;
		}

		if (resolved == null || resolved.getService() == null || resolved.getService().getAuth() == null) {
			return null;
		}

		FeignAuthProperties.Auth auth = resolved.getService().getAuth();
		String responseStatusField = auth.getResponseStatusField();
		if (StringUtils.isBlank(responseStatusField)) {
			return null;
		}

		try {
			String body = new String(bodyBytes, StandardCharsets.UTF_8);
			Integer responseStatus = parseJsonField(body, responseStatusField);
			if (responseStatus != null && auth.isExpiredTokenStatus(responseStatus)) {
				if (logger.isDebugEnabled()) {
					logger.debug("FeignAuth: detected expired token status " + responseStatus + " in response body");
				}
				return responseStatus;
			}
		} catch (Exception e) {
			if (logger.isDebugEnabled()) {
				logger.debug("Failed to parse response body for expired token check", e);
			}
		}

		return null;
	}

	private Integer parseJsonField(String jsonBody, String fieldPath) {
		if (StringUtils.isBlank(jsonBody) || StringUtils.isBlank(fieldPath)) {
			return null;
		}

		try {
			JsonNode root = this.objectMapper.readTree(jsonBody);
			String[] fields = fieldPath.split("\\.");
			JsonNode current = root;

			for (String field : fields) {
				if (current == null || current.isNull()) {
					return null;
				}
				current = current.get(field);
			}

			if (current != null && !current.isNull()) {
				if (current.isNumber()) {
					return current.asInt();
				} else if (current.isTextual()) {
					return Integer.parseInt(current.asText());
				}
			}
		} catch (Exception e) {
			if (logger.isDebugEnabled()) {
				logger.debug("Failed to parse JSON field: " + fieldPath, e);
			}
		}

		return null;
	}

	private Exception handleExpiredToken(Response response, ResolvedService resolved, ResolvedRequest request,
			Integer detectedStatus) {

		boolean evicted = this.tokenFetcher.invalidateToken(resolved.getServiceName(), request.getRequestPath());
		String message = "FeignAuth: service '" + resolved.getServiceName() + "' returned " + detectedStatus
				+ ", OAuth2 token cache evicted=" + evicted + ", retrying request";
		if (logger.isWarnEnabled()) {
			logger.warn(message);
		}
		Date retryAfter = new Date(System.currentTimeMillis() + 1000L);
		return new RetryableException(detectedStatus, message, response.request().httpMethod(), retryAfter,
				response.request());
	}

	private static boolean isExpiredTokenStatus(ResolvedService resolved, int status) {
		if (resolved == null || resolved.getService() == null || resolved.getService().getAuth() == null) {
			return false;
		}
		return resolved.getService().getAuth().isExpiredTokenStatus(status);
	}

	private static boolean isOAuth2Service(ResolvedService resolved) {
		return resolved != null && resolved.getService() != null && resolved.getService().getAuth() != null
				&& resolved.getService().getAuth().isOAuth2();
	}

	static final class ResolvedRequest {

		private final String baseUrl;

		private final String requestPath;

		private ResolvedRequest(String baseUrl, String requestPath) {
			this.baseUrl = baseUrl;
			this.requestPath = requestPath;
		}

		String getBaseUrl() {
			return this.baseUrl;
		}

		String getRequestPath() {
			return this.requestPath;
		}

		static ResolvedRequest from(Request request) {
			if (request == null || StringUtils.isBlank(request.url())) {
				return null;
			}
			URI uri = URI.create(request.url());
			String path = StringUtils.isNotBlank(uri.getRawPath()) ? uri.getRawPath() : "";
			return new ResolvedRequest(uri.getScheme() + "://" + uri.getAuthority(),
					PathUtils.normalizePath(path));
		}

	}

}
