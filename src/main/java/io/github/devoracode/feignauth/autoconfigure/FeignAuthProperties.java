package io.github.devoracode.feignauth.autoconfigure;

import io.github.devoracode.feignauth.support.AuthType;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Configuration properties for feign-auth-spring-boot-starter.
 *
 * @author Wenjie Liu
 * @since 1.0.0
 */
@ConfigurationProperties(prefix = "feign")
public class FeignAuthProperties {

	private GlobalAuth auth = new GlobalAuth();

	private Map<String, Service> services = new HashMap<>();

	public GlobalAuth getAuth() {
		return auth;
	}

	public void setAuth(GlobalAuth auth) {
		this.auth = auth;
	}

	public Map<String, Service> getServices() {
		return this.services;
	}

	public void setServices(Map<String, Service> services) {
		this.services = services;
	}

	public static class GlobalAuth {

		private Cache cache = new Cache();

		public Cache getCache() {
			return cache;
		}

		public void setCache(Cache cache) {
			this.cache = cache;
		}
	}

	public static class Cache {

		/**
		 * local | redis
		 */
		private String provider = "local";

		private Redis redis = new Redis();

		public String getProvider() {
			return provider;
		}

		public void setProvider(String provider) {
			this.provider = provider;
		}

		public Redis getRedis() {
			return redis;
		}

		public void setRedis(Redis redis) {
			this.redis = redis;
		}
	}

	public static class Redis {

		private String keyPrefix = "feign-auth:";

		public String getKeyPrefix() {
			return keyPrefix;
		}

		public void setKeyPrefix(String keyPrefix) {
			this.keyPrefix = keyPrefix;
		}
	}

	/**
	 * Configuration for a single third-party service.
	 */
	public static class Service {

		private String baseUrl;

		private Auth auth = new Auth();

		public String getBaseUrl() {
			return this.baseUrl;
		}

		public void setBaseUrl(String baseUrl) {
			this.baseUrl = baseUrl;
		}

		public Auth getAuth() {
			return this.auth;
		}

		public void setAuth(Auth auth) {
			this.auth = auth;
		}

	}

	/**
	 * Authentication configuration for a service.
	 */
	public static class Auth {

		private String type;

		private String tokenUrl;

		private String method = "post";

		private String tokenHeader = "x-token";

		private String headerName = "Authorization";

		private String tokenPrefix;

		private String value;

		private long expireAheadSeconds = 60;

		private String tokenField;

		private Long tokenExpiresInSeconds;

		private RequestFields requestFields = new RequestFields();

		private List<Client> clients = new ArrayList<>();

		private List<String> pathPrefixes = new ArrayList<>();

		private Map<String, String> tokenRequestHeaders = new HashMap<>();

		private Map<String, String> requestHeaders = new HashMap<>();

		private List<Integer> expiredTokenStatuses = new ArrayList<>();

		private String responseStatusField;

		public String getResponseStatusField() {
			return this.responseStatusField;
		}

		public void setResponseStatusField(String responseStatusField) {
			this.responseStatusField = responseStatusField;
		}

		public List<Integer> getExpiredTokenStatuses() {
			return this.expiredTokenStatuses;
		}

		public void setExpiredTokenStatuses(List<Integer> expiredTokenStatuses) {
			this.expiredTokenStatuses = expiredTokenStatuses;
		}

		public String getType() {
			return this.type;
		}

		public void setType(String type) {
			this.type = type;
		}

		public String getTokenUrl() {
			return this.tokenUrl;
		}

		public void setTokenUrl(String tokenUrl) {
			this.tokenUrl = tokenUrl;
		}

		public String getMethod() {
			return this.method;
		}

		public void setMethod(String method) {
			this.method = method;
		}

		public String getTokenHeader() {
			return this.tokenHeader;
		}

		public void setTokenHeader(String tokenHeader) {
			this.tokenHeader = tokenHeader;
		}

		public String getHeaderName() {
			return this.headerName;
		}

		public void setHeaderName(String headerName) {
			this.headerName = headerName;
		}

		public String getTokenPrefix() {
			return this.tokenPrefix;
		}

		public void setTokenPrefix(String tokenPrefix) {
			this.tokenPrefix = tokenPrefix;
		}

		public String getValue() {
			return this.value;
		}

		public void setValue(String value) {
			this.value = value;
		}

		public long getExpireAheadSeconds() {
			return this.expireAheadSeconds;
		}

		public void setExpireAheadSeconds(long expireAheadSeconds) {
			this.expireAheadSeconds = expireAheadSeconds;
		}

		public String getTokenField() {
			return this.tokenField;
		}

		public void setTokenField(String tokenField) {
			this.tokenField = tokenField;
		}

		public Long getTokenExpiresInSeconds() {
			return this.tokenExpiresInSeconds;
		}

		public void setTokenExpiresInSeconds(Long tokenExpiresInSeconds) {
			this.tokenExpiresInSeconds = tokenExpiresInSeconds;
		}

		public RequestFields getRequestFields() {
			return this.requestFields;
		}

		public void setRequestFields(RequestFields requestFields) {
			this.requestFields = requestFields;
		}

		public List<Client> getClients() {
			return this.clients;
		}

		public void setClients(List<Client> clients) {
			this.clients = clients;
		}

		public List<String> getPathPrefixes() {
			return this.pathPrefixes;
		}

		public void setPathPrefixes(List<String> pathPrefixes) {
			this.pathPrefixes = pathPrefixes;
		}

		public Map<String, String> getTokenRequestHeaders() {
			return tokenRequestHeaders;
		}

		public void setTokenRequestHeaders(Map<String, String> tokenRequestHeaders) {
			this.tokenRequestHeaders = tokenRequestHeaders;
		}

		public Map<String, String> getRequestHeaders() {
			return requestHeaders;
		}

		public void setRequestHeaders(Map<String, String> requestHeaders) {
			this.requestHeaders = requestHeaders;
		}

		public boolean isOAuth2() {
			return AuthType.OAUTH2 == AuthType.from(this.type);
		}

		public boolean isApiKey() {
			return AuthType.API_KEY == AuthType.from(this.type);
		}

		public String resolveHeaderName() {
			if (isApiKey()) {
				return StringUtils.hasText(this.headerName) ? this.headerName : "Authorization";
			}
			return StringUtils.hasText(this.tokenHeader) ? this.tokenHeader : "x-token";
		}

		public boolean isFallback() {
			if (isApiKey()) {
				return this.pathPrefixes == null || this.pathPrefixes.isEmpty();
			}
			if (this.clients == null || this.clients.isEmpty()) {
				return false;
			}
			for (Client client : this.clients) {
				if (client != null && client.isDefaultClient()) {
					return true;
				}
			}
			return false;
		}

		public boolean isExpiredTokenStatus(int status) {
			if (this.expiredTokenStatuses != null && !this.expiredTokenStatuses.isEmpty()) {
				return this.expiredTokenStatuses.contains(status);
			}
			return status == 401 || status == 421 || status == 423;
		}

	}

	/**
	 * Custom field names for the OAuth2 token request payload.
	 */
	public static class RequestFields {

		private String clientId;

		private String clientSecret;

		private String grantType;

		public String getClientId() {
			return this.clientId;
		}

		public void setClientId(String clientId) {
			this.clientId = clientId;
		}

		public String getClientSecret() {
			return this.clientSecret;
		}

		public void setClientSecret(String clientSecret) {
			this.clientSecret = clientSecret;
		}

		public String getGrantType() {
			return this.grantType;
		}

		public void setGrantType(String grantType) {
			this.grantType = grantType;
		}

	}

	/**
	 * OAuth2 client credential configuration.
	 */
	public static class Client {

		private String id;

		private String secret;

		private String grantType;

		private List<String> pathPrefixes = new ArrayList<>();

		public String getId() {
			return this.id;
		}

		public void setId(String id) {
			this.id = id;
		}

		public String getSecret() {
			return this.secret;
		}

		public void setSecret(String secret) {
			this.secret = secret;
		}

		public String getGrantType() {
			return this.grantType;
		}

		public void setGrantType(String grantType) {
			this.grantType = grantType;
		}

		public List<String> getPathPrefixes() {
			return this.pathPrefixes;
		}

		public void setPathPrefixes(List<String> pathPrefixes) {
			this.pathPrefixes = pathPrefixes;
		}

		public boolean isDefaultClient() {
			return this.pathPrefixes == null || this.pathPrefixes.isEmpty();
		}

	}

}
