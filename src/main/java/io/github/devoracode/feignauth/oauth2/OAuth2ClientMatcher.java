package io.github.devoracode.feignauth.oauth2;

import io.github.devoracode.feignauth.autoconfigure.FeignAuthProperties;
import io.github.devoracode.feignauth.support.PathUtils;
import org.springframework.util.Assert;

/**
 * Resolves OAuth2 client credentials for a service request path.
 *
 * @author Wenjie Liu
 * @since 1.0.0
 */
public class OAuth2ClientMatcher {

	/**
	 * Selects the OAuth2 client that best matches the given request path.
	 * @param serviceName the logical service name
	 * @param service the service configuration
	 * @param requestPath the normalized request path
	 * @return the selected client
	 */
	public FeignAuthProperties.Client match(String serviceName, FeignAuthProperties.Service service,
			String requestPath) {
		Assert.notNull(service, "service must not be null");
		FeignAuthProperties.Auth auth = service.getAuth();
		if (auth.getClients() == null || auth.getClients().isEmpty()) {
			throw new IllegalStateException("No OAuth2 clients configured for service: " + serviceName);
		}

		String path = PathUtils.normalizePath(requestPath);
		FeignAuthProperties.Client bestClient = null;
		int bestMatchLength = 0;
		for (FeignAuthProperties.Client client : auth.getClients()) {
			if (client == null || client.isDefaultClient()) {
				continue;
			}
			int matchLength = PathUtils.bestPrefixMatchLength(client.getPathPrefixes(), path);
			if (matchLength > bestMatchLength) {
				bestClient = client;
				bestMatchLength = matchLength;
			}
			else if (matchLength > 0 && matchLength == bestMatchLength) {
				throw new IllegalStateException("Multiple OAuth2 clients matched requestPath=" + requestPath
						+ " for service=" + serviceName);
			}
		}
		if (bestClient != null) {
			return bestClient;
		}

		FeignAuthProperties.Client defaultClient = null;
		for (FeignAuthProperties.Client client : auth.getClients()) {
			if (client == null || !client.isDefaultClient()) {
				continue;
			}
			if (defaultClient != null) {
				throw new IllegalStateException("Multiple default OAuth2 clients configured for service: "
						+ serviceName);
			}
			defaultClient = client;
		}
		if (defaultClient != null) {
			return defaultClient;
		}

		throw new IllegalStateException("No OAuth2 client matched requestPath=" + requestPath + " for service="
				+ serviceName);
	}

}
