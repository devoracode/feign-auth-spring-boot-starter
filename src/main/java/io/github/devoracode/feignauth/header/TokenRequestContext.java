package io.github.devoracode.feignauth.header;

import io.github.devoracode.feignauth.autoconfigure.FeignAuthProperties;

import java.util.Collections;
import java.util.Map;
import java.util.Objects;

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

	public String getServiceName() {
		return this.serviceName;
	}

	public FeignAuthProperties.Service getService() {
		return this.service;
	}

	public FeignAuthProperties.Client getClient() {
		return this.client;
	}

	public Map<String, String> getParameters() {
		return this.parameters;
	}

	@Override
	public boolean equals(Object o) {
		if (this == o) {
			return true;
		}
		if (o == null || getClass() != o.getClass()) {
			return false;
		}
		TokenRequestContext that = (TokenRequestContext) o;
		return Objects.equals(serviceName, that.serviceName) && Objects.equals(service, that.service)
				&& Objects.equals(client, that.client) && Objects.equals(parameters, that.parameters);
	}

	@Override
	public int hashCode() {
		return Objects.hash(serviceName, service, client, parameters);
	}

}
