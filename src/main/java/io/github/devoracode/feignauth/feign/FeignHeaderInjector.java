package io.github.devoracode.feignauth.feign;

import io.github.devoracode.feignauth.autoconfigure.FeignAuthProperties;

import java.util.function.BiConsumer;

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
     * Injects custom headers via the supplied consumer.
     * <p>This method is called for both Feign business requests and OAuth2 token
     * requests. Call {@code header.accept(name, value)} for each header to add.
     *
     * @param serviceName the logical service name
     * @param requestPath the normalized request path
     * @param header      a consumer that accepts {@code (headerName, headerValue)} pairs
     */
    void inject(String serviceName, String requestPath, BiConsumer<String, String> header);
}
