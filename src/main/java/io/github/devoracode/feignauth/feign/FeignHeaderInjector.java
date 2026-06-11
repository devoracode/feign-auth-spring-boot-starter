package io.github.devoracode.feignauth.feign;

import feign.RequestTemplate;
import io.github.devoracode.feignauth.autoconfigure.FeignAuthProperties;
import org.springframework.http.HttpHeaders;

import java.util.Map;

/**
 * Strategy interface for injecting additional headers into outgoing Feign requests
 * and OAuth2 token requests.
 *
 * <p>Implement this interface and register it as a Spring bean to inject
 * extra headers for one or more configured services.
 *
 * @author Wenjie Liu
 * @since 1.11.0
 */
public interface FeignHeaderInjector {

    /**
     * Returns whether this injector applies to the given service.
     *
     * @param serviceName the logical service name (key under feign.services)
     * @param service     the resolved service configuration
     * @return {@code true} when this injector should run for the service
     */
    boolean supports(String serviceName, FeignAuthProperties.Service service);

    /**
     * Injects custom headers for Feign business requests.
     * <p>Call {@code template.header(name, value)} for each header to add.
     * <p>Use {@link RequestTemplate#queries()}, {@link RequestTemplate#method()},
     * and {@link RequestTemplate#body()} to access request data for signature etc.
     *
     * @param serviceName the logical service name
     * @param requestPath the normalized request path
     * @param template    the Feign request template
     */
    void inject(String serviceName, String requestPath, RequestTemplate template);

    /**
     * Injects custom headers for OAuth2 token requests.
     * <p>Call {@code headers.set(name, value)} for each header to add.
     * <p>Use {@code parameters} to access the token request body (e.g.
     * {@code client_id}, {@code client_secret}, {@code grant_type}).
     * <p>The default implementation does nothing.
     *
     * @param serviceName the logical service name
     * @param parameters  the token request parameters (key-value pairs)
     * @param headers     the HTTP headers for the token request
     */
    default void injectTokenHeaders(String serviceName, Map<String, String> parameters, HttpHeaders headers) {
    }
}
