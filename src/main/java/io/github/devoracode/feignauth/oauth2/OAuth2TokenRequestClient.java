package io.github.devoracode.feignauth.oauth2;

import io.github.devoracode.feignauth.autoconfigure.FeignAuthProperties;
import io.github.devoracode.feignauth.exception.FeignAuthConfigurationException;
import io.github.devoracode.feignauth.exception.FeignAuthTokenException;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.util.Assert;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

import java.util.HashMap;
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

	public OAuth2TokenRequestClient(RestTemplate restTemplate, OAuth2TokenResponseParser responseParser) {
		Assert.notNull(restTemplate, "restTemplate must not be null");
		Assert.notNull(responseParser, "responseParser must not be null");
		this.restTemplate = restTemplate;
		this.responseParser = responseParser;
	}

	/**
	 * Requests a new OAuth2 access token for the given service and client.
	 * @param service the service configuration
	 * @param client the OAuth2 client credentials
	 * @return a populated access token
	 */
	public OAuth2AccessToken requestToken(FeignAuthProperties.Service service, FeignAuthProperties.Client client) {
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
			return requestTokenByGet(auth, client);
		}
		if ("post".equals(method)) {
			return requestTokenByPost(auth, client);
		}
		throw new FeignAuthConfigurationException("Unsupported OAuth2 token method: " + auth.getMethod());
	}

	private OAuth2AccessToken requestTokenByGet(FeignAuthProperties.Auth auth, FeignAuthProperties.Client client) {
		Map<String, String> parameters = buildTokenRequestParameters(auth, client);
		UriComponentsBuilder builder = UriComponentsBuilder.fromHttpUrl(auth.getTokenUrl());
		parameters.forEach(builder::queryParam);

		ResponseEntity<String> response = this.restTemplate.getForEntity(builder.toUriString(), String.class);
		return this.responseParser.parse(response, auth.getExpireAheadSeconds(), auth.getTokenField(),
				auth.getTokenExpiresInSeconds());
	}

	private OAuth2AccessToken requestTokenByPost(FeignAuthProperties.Auth auth, FeignAuthProperties.Client client) {
		Map<String, String> body = buildTokenRequestParameters(auth, client);

		HttpHeaders headers = new HttpHeaders();
		headers.setContentType(MediaType.APPLICATION_JSON);

		ResponseEntity<String> response = this.restTemplate.postForEntity(auth.getTokenUrl(),
				new HttpEntity<>(body, headers), String.class);
		return this.responseParser.parse(response, auth.getExpireAheadSeconds(), auth.getTokenField(),
				auth.getTokenExpiresInSeconds());
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

		if (StringUtils.hasText(fields.getGrantType()) && StringUtils.hasText(client.getGrantType())) {
			parameters.put(fields.getGrantType(), client.getGrantType());
		}
		return parameters;
	}

}
