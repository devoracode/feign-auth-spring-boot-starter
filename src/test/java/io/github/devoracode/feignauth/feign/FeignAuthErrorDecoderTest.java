package io.github.devoracode.feignauth.feign;

import feign.Request;
import feign.RequestTemplate;
import feign.Response;
import feign.RetryableException;
import feign.codec.Decoder;
import io.github.devoracode.feignauth.autoconfigure.FeignAuthProperties;
import io.github.devoracode.feignauth.exception.FeignAuthTokenException;
import io.github.devoracode.feignauth.oauth2.OAuth2AccessToken;
import io.github.devoracode.feignauth.oauth2.OAuth2ClientMatcher;
import io.github.devoracode.feignauth.oauth2.OAuth2TokenRequestClient;
import io.github.devoracode.feignauth.oauth2.OAuth2TokenResponseParser;
import io.github.devoracode.feignauth.oauth2.TokenFetcher;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestTemplate;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FeignAuthErrorDecoderTest {

	@Test
	void evictsOAuth2TokenAndRetriesWhenRemoteServiceReturns423() {
		FeignAuthProperties properties = new FeignAuthProperties();
		properties.setServices(new LinkedHashMap<>());
		properties.getServices().put("measure", oauth2Service("https://api.service-a.com",
				client("telemetry", "/api/measure"), client("default")));

		CountingTokenRequestClient tokenRequestClient = new CountingTokenRequestClient();
		TokenFetcher tokenFetcher = new TokenFetcher(properties, new OAuth2ClientMatcher(), tokenRequestClient);
		FeignAuthErrorDecoder decoder = new FeignAuthErrorDecoder(new ServiceMatcher(properties), tokenFetcher);

		assertThat(tokenFetcher.getToken("measure", "/api/measure/read/1")).isEqualTo("token-1");

		Exception exception = decoder.decode("MeasureClient#getReading",
				response(423, "https://api.service-a.com/api/measure/read/1"));

		assertThat(exception).isInstanceOf(RetryableException.class);
		assertThat(tokenFetcher.getToken("measure", "/api/measure/read/1")).isEqualTo("token-2");
		assertThat(tokenRequestClient.getRequestCount()).isEqualTo(2);
	}

	@Test
	void returnsClearUnauthorizedExceptionFor401WithoutRetry() {
		FeignAuthProperties properties = new FeignAuthProperties();
		properties.setServices(new LinkedHashMap<>());
		properties.getServices().put("measure", oauth2Service("https://api.service-a.com",
				client("telemetry", "/api/measure"), client("default")));

		CountingTokenRequestClient tokenRequestClient = new CountingTokenRequestClient();
		TokenFetcher tokenFetcher = new TokenFetcher(properties, new OAuth2ClientMatcher(), tokenRequestClient);
		FeignAuthErrorDecoder decoder = new FeignAuthErrorDecoder(new ServiceMatcher(properties), tokenFetcher);

		Exception exception = decoder.decode("MeasureClient#getReading",
				response(401, "https://api.service-a.com/api/measure/read/1"));

		assertThat(exception).isInstanceOf(FeignAuthTokenException.class)
				.isNotInstanceOf(RetryableException.class)
				.hasMessageContaining("returned 401")
				.hasMessageContaining("measure");
		assertThat(tokenRequestClient.getRequestCount()).isZero();
	}

	@Test
	void evictsOAuth2TokenAndRetriesWhenHttp200BodyContainsNumeric421Status() throws Exception {
		FeignAuthProperties properties = new FeignAuthProperties();
		properties.setServices(new LinkedHashMap<>());
		properties.getServices().put("measure", oauth2Service("https://api.service-a.com",
				client("telemetry", "/api/measure"), client("default")));

		CountingTokenRequestClient tokenRequestClient = new CountingTokenRequestClient();
		TokenFetcher tokenFetcher = new TokenFetcher(properties, new OAuth2ClientMatcher(), tokenRequestClient);
		ServiceMatcher serviceMatcher = new ServiceMatcher(properties);
		FeignAuthDecoder decoder = new FeignAuthDecoder(new Decoder.Default(), new ObjectMapper(),
				new FeignAuthStatusHandler(serviceMatcher, tokenFetcher));

		assertThat(tokenFetcher.getToken("measure", "/api/measure/read/1")).isEqualTo("token-1");

		assertThatThrownBy(() -> decoder.decode(
				response(200, "https://api.service-a.com/api/measure/read/1", "{\"status\":421}"), String.class))
				.isInstanceOf(RetryableException.class);
		assertThat(tokenFetcher.getToken("measure", "/api/measure/read/1")).isEqualTo("token-2");
		assertThat(tokenRequestClient.getRequestCount()).isEqualTo(2);
	}

	@Test
	void evictsOAuth2TokenAndRetriesWhenHttp200BodyContainsString423Status() throws Exception {
		FeignAuthProperties properties = new FeignAuthProperties();
		properties.setServices(new LinkedHashMap<>());
		properties.getServices().put("measure", oauth2Service("https://api.service-a.com",
				client("telemetry", "/api/measure"), client("default")));

		CountingTokenRequestClient tokenRequestClient = new CountingTokenRequestClient();
		TokenFetcher tokenFetcher = new TokenFetcher(properties, new OAuth2ClientMatcher(), tokenRequestClient);
		ServiceMatcher serviceMatcher = new ServiceMatcher(properties);
		FeignAuthDecoder decoder = new FeignAuthDecoder(new Decoder.Default(), new ObjectMapper(),
				new FeignAuthStatusHandler(serviceMatcher, tokenFetcher));

		assertThat(tokenFetcher.getToken("measure", "/api/measure/read/1")).isEqualTo("token-1");

		assertThatThrownBy(() -> decoder.decode(
				response(200, "https://api.service-a.com/api/measure/read/1", "{\"status\":\"423\"}"), String.class))
				.isInstanceOf(RetryableException.class);
		assertThat(tokenFetcher.getToken("measure", "/api/measure/read/1")).isEqualTo("token-2");
		assertThat(tokenRequestClient.getRequestCount()).isEqualTo(2);
	}

	private static Response response(int status, String url) {
		return response(status, url, null);
	}

	private static Response response(int status, String url, String body) {
		Request request = Request.create(Request.HttpMethod.GET, url,
				Collections.<String, Collection<String>>emptyMap(),
				null, StandardCharsets.UTF_8, new RequestTemplate());
		return Response.builder()
				.status(status)
				.reason("test")
				.request(request)
				.headers(Collections.<String, Collection<String>>emptyMap())
				.body(body, StandardCharsets.UTF_8)
				.build();
	}

	private static FeignAuthProperties.Service oauth2Service(String baseUrl, FeignAuthProperties.Client... clients) {
		FeignAuthProperties.Service service = new FeignAuthProperties.Service();
		service.setBaseUrl(baseUrl);

		FeignAuthProperties.Auth auth = new FeignAuthProperties.Auth();
		auth.setType("oauth2");
		auth.setTokenUrl(baseUrl + "/oauth/token");
		auth.setClients(Arrays.asList(clients));
		service.setAuth(auth);
		return service;
	}

	private static FeignAuthProperties.Client client(String id, String... pathPrefixes) {
		FeignAuthProperties.Client client = new FeignAuthProperties.Client();
		client.setId(id);
		client.setSecret("secret-" + id);
		client.setPathPrefixes(Arrays.asList(pathPrefixes));
		return client;
	}

	private static final class CountingTokenRequestClient extends OAuth2TokenRequestClient {

		private int requestCount;

		private CountingTokenRequestClient() {
			super(new RestTemplate(), new OAuth2TokenResponseParser(new ObjectMapper()));
		}

		@Override
		public OAuth2AccessToken requestToken(FeignAuthProperties.Service service, FeignAuthProperties.Client client) {
			this.requestCount++;
			OAuth2AccessToken accessToken = new OAuth2AccessToken();
			accessToken.setAccessToken("token-" + this.requestCount);
			accessToken.setExpireAt(System.currentTimeMillis() + 60_000L);
			return accessToken;
		}

		private int getRequestCount() {
			return this.requestCount;
		}

	}

}
