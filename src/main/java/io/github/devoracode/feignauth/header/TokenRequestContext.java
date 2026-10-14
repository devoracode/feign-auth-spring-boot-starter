package io.github.devoracode.feignauth.header;

import io.github.devoracode.feignauth.autoconfigure.FeignAuthProperties;
import lombok.EqualsAndHashCode;
import lombok.Getter;

import java.util.Collections;
import java.util.Map;

/**
 * OAuth2 令牌请求场景下传给 {@link HeaderCustomizer} 的不可变上下文对象。
 *
 * @author Wenjie Liu
 * @since 1.14.0
 * @see RequestContext
 */
@Getter
@EqualsAndHashCode
public final class TokenRequestContext {

	private final String serviceName;

	private final FeignAuthProperties.Service service;

	private final FeignAuthProperties.Client client;

	private final Map<String, String> parameters;

	public TokenRequestContext(String serviceName, FeignAuthProperties.Service service,
			FeignAuthProperties.Client client, Map<String, String> parameters) {
		this.serviceName = serviceName;
		this.service = service;
		this.client = client;
		this.parameters = parameters != null ? Collections.unmodifiableMap(parameters) : Collections.emptyMap();
	}

}
