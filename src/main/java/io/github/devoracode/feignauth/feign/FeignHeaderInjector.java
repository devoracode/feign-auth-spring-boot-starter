package io.github.devoracode.feignauth.feign;

import feign.RequestTemplate;
import io.github.devoracode.feignauth.autoconfigure.FeignAuthProperties;

/**
 * Strategy interface for injecting additional headers into outgoing Feign requests.
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
     * Injects headers into the request template.
     *
     * @param serviceName the logical service name
     * @param requestPath the normalized request path
     * @param template    the Feign request template to modify
     */
    void inject(String serviceName, String requestPath, RequestTemplate template);
}