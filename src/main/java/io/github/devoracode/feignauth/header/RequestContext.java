package io.github.devoracode.feignauth.header;

import feign.RequestTemplate;
import io.github.devoracode.feignauth.autoconfigure.FeignAuthProperties;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.util.Collections;
import java.util.Map;

/**
 * Feign 业务请求场景下传给 {@link HeaderCustomizer} 的不可变上下文对象。
 *
 * @author Wenjie Liu
 * @since 1.14.0
 * @see TokenRequestContext
 */
@Getter
@EqualsAndHashCode
@RequiredArgsConstructor
public final class RequestContext {

	private final String serviceName;

	private final FeignAuthProperties.Service service;

	private final String requestPath;

	private final RequestTemplate template;

	public Map<String, ?> getQueryParams() {
		Map<String, ?> queries = this.template.queries();
		return queries != null ? queries : Collections.emptyMap();
	}

	public String getMethod() {
		return this.template.method();
	}

	public byte[] getBody() {
		return this.template.body();
	}

}
