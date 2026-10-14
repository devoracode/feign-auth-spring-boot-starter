package io.github.devoracode.feignauth.feign;

import io.github.devoracode.feignauth.autoconfigure.FeignAuthProperties;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * A service configuration resolved for an outgoing Feign request.
 *
 * @author Wenjie Liu
 * @since 1.0.0
 */
@Getter
@RequiredArgsConstructor
public final class ResolvedService {

	private final String serviceName;

	private final FeignAuthProperties.Service service;

}
