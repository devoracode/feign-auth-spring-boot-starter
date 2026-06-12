package io.github.devoracode.feignauth.header;

import feign.RequestTemplate;
import io.github.devoracode.feignauth.autoconfigure.FeignAuthProperties;
 
import java.util.Collections;
import java.util.Map;
 
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
 
	/**
	 * The logical service name (key under {@code feign.services}).
	 */
	public String getServiceName() {
		return this.serviceName;
	}
 
	/**
	 * The full service configuration, including auth settings and base URL.
	 */
	public FeignAuthProperties.Service getService() {
		return this.service;
	}
 
	/**
	 * The normalized request path, e.g. {@code /api/orders/42}.
	 */
	public String getRequestPath() {
		return this.requestPath;
	}
 
	/**
	 * The live Feign request template.
	 * <p>Use {@link RequestTemplate#queries()} to access query parameters,
	 * {@link RequestTemplate#method()} for the HTTP method,
	 * and {@link RequestTemplate#body()} for the serialized request body.
	 */
	public RequestTemplate getTemplate() {
		return this.template;
	}
 
	/**
	 * Convenience accessor for the query parameters of the request.
	 * Returns an empty map when no queries are present.
	 */
	public Map<String, ?> getQueryParams() {
		Map<String, ?> queries = this.template.queries();
		return queries != null ? queries : Collections.emptyMap();
	}
 
	/**
	 * Convenience accessor for the HTTP method of the request, e.g. {@code "GET"}.
	 */
	public String getMethod() {
		return this.template.method();
	}
 
	/**
	 * Convenience accessor for the raw request body bytes.
	 * Returns {@code null} when no body is present.
	 */
	public byte[] getBody() {
		return this.template.body();
	}
 
}