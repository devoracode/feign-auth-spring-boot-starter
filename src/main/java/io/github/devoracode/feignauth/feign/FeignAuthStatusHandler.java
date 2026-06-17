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
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Date;

/**
 * Handles HTTP error responses for Feign requests, including OAuth2 token eviction
 * on expired-token status codes and clear error messages for common HTTP errors.
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
	 * Handle an HTTP error response.
	 * <p>When the status is 421 or 423 and the service uses OAuth2, the cached token
	 * is evicted and a {@link RetryableException} is returned to trigger a retry.
	 * For other 4xx/5xx statuses a descriptive {@link FeignAuthTokenException} is returned.
	 * @param methodKey the Feign method key (used to build the cause exception)
	 * @param response the Feign response
	 * @return an exception to propagate, or {@code null} when the status is below 400
	 */
	Exception handle(String methodKey, Response response) {
		if (response == null || response.request() == null) {
			return null;
		}

		int status = response.status();
		ResolvedRequest request = ResolvedRequest.from(response.request());
		ResolvedService resolved = (request != null)
				? this.serviceMatcher.match(request.getBaseUrl(), request.getRequestPath()) : null;

		// Check if need to inspect response body
		byte[] bodyBytes = null;
		if (needResponseBodyCheck(resolved, status) && response.body() != null) {
			try {
				bodyBytes = Util.toByteArray(response.body().asInputStream());
				// Create a new Response with the body restored for later use
				response = response.toBuilder()
						.body(bodyBytes)
						.build();
			} catch (IOException e) {
				if (logger.isWarnEnabled()) {
					logger.warn("Failed to read response body for expired token check", e);
				}
			}
		}

		// Check for expired token (HTTP status code or response body status)
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
		// Only check response body if:
		// 1. HTTP status is not already an expired token status
		// 2. This is an OAuth2 service
		// 3. responseStatusField is explicitly configured
		return !isExpiredTokenStatus(resolved, status) && isOAuth2Service(resolved)
				&& StringUtils.hasText(auth.getResponseStatusField());
	}

	private Integer detectExpiredTokenStatus(ResolvedService resolved, int status, byte[] bodyBytes) {
		// First check HTTP status code
		if (isExpiredTokenStatus(resolved, status)) {
			return status;
		}

		// Then check response body status if body is available
		if (bodyBytes == null || bodyBytes.length == 0) {
			return null;
		}

		if (resolved == null || resolved.getService() == null || resolved.getService().getAuth() == null) {
			return null;
		}

		FeignAuthProperties.Auth auth = resolved.getService().getAuth();
		String responseStatusField = auth.getResponseStatusField();
		if (!StringUtils.hasText(responseStatusField)) {
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
		if (!StringUtils.hasText(jsonBody) || !StringUtils.hasText(fieldPath)) {
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
			if (request == null || !StringUtils.hasText(request.url())) {
				return null;
			}
			URI uri = URI.create(request.url());
			String path = StringUtils.hasText(uri.getRawPath()) ? uri.getRawPath() : "";
			return new ResolvedRequest(uri.getScheme() + "://" + uri.getAuthority(),
					PathUtils.normalizePath(path));
		}

	}

}
