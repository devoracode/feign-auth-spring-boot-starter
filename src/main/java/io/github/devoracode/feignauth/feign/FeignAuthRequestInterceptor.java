package io.github.devoracode.feignauth.feign;

import feign.RequestInterceptor;
import feign.RequestTemplate;
import io.github.devoracode.feignauth.autoconfigure.FeignAuthProperties;
import io.github.devoracode.feignauth.exception.FeignAuthConfigurationException;
import io.github.devoracode.feignauth.header.HeaderManager;
import io.github.devoracode.feignauth.oauth2.TokenFetcher;
import io.github.devoracode.feignauth.support.PathUtils;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.springframework.util.Assert;
import org.springframework.util.StringUtils;

import java.net.URI;

/**
 * Feign {@link RequestInterceptor} that injects authentication headers and additional
 * custom headers into every outgoing Feign business request.
 *
 * @author Wenjie Liu
 * @since 1.0.0
 */
public class FeignAuthRequestInterceptor implements RequestInterceptor {

	private static final Log logger = LogFactory.getLog(FeignAuthRequestInterceptor.class);

	private final ServiceMatcher serviceMatcher;

	private final TokenFetcher tokenFetcher;

	private final HeaderManager headerManager;

	public FeignAuthRequestInterceptor(ServiceMatcher serviceMatcher, TokenFetcher tokenFetcher) {
		this(serviceMatcher, tokenFetcher, new HeaderManager());
	}

	public FeignAuthRequestInterceptor(ServiceMatcher serviceMatcher, TokenFetcher tokenFetcher,
			HeaderManager headerManager) {
		Assert.notNull(serviceMatcher, "serviceMatcher must not be null");
		Assert.notNull(headerManager, "headerManager must not be null");
		this.serviceMatcher = serviceMatcher;
		this.tokenFetcher = tokenFetcher;
		this.headerManager = headerManager;
	}

	@Override
	public void apply(RequestTemplate template) {
		if (template == null || template.feignTarget() == null) {
			return;
		}

		String targetUrl = template.feignTarget().url();
		String baseUrl;
		String targetPathPrefix;
		try {
			URI uri = URI.create(targetUrl);
			String path = StringUtils.hasText(uri.getRawPath()) ? uri.getRawPath() : "";
			baseUrl = uri.getScheme() + "://" + uri.getAuthority();
			targetPathPrefix = trimTrailingSlash(path);
		}
		catch (Exception ex) {
			baseUrl = targetUrl;
			targetPathPrefix = "";
		}

		String methodPath = PathUtils.normalizePath(template.path());
		String requestPath = StringUtils.hasText(targetPathPrefix) ? targetPathPrefix + methodPath : methodPath;

		ResolvedService resolved = this.serviceMatcher.match(baseUrl, requestPath);
		if (resolved == null) {
			if (logger.isWarnEnabled()) {
				logger.warn("FeignAuth: no service config matched for baseUrl=" + baseUrl + ", path=" + requestPath
						+ " — request will be sent without auth header");
			}
			return;
		}

		FeignAuthProperties.Auth auth = resolved.getService().getAuth();
		if (auth == null) {
			if (logger.isWarnEnabled()) {
				logger.warn("FeignAuth: service '" + resolved.getServiceName()
						+ "' has no auth config — request will be sent without auth header");
			}
			return;
		}

		if (auth.isApiKey()) {
			applyApiKey(template, resolved, auth, requestPath);
		}

		if (auth.isOAuth2()) {
			applyOAuth2(template, resolved, auth, requestPath);
		}

		this.headerManager.applyRequestHeaders(
				resolved.getServiceName(), resolved.getService(), requestPath, template);
	}

	/**
	 * Resolves which configured service should handle the given request.
	 * @param baseUrl the base URL of the Feign target
	 * @param requestPath the normalized request path
	 * @return the resolved service, or {@code null} when no service matches
	 */
	public ResolvedService matchService(String baseUrl, String requestPath) {
		return this.serviceMatcher.match(baseUrl, requestPath);
	}

	private void applyApiKey(RequestTemplate template, ResolvedService resolved, FeignAuthProperties.Auth auth,
			String requestPath) {
		if (!StringUtils.hasText(auth.getValue())) {
			throw new FeignAuthConfigurationException(
					"API key value is required for service: " + resolved.getServiceName());
		}
		String headerName = auth.resolveHeaderName();
		String headerValue = prefix(auth) + auth.getValue();
		template.header(headerName, headerValue);
		if (logger.isInfoEnabled()) {
			logger.info("FeignAuth [api-key] service='" + resolved.getServiceName() + "' header='" + headerName
					+ "' path=" + requestPath);
		}
	}

	private void applyOAuth2(RequestTemplate template, ResolvedService resolved, FeignAuthProperties.Auth auth,
			String requestPath) {
		Assert.notNull(this.tokenFetcher, "tokenFetcher must not be null for OAuth2 services");
		String token;
		try {
			token = this.tokenFetcher.getToken(resolved.getServiceName(), requestPath);
		} catch (Exception ex) {
			if (logger.isErrorEnabled()) {
				logger.error("FeignAuth [oauth2] failed to obtain token for service='" + resolved.getServiceName()
						+ "', path=" + requestPath + ": " + ex.getMessage(), ex);
			}
			throw ex;
		}
		String headerName = auth.resolveHeaderName();
		String headerValue = prefix(auth) + token;
		template.header(headerName, headerValue);
		if (logger.isInfoEnabled()) {
			logger.info("FeignAuth [oauth2] service='" + resolved.getServiceName() + "' header='" + headerName
					+ "' path=" + requestPath);
		}
	}

	private static String prefix(FeignAuthProperties.Auth auth) {
		String prefix = auth.getTokenPrefix();
		return StringUtils.hasText(prefix) ? prefix : "";
	}

	private static String trimTrailingSlash(String value) {
		if (!StringUtils.hasText(value)) {
			return value;
		}
		while (value.endsWith("/")) {
			value = value.substring(0, value.length() - 1);
		}
		return value;
	}

}
