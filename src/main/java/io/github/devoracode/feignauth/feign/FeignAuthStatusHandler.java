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
 * Shared status handling for both HTTP errors and business-status errors in successful responses.
 *
 * @author Wenjie Liu
 * @since 1.4.0
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

	Exception handle(String methodKey, Response response, Integer effectiveStatus, boolean businessStatus) {
		if (response == null || response.request() == null || effectiveStatus == null) {
			return null;
		}

		ResolvedRequest request = ResolvedRequest.from(response.request());
		ResolvedService resolved = request == null ? null : this.serviceMatcher.match(request.getBaseUrl(),
				request.getRequestPath());
		if (isExpiredTokenStatus(effectiveStatus) && isOAuth2Service(resolved) && request != null) {
			return handleExpiredToken(response, resolved, request, effectiveStatus, businessStatus);
		}
		if (effectiveStatus < 400) {
			return null;
		}

		String serviceName = resolved == null ? "unknown" : resolved.getServiceName();
		String statusText = describeStatus(response.status(), effectiveStatus, businessStatus);
		Exception cause = businessStatus ? null : FeignException.errorStatus(methodKey, response);
		if (effectiveStatus == 401) {
			return illegalState("FeignAuth: service '" + serviceName + "' " + statusText
					+ ", current credentials are not allowed to access this endpoint", cause);
		}
		if (effectiveStatus == 403) {
			return illegalState("FeignAuth: service '" + serviceName + "' " + statusText
					+ ", the remote service explicitly rejected this request", cause);
		}
		if (effectiveStatus == 404) {
			return illegalState("FeignAuth: service '" + serviceName + "' " + statusText
					+ ", the target resource or path does not exist", cause);
		}
		if (effectiveStatus == 429) {
			return illegalState("FeignAuth: service '" + serviceName + "' " + statusText
					+ ", the remote service rate limit has been exceeded", cause);
		}
		if (effectiveStatus >= 500) {
			return illegalState("FeignAuth: service '" + serviceName + "' " + statusText
					+ ", the remote service is temporarily unavailable", cause);
		}
		return illegalState("FeignAuth: service '" + serviceName + "' " + statusText
				+ ", request failed on the remote side", cause);
	}

	private Exception handleExpiredToken(Response response, ResolvedService resolved, ResolvedRequest request,
			int effectiveStatus, boolean businessStatus) {
		boolean evicted = this.tokenFetcher.invalidateToken(resolved.getServiceName(), request.getRequestPath());
		String message = "FeignAuth: service '" + resolved.getServiceName() + "' "
				+ describeStatus(response.status(), effectiveStatus, businessStatus)
				+ ", OAuth2 token cache evicted=" + evicted + ", retrying request";
		if (logger.isWarnEnabled()) {
			logger.warn(message);
		}
		return new RetryableException(effectiveStatus, message, response.request().httpMethod(), null, response.request());
	}

	private static FeignAuthTokenException illegalState(String message, Exception cause) {
		return cause == null ? new FeignAuthTokenException(message) : new FeignAuthTokenException(message, cause);
	}

	private static boolean isExpiredTokenStatus(int status) {
		return status == 421 || status == 423;
	}

	private static boolean isOAuth2Service(ResolvedService resolved) {
		return resolved != null && resolved.getService() != null && resolved.getService().getAuth() != null
				&& resolved.getService().getAuth().isOAuth2();
	}

	private static String describeStatus(int httpStatus, int effectiveStatus, boolean businessStatus) {
		return businessStatus ? "returned HTTP " + httpStatus + " but body.status=" + effectiveStatus
				: "returned " + effectiveStatus;
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
				return new ResolvedRequest(uri.getScheme() + "://" + uri.getAuthority(), PathUtils.normalizePath(path));
			}
			catch (Exception ex) {
				return null;
			}
		}

	}

}
