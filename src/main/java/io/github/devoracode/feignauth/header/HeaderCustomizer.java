package io.github.devoracode.feignauth.header;

import io.github.devoracode.feignauth.autoconfigure.FeignAuthProperties;
import org.springframework.http.HttpHeaders;

/**
 * Unified strategy interface for injecting dynamic headers into both Feign business
 * requests and OAuth2 token requests.
 *
 * @author Wenjie Liu
 * @since 1.14.0
 */
public interface HeaderCustomizer {

	/**
	 * Returns whether this customizer should be applied to the given service.
	 * @param serviceName the logical service name (key under {@code feign.services})
	 * @param service the service configuration
	 * @return {@code true} when this customizer should run for the service
	 */
	boolean supports(String serviceName, FeignAuthProperties.Service service);

	/**
	 * Adds or modifies headers for an outgoing Feign business request.
	 * <p>The default implementation does nothing.
	 * @param context the business request context; provides access to path, query params,
	 *                HTTP method, request body, and the full {@link feign.RequestTemplate}
	 * @param headers the mutable headers that will be applied to the request template
	 */
	default void customize(RequestContext context, HttpHeaders headers) {
	}

	/**
	 * Adds or modifies headers for an outgoing OAuth2 token HTTP request.
	 * <p>The default implementation does nothing.
	 * @param context the token request context; provides access to the matched
	 *                {@link FeignAuthProperties.Client} and resolved token parameters
	 * @param headers the mutable HTTP headers of the outgoing token request
	 */
	default void customize(TokenRequestContext context, HttpHeaders headers) {
	}

}