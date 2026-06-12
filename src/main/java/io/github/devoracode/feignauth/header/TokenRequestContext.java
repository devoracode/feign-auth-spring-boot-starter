package io.github.devoracode.feignauth.header;

import io.github.devoracode.feignauth.autoconfigure.FeignAuthProperties;

import java.util.Collections;
import java.util.Map;

/**
 * Immutable context object passed to {@link HeaderCustomizer} for OAuth2 token requests.
 *
 * @author Wenjie Liu
 * @since 1.14.0
 * @see RequestContext
 */
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
	 * The OAuth2 client whose token is being requested.
	 * <p>Use {@link FeignAuthProperties.Client#getId()} and
	 * {@link FeignAuthProperties.Client#getSecret()} to retrieve the original configured
	 * credentials — these are the raw values regardless of any {@code request-fields}
	 * field-name mapping, making them suitable for HMAC computation.
	 */
	public FeignAuthProperties.Client getClient() {
		return this.client;
	}

	/**
	 * The resolved token request parameters that will be sent to the token endpoint.
	 * <p>Keys reflect the effective field names after {@code request-fields} mapping
	 * (e.g. {@code appId} when {@code request-fields.client-id=appId} is configured).
	 * The map is unmodifiable; mutating token request parameters is not supported.
	 */
	public Map<String, String> getParameters() {
		return this.parameters;
	}

}