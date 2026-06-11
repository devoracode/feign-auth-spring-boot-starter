package io.github.devoracode.feignauth.oauth2;

import io.github.devoracode.feignauth.autoconfigure.FeignAuthProperties;
import io.github.devoracode.feignauth.exception.FeignAuthConfigurationException;
import io.github.devoracode.feignauth.feign.FeignHeaderInjector;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.util.Assert;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Executes OAuth2 token requests using configured HTTP methods and request field names.
 *
 * @author Wenjie Liu
 * @since 1.0.0
 */
public class OAuth2TokenRequestClient {

	private final RestTemplate restTemplate;

	private final OAuth2TokenResponseParser responseParser;

	private final List<FeignHeaderInjector> headerInjectors;

	public OAuth2TokenRequestClient(RestTemplate restTemplate, OAuth2TokenResponseParser responseParser) {
		this(restTemplate, responseParser, Collections.<FeignHeaderInjector>emptyList());
	}

	public OAuth2TokenRequestClient(RestTemplate restTemplate, OAuth2TokenResponseParser responseParser,
			List<FeignHeaderInjector> headerInjectors) {
		Assert.notNull(restTemplate, "restTemplate must not be null");
		Assert.notNull(responseParser, "responseParser must not be null");
		this.restTemplate = restTemplate;
		this.responseParser = responseParser;
		this.headerInjectors = (headerInjectors != null) ? headerInjectors : Collections.<FeignHeaderInjector>emptyList();
	}

	/**
	 * Requests a new OAuth2 access token for the given service and client.
	 * @param serviceName the logical service name
	 * @param service the service configuration
	 * @param client the OAuth2 client credentials
	 * @return a populated access token
	 */
	public OAuth2AccessToken requestToken(String serviceName, FeignAuthProperties.Service service,
			FeignAuthProperties.Client client) {
		FeignAuthProperties.Auth auth = service.getAuth();
		if (!StringUtils.hasText(auth.getTokenUrl())) {
			throw new FeignAuthConfigurationException(
					"token-url is required for OAuth2 service baseUrl=" + service.getBaseUrl());
		}
		if (!StringUtils.hasText(client.getId()) || !StringUtils.hasText(client.getSecret())) {
			throw new FeignAuthConfigurationException(
					"OAuth2 client id and secret are required for service baseUrl=" + service.getBaseUrl());
		}

		String method = auth.getMethod() == null ? "post" : auth.getMethod().trim().toLowerCase();
		if ("get".equals(method)) {
			return requestTokenByGet(serviceName, service, auth, client);
		}
		if ("post".equals(method)) {
			return requestTokenByPost(serviceName, service, auth, client);
		}
		throw new FeignAuthConfigurationException("Unsupported OAuth2 token method: " + auth.getMethod());
	}

	private OAuth2AccessToken requestTokenByGet(String serviceName, FeignAuthProperties.Service service,
			FeignAuthProperties.Auth auth, FeignAuthProperties.Client client) {
		Map<String, String> parameters = buildTokenRequestParameters(auth, client);
		UriComponentsBuilder builder = UriComponentsBuilder.fromHttpUrl(auth.getTokenUrl());
		parameters.forEach(builder::queryParam);

		HttpHeaders headers = new HttpHeaders();
		applyTokenHeaders(serviceName, service, headers, parameters);

		ResponseEntity<String> response = this.restTemplate.exchange(builder.toUriString(), HttpMethod.GET,
				new HttpEntity<>(headers), String.class);
		return this.responseParser.parse(response, auth.getExpireAheadSeconds(), auth.getTokenField(),
				auth.getTokenExpiresInSeconds());
	}

	private OAuth2AccessToken requestTokenByPost(String serviceName, FeignAuthProperties.Service service,
			FeignAuthProperties.Auth auth, FeignAuthProperties.Client client) {
		Map<String, String> body = buildTokenRequestParameters(auth, client);

		HttpHeaders headers = new HttpHeaders();
		headers.setContentType(MediaType.APPLICATION_JSON);
		applyTokenHeaders(serviceName, service, headers, body);

		ResponseEntity<String> response = this.restTemplate.postForEntity(auth.getTokenUrl(),
				new HttpEntity<>(body, headers), String.class);
		return this.responseParser.parse(response, auth.getExpireAheadSeconds(), auth.getTokenField(),
				auth.getTokenExpiresInSeconds());
	}

	private void applyTokenHeaders(String serviceName, FeignAuthProperties.Service service,
			HttpHeaders headers, Map<String, String> parameters) {
		for (FeignHeaderInjector injector : this.headerInjectors) {
			if (injector.supports(serviceName, service)) {
				injector.injectTokenHeaders(serviceName, parameters, headers);
			}
		}
	}

	private static Map<String, String> buildTokenRequestParameters(FeignAuthProperties.Auth auth,
			FeignAuthProperties.Client client) {
		FeignAuthProperties.RequestFields fields = auth.getRequestFields();
		String clientIdField = StringUtils.hasText(fields.getClientId()) ? fields.getClientId() : "client_id";
		String clientSecretField = StringUtils.hasText(fields.getClientSecret()) ? fields.getClientSecret()
				: "client_secret";

		Map<String, String> parameters = new HashMap<>();
		parameters.put(clientIdField, client.getId());
		parameters.put(clientSecretField, client.getSecret());

		if (!StringUtils.hasText(fields.getClientId()) && !StringUtils.hasText(fields.getClientSecret())) {
			parameters.put("grant_type",
					StringUtils.hasText(client.getGrantType()) ? client.getGrantType() : "client_credentials");
		} else if (StringUtils.hasText(fields.getGrantType()) && StringUtils.hasText(client.getGrantType())) {
			parameters.put(fields.getGrantType(), client.getGrantType());
		}
		return parameters;
	}

}
