package io.github.devoracode.feignauth.header;

import feign.RequestTemplate;
import io.github.devoracode.feignauth.autoconfigure.FeignAuthProperties;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.util.Assert;
import org.springframework.util.StringUtils;

import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Central coordinator for the three-stage header injection pipeline.
 *
 * <p>Both the Feign business-request path and the OAuth2 token-request path share
 * this class to ensure consistent behavior and a single point of extension.
 *
 * <h3>Pipeline stages</h3>
 * <ol>
 *   <li><strong>Static</strong> — headers declared in the service configuration
 *       ({@code request-headers} or {@code token-request-headers}) are written first.
 *       Blank names and {@code null} values are silently skipped.</li>
 *   <li><strong>Dynamic ({@link HeaderCustomizer})</strong> — registered
 *       {@link HeaderCustomizer} beans whose {@link HeaderCustomizer#supports} returns
 *       {@code true} are invoked in list order. They may override static values when
 *       the same header name is reused.</li>
 * </ol>
 *
 * <p>For business requests the final {@link HttpHeaders} are merged into the Feign
 * {@link RequestTemplate} by the caller. For token requests the {@link HttpHeaders}
 * object is passed directly to the HTTP entity.
 *
 * @author Wenjie Liu
 * @since 1.14.0
 */
public class HeaderManager {

	private static final Log logger = LogFactory.getLog(HeaderManager.class);

	private final List<HeaderCustomizer> customizers;

	public HeaderManager() {
		this(Collections.<HeaderCustomizer>emptyList());
	}

	public HeaderManager(List<HeaderCustomizer> customizers) {
		this.customizers = (customizers != null) ? customizers : Collections.<HeaderCustomizer>emptyList();
	}

	// ── Business request ─────────────────────────────────────────────────────

	/**
	 * Builds the additional headers for a Feign business request and writes them into
	 * the provided {@link RequestTemplate}.
	 *
	 * <p>Stage 1 reads {@link FeignAuthProperties.Auth#getRequestHeaders()}.
	 * Stage 2 calls {@link HeaderCustomizer#customize(RequestContext, HttpHeaders)}
	 * on every matching customizer.
	 *
	 * @param serviceName the logical service name
	 * @param service the service configuration
	 * @param requestPath the normalized request path
	 * @param template the Feign request template to write headers into
	 */
	public void applyRequestHeaders(String serviceName, FeignAuthProperties.Service service,
			String requestPath, RequestTemplate template) {
		Assert.notNull(template, "template must not be null");

		HttpHeaders headers = new HttpHeaders();

		// Stage 1: static headers from request-headers config
		applyStaticHeaders(service.getAuth().getRequestHeaders(), headers);

		// Stage 2: dynamic headers from HeaderCustomizer beans
		if (!this.customizers.isEmpty()) {
			RequestContext context = new RequestContext(serviceName, service, requestPath, template);
			for (HeaderCustomizer customizer : this.customizers) {
				invokeCustomizer(customizer, serviceName, service,
						() -> customizer.customize(context, headers));
			}
		}

		// Merge into RequestTemplate
		headers.forEach((name, values) -> {
			if (!values.isEmpty()) {
				template.header(name, values);
			}
		});
	}

	// ── Token request ────────────────────────────────────────────────────────

	/**
	 * Builds the {@link HttpHeaders} for an OAuth2 token HTTP request.
	 *
	 * <p>Stage 1 reads {@link FeignAuthProperties.Auth#getTokenRequestHeaders()}.
	 * Stage 2 calls {@link HeaderCustomizer#customize(TokenRequestContext, HttpHeaders)}
	 * on every matching customizer.
	 *
	 * @param serviceName the logical service name
	 * @param service the service configuration
	 * @param client the OAuth2 client whose token is being requested
	 * @param parameters the resolved token request parameters (field-name-mapped)
	 * @return the populated {@link HttpHeaders} (does not include Content-Type;
	 *         callers must set that themselves when needed)
	 */
	public HttpHeaders buildTokenHeaders(String serviceName, FeignAuthProperties.Service service,
			FeignAuthProperties.Client client, Map<String, String> parameters) {
		HttpHeaders headers = new HttpHeaders();

		// Stage 1: static headers from token-request-headers config
		applyStaticHeaders(service.getAuth().getTokenRequestHeaders(), headers);

		// Stage 2: dynamic headers from HeaderCustomizer beans
		if (!this.customizers.isEmpty()) {
			TokenRequestContext context = new TokenRequestContext(serviceName, service, client, parameters);
			for (HeaderCustomizer customizer : this.customizers) {
				invokeCustomizer(customizer, serviceName, service,
						() -> customizer.customize(context, headers));
			}
		}

		return headers;
	}

	// ── Internal helpers ─────────────────────────────────────────────────────

	private static void applyStaticHeaders(Map<String, String> staticHeaders, HttpHeaders target) {
		if (staticHeaders == null || staticHeaders.isEmpty()) {
			return;
		}
		staticHeaders.forEach((name, value) -> {
			if (StringUtils.hasText(name) && value != null) {
				target.set(name, value);
			}
		});
	}

	private void invokeCustomizer(HeaderCustomizer customizer, String serviceName,
			FeignAuthProperties.Service service, Runnable action) {
		try {
			if (customizer.supports(serviceName, service)) {
				action.run();
			}
		}
		catch (Exception ex) {
			if (logger.isErrorEnabled()) {
				logger.error("FeignAuth: HeaderCustomizer " + customizer.getClass().getSimpleName()
						+ " failed for service='" + serviceName + "': " + ex.getMessage(), ex);
			}
			throw ex;
		}
	}

}
