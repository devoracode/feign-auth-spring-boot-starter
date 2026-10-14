package io.github.devoracode.feignauth.feign;

import io.github.devoracode.feignauth.autoconfigure.FeignAuthProperties;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * 为发出的 Feign 请求解析得到的服务配置。
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
