package io.github.devoracode.feignauth.config;

import lombok.Data;
import org.apache.commons.lang3.StringUtils;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Configuration properties for feign-auth-spring-boot-starter.
 *
 * <p>All properties are bound from the {@code feign} prefix in the application
 * configuration file. Each entry in {@link #services} represents a distinct
 * third-party service with its own base URL and authentication configuration.
 *
 * <p>Example YAML configuration:
 * <pre>{@code
 * feign:
 *   services:
 *     order:
 *       base-url: https://api.example.com
 *       auth:
 *         type: oauth2
 *         token-url: https://api.example.com/oauth/token
 *         clients:
 *           - id: my-client
 *             secret: my-secret
 * }</pre>
 *
 * @author Wenjie Liu
 * @since 1.0.0
 */
@Data
@ConfigurationProperties(prefix = "feign")
public class FeignAuthProperties {

    /**
     * Map of named service configurations, keyed by a logical service name.
     *
     * <p>Each key is an arbitrary name used to identify the service within the starter.
     * The value holds the base URL and authentication details for that service.
     */
    private Map<String, ServiceConfig> services = new HashMap<>();

    /**
     * Configuration for a single third-party service.
     *
     * <p>A service is identified by its {@link #baseUrl}. The {@link #auth} block
     * defines how requests to this service should be authenticated.
     */
    @Data
    public static class ServiceConfig {

        /**
         * The base URL of the target service, e.g. {@code https://api.example.com}.
         *
         * <p>Trailing slashes are ignored when matching against Feign request targets.
         */
        private String baseUrl;

        /**
         * Authentication configuration for this service.
         *
         * <p>Defaults to a new empty {@link AuthConfig} instance.
         */
        private AuthConfig auth = new AuthConfig();
    }

    /**
     * Authentication configuration for a service.
     *
     * <p>Supports two authentication types:
     * <ul>
     *   <li>{@code oauth2} – fetches a token from a token endpoint and injects it
     *       into a configurable header on each request.</li>
     *   <li>{@code api-key} – injects a static API key value into a configurable header.</li>
     * </ul>
     */
    @Data
    public static class AuthConfig {

        /**
         * Authentication type. Accepted values are {@code oauth2} and {@code api-key}.
         */
        private String type;

        /**
         * Token endpoint URL. Required when {@link #type} is {@code oauth2}.
         */
        private String tokenUrl;

        /**
         * HTTP method used when requesting a token. Accepted values are {@code post} (default)
         * and {@code get}.
         */
        private String method = "post";

        /**
         * Name of the request header into which the OAuth2 token is injected.
         * Defaults to {@code x-token}.
         *
         * <p>Used only when {@link #type} is {@code oauth2}.
         */
        private String tokenHeader = "x-token";

        /**
         * Name of the request header into which the API key is injected.
         * Defaults to {@code Authorization}.
         *
         * <p>Used only when {@link #type} is {@code api-key}.
         */
        private String headerName = "Authorization";

        /**
         * Static API key value to inject. Required when {@link #type} is {@code api-key}.
         */
        private String value;

        /**
         * Number of seconds before actual token expiry at which the cached token should
         * be proactively refreshed. Defaults to {@code 60}.
         */
        private long expireAheadSeconds = 60;

        /**
         * JSON path expression used to extract the access token from the token response.
         *
         * <p>Supports dot-notation for nested fields, e.g. {@code data.accessToken} will
         * navigate to {@code root → "data" → "accessToken"}.  Only simple dot-separated
         * field names are supported; array indexing is not.
         *
         * <p>When this field is blank, the starter falls back to its built-in auto-detection
         * logic, which tries {@code access_token}, {@code accessToken}, and {@code token}
         * in that order.
         *
         * <p>Used only when {@link #type} is {@code oauth2}.
         */
        private String tokenField;

        /**
         * Custom field names used when building the token request body or query parameters.
         */
        private RequestFields requestFields = new RequestFields();

        /**
         * OAuth2 client credentials list. Each entry may optionally specify path prefixes
         * to scope its usage to particular request paths.
         *
         * <p>Used only when {@link #type} is {@code oauth2}.
         */
        private List<ClientConfig> clients = new ArrayList<>();

        /**
         * Path prefixes that restrict this API Key configuration to specific request paths.
         * When empty, this service acts as a fallback for all unmatched paths on the same
         * {@code base-url}.
         *
         * <p>Used only when {@link #type} is {@code api-key}.
         */
        private List<String> pathPrefixes = new ArrayList<>();

        /**
         * Returns {@code true} if the authentication type is {@code oauth2}
         * (case-insensitive comparison).
         *
         * @return {@code true} for OAuth2 authentication
         */
        public boolean isOAuth2() {
            return "oauth2".equalsIgnoreCase(type);
        }

        /**
         * Returns {@code true} if the authentication type is {@code api-key}
         * (case-insensitive comparison).
         *
         * @return {@code true} for API key authentication
         */
        public boolean isApiKey() {
            return "api-key".equalsIgnoreCase(type);
        }

        /**
         * Resolves the effective header name to use when injecting the credential.
         *
         * <ul>
         *   <li>For {@code api-key}: returns {@link #headerName} if non-blank,
         *       otherwise falls back to {@code Authorization}.</li>
         *   <li>For {@code oauth2}: returns {@link #tokenHeader} if non-blank,
         *       otherwise falls back to {@code x-token}.</li>
         * </ul>
         *
         * @return the resolved header name; never {@code null}
         */
        public String resolvedHeaderName() {
            if (isApiKey()) {
                return StringUtils.isNotBlank(headerName) ? headerName : "Authorization";
            }
            return StringUtils.isNotBlank(tokenHeader) ? tokenHeader : "x-token";
        }

        /**
         * Returns {@code true} if this authentication configuration acts as a fallback
         * for requests that do not match any more specific path prefix.
         *
         * <ul>
         *   <li>For {@code api-key}: a fallback service has no {@link #pathPrefixes}.</li>
         *   <li>For {@code oauth2}: a fallback service has at least one
         *       {@link ClientConfig} whose {@link ClientConfig#pathPrefixes} is empty.</li>
         * </ul>
         *
         * @return {@code true} if this configuration is a fallback
         */
        public boolean isFallback() {
            if (isApiKey()) {
                return pathPrefixes == null || pathPrefixes.isEmpty();
            }
            if (clients == null || clients.isEmpty()) {
                return false;
            }
            for (ClientConfig client : clients) {
                if (client != null && client.isDefaultClient()) {
                    return true;
                }
            }
            return false;
        }
    }

    /**
     * Custom field names for the token request payload.
     *
     * <p>Different OAuth2 servers may use non-standard field names. This class
     * allows the caller to map the logical field roles to whatever names the
     * target server expects.
     *
     * <p>All fields default to {@code null}. When {@code null}, they are not
     * included in the token request, allowing services that do not accept certain
     * parameters (e.g., {@code grant_type}) to work correctly.
     */
    @Data
    public static class RequestFields {

        /**
         * Field name for the client ID in the token request.
         * When {@code null}, defaults to {@code clientId} at request-build time.
         */
        private String clientId;

        /**
         * Field name for the client secret in the token request.
         * When {@code null}, defaults to {@code clientSecret} at request-build time.
         */
        private String clientSecret;

        /**
         * Field name for the grant type in the token request.
         * When {@code null}, the grant type parameter is omitted from the request.
         */
        private String grantType;
    }

    /**
     * OAuth2 client credential configuration.
     *
     * <p>Each entry corresponds to one set of OAuth2 client credentials. Path prefixes
     * can be used to restrict a client to specific API paths within the same service.
     * A client without any path prefix is treated as the default (fallback) client.
     */
    @Data
    public static class ClientConfig {

        /**
         * OAuth2 client ID.
         */
        private String id;

        /**
         * OAuth2 client secret.
         */
        private String secret;

        /**
         * OAuth2 grant type value sent in the token request.
         * When {@code null}, the grant type parameter is omitted from the request.
         * Set explicitly (e.g. {@code client_credentials}) when the token endpoint
         * requires it.
         */
        private String grantType;

        /**
         * Request path prefixes that this client should be selected for.
         * When empty, this client is treated as the default client for its service.
         */
        private List<String> pathPrefixes = new ArrayList<>();

        /**
         * Returns {@code true} if this client has no path prefixes configured,
         * making it the default (fallback) client for the service.
         *
         * @return {@code true} if this is the default client
         */
        public boolean isDefaultClient() {
            return pathPrefixes == null || pathPrefixes.isEmpty();
        }
    }
}
