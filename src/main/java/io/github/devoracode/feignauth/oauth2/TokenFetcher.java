package io.github.devoracode.feignauth.oauth2;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.devoracode.feignauth.autoconfigure.FeignAuthProperties;
import io.github.devoracode.feignauth.exception.FeignAuthConfigurationException;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.springframework.util.Assert;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestTemplate;

import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Provides cached OAuth2 access tokens for configured Feign services.
 *
 * @author Wenjie Liu
 * @since 1.0.0
 */
public class TokenFetcher {

	private static final Log logger = LogFactory.getLog(TokenFetcher.class);

	private final FeignAuthProperties properties;

	private final OAuth2ClientMatcher clientMatcher;

	private final OAuth2TokenRequestClient tokenRequestClient;

	private final Map<String, OAuth2AccessToken> tokenCache = new ConcurrentHashMap<>();

	public TokenFetcher(FeignAuthProperties properties, OAuth2ClientMatcher clientMatcher,
			OAuth2TokenRequestClient tokenRequestClient) {
		Assert.notNull(properties, "properties must not be null");
		Assert.notNull(clientMatcher, "clientMatcher must not be null");
		Assert.notNull(tokenRequestClient, "tokenRequestClient must not be null");
		this.properties = properties;
		this.clientMatcher = clientMatcher;
		this.tokenRequestClient = tokenRequestClient;
	}

	/**
	 * Creates a {@link TokenFetcher} using the default OAuth2 collaborators.
	 * @param properties the configuration properties
	 * @param restTemplate the HTTP client used to call token endpoints
	 * @param objectMapper the JSON parser used to deserialize token responses
	 */
	public TokenFetcher(FeignAuthProperties properties, RestTemplate restTemplate, ObjectMapper objectMapper) {
		this(properties, new OAuth2ClientMatcher(),
				new OAuth2TokenRequestClient(restTemplate, new OAuth2TokenResponseParser(objectMapper)));
	}

	/**
	 * Returns a valid OAuth2 access token for the given service and request path.
	 * @param serviceName the logical service name
	 * @param requestPath the normalized request path
	 * @return a non-blank access token
	 */
	public String getToken(String serviceName, String requestPath) {
		FeignAuthProperties.Service service = getRequiredService(serviceName);
		FeignAuthProperties.Auth auth = service.getAuth();
		if (auth == null || !auth.isOAuth2()) {
			throw new FeignAuthConfigurationException("Service '" + serviceName + "' is not OAuth2 mode");
		}

		FeignAuthProperties.Client client = resolveClient(serviceName, service, requestPath);
		return getCachedOrFetch(serviceName, service, client);
	}

	/**
	 * Invalidates the cached OAuth2 access token selected for the given request path.
	 * @param serviceName the logical service name
	 * @param requestPath the normalized request path
	 * @return {@code true} when a cached token was removed
	 */
	public boolean invalidateToken(String serviceName, String requestPath) {
		FeignAuthProperties.Service service = getRequiredService(serviceName);
		FeignAuthProperties.Auth auth = service.getAuth();
		if (auth == null || !auth.isOAuth2()) {
			return false;
		}

		FeignAuthProperties.Client client = resolveClient(serviceName, service, requestPath);
		String cacheKey = buildCacheKey(serviceName, client);
		OAuth2AccessToken removed = this.tokenCache.remove(cacheKey);
		if (removed != null && logger.isInfoEnabled()) {
			logger.info("FeignAuth [oauth2] token evicted for service='" + serviceName + "', clientId='"
					+ client.getId() + "'");
		}
		return removed != null;
	}

	/**
	 * Selects the OAuth2 client that best matches the given request path.
	 * @param serviceName the logical service name
	 * @param service the service configuration
	 * @param requestPath the normalized request path
	 * @return the selected client
	 */
	public FeignAuthProperties.Client resolveClient(String serviceName, FeignAuthProperties.Service service,
			String requestPath) {
		return this.clientMatcher.match(serviceName, service, requestPath);
	}

	private FeignAuthProperties.Service getRequiredService(String serviceName) {
		Map<String, FeignAuthProperties.Service> services = this.properties.getServices();
		FeignAuthProperties.Service service = services == null ? null : services.get(serviceName);
		if (service == null) {
			throw new FeignAuthConfigurationException("Service config not found: " + serviceName);
		}
		return service;
	}

	private String getCachedOrFetch(String serviceName, FeignAuthProperties.Service service,
			FeignAuthProperties.Client client) {
		String cacheKey = buildCacheKey(serviceName, client);
		OAuth2AccessToken cached = this.tokenCache.get(cacheKey);
		if (cached != null && !cached.isExpired() && StringUtils.hasText(cached.getAccessToken())) {
			return cached.getAccessToken();
		}

		synchronized (this.tokenCache) {
			OAuth2AccessToken cachedAgain = this.tokenCache.get(cacheKey);
			if (cachedAgain != null && !cachedAgain.isExpired()
					&& StringUtils.hasText(cachedAgain.getAccessToken())) {
				return cachedAgain.getAccessToken();
			}

			if (logger.isInfoEnabled()) {
				logger.info("FeignAuth [oauth2] fetching new token for service='" + serviceName + "', clientId='"
						+ client.getId() + "'");
			}
			OAuth2AccessToken refreshed = this.tokenRequestClient.requestToken(serviceName, service, client);
			this.tokenCache.put(cacheKey, refreshed);
			if (logger.isInfoEnabled()) {
				logger.info("FeignAuth [oauth2] token cached for service='" + serviceName + "', clientId='"
						+ client.getId() + "', expireAt=" + refreshed.getExpireAt());
			}
			return refreshed.getAccessToken();
		}
	}

	private static String buildCacheKey(String serviceName, FeignAuthProperties.Client client) {
		return serviceName + ":" + Objects.toString(client.getId(), "");
	}

}
