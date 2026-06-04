package io.github.devoracode.feignauth.feign;

import io.github.devoracode.feignauth.autoconfigure.FeignAuthProperties;

/**
 * A service configuration resolved for an outgoing Feign request.
 *
 * @author Wenjie Liu
 * @since 1.0.0
 */
public final class ResolvedService {

	private final String serviceName;

	private final FeignAuthProperties.Service service;

	public ResolvedService(String serviceName, FeignAuthProperties.Service service) {
		this.serviceName = serviceName;
		this.service = service;
	}

	public String getServiceName() {
		return this.serviceName;
	}

	public FeignAuthProperties.Service getService() {
		return this.service;
	}

}
