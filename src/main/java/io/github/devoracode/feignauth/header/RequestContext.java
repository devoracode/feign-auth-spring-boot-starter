package io.github.devoracode.feignauth.header;

import feign.RequestTemplate;
import io.github.devoracode.feignauth.autoconfigure.FeignAuthProperties;

import java.util.Collections;
import java.util.Map;
import java.util.Objects;

/**
 * Immutable context object passed to {@link HeaderCustomizer} for Feign business requests.
 *
 * @author Wenjie Liu
 * @since 1.14.0
 * @see TokenRequestContext
 */
public final class RequestContext {

	private final String serviceName;

	private final FeignAuthProperties.Service service;

	private final String requestPath;

	private final RequestTemplate template;

	public RequestContext(String serviceName, FeignAuthProperties.Service service,
			String requestPath, RequestTemplate template) {
		this.serviceName = serviceName;
		this.service = service;
		this.requestPath = requestPath;
		this.template = template;
	}

	public String getServiceName() {
		return this.serviceName;
	}

	public FeignAuthProperties.Service getService() {
		return this.service;
	}

	public String getRequestPath() {
		return this.requestPath;
	}

	public RequestTemplate getTemplate() {
		return this.template;
	}

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

	@Override
	public boolean equals(Object o) {
		if (this == o) {
			return true;
		}
		if (o == null || getClass() != o.getClass()) {
			return false;
		}
		RequestContext that = (RequestContext) o;
		return Objects.equals(serviceName, that.serviceName) && Objects.equals(service, that.service)
				&& Objects.equals(requestPath, that.requestPath) && Objects.equals(template, that.template);
	}

	@Override
	public int hashCode() {
		return Objects.hash(serviceName, service, requestPath, template);
	}

}
