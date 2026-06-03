package io.github.devoracode.feignauth.feign;

import io.github.devoracode.feignauth.config.FeignAuthProperties;
import lombok.RequiredArgsConstructor;

/**
 * Holds a service name and its associated {@link FeignAuthProperties.ServiceConfig},
 * representing a candidate match during service resolution.
 *
 * @author Wenjie Liu
 * @since 1.0.0
 */
@RequiredArgsConstructor
public class MatchedService {

    /** The logical service name as configured in {@link FeignAuthProperties#services}. */
    private final String serviceName;

    /** The service configuration associated with {@link #serviceName}. */
    private final FeignAuthProperties.ServiceConfig service;

    /**
     * Returns the logical service name.
     *
     * @return the service name; never {@code null}
     */
    public String getServiceName() {
        return serviceName;
    }

    /**
     * Returns the service configuration.
     *
     * @return the {@link FeignAuthProperties.ServiceConfig}; never {@code null}
     */
    public FeignAuthProperties.ServiceConfig getService() {
        return service;
    }
}
