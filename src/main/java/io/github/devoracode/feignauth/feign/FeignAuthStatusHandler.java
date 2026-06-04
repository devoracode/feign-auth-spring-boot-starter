package io.github.devoracode.feignauth.feign;

import feign.FeignException;
import feign.Request;
import feign.Response;
import feign.RetryableException;
import io.github.devoracode.feignauth.exception.FeignAuthTokenException;
import io.github.devoracode.feignauth.oauth2.TokenFetcher;
import io.github.devoracode.feignauth.support.PathUtils;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.springframework.util.Assert;
import org.springframework.util.StringUtils;

import java.net.URI;

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

	FeignAuthStatusHandler(ServiceMatcher serviceMatcher, TokenFetcher tokenFetcher) {
		Assert.notNull(serviceMatcher, "serviceMatcher must not be null");
		Assert.notNull(tokenFetcher, "tokenFetcher must not be null");
		this.serviceMatcher = serviceMatcher;
		this.tokenFetcher = tokenFetcher;
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
		return handle(methodKey, response, response != null ? response.status() : null);
	}

	/**
	 * Handle a response using an explicit effective status code.
	 * <p>Used by {@link FeignAuthDecoder} to handle business-level status codes
	 * embedded inside HTTP 200 response bodies.
	 * @param methodKey the Feign method key
	 * @param response the Feign response
	 * @param effectiveStatus the status code to evaluate (may differ from HTTP status)
	 * @return an exception to propagate, or {@code null} when no action is needed
	 */
	Exception handle(String methodKey, Response response, Integer effectiveStatus) {
		if (response == null || response.request() == null || effectiveStatus == null) {
			return null;
		}

		int status = effectiveStatus;
		ResolvedRequest request = ResolvedRequest.from(response.request());
		ResolvedService resolved = (request != null)
				? this.serviceMatcher.match(request.getBaseUrl(), request.getRequestPath()) : null;

		if (isExpiredTokenStatus(status) && isOAuth2Service(resolved) && request != null) {
			return handleExpiredToken(response, resolved, request, status);
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

	private Exception handleExpiredToken(Response response, ResolvedService resolved, ResolvedRequest request,
			int status) {
		boolean evicted = this.tokenFetcher.invalidateToken(resolved.getServiceName(), request.getRequestPath());
		String message = "FeignAuth: service '" + resolved.getServiceName() + "' returned " + status
				+ ", OAuth2 token cache evicted=" + evicted + ", retrying request";
		if (logger.isWarnEnabled()) {
			logger.warn(message);
		}
		return new RetryableException(status, message, response.request().httpMethod(), null,
				response.request());
	}

	private static boolean isExpiredTokenStatus(int status) {
		return status == 421 || status == 423;
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
			try {
				URI uri = URI.create(request.url());
				String path = StringUtils.hasText(uri.getRawPath()) ? uri.getRawPath() : "";
				return new ResolvedRequest(uri.getScheme() + "://" + uri.getAuthority(),
						PathUtils.normalizePath(path));
			}
			catch (Exception ex) {
				return null;
			}
		}

	}

}
