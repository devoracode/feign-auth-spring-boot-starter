package io.github.devoracode.feignauth.autoconfigure;

import io.github.devoracode.feignauth.support.AuthType;
import lombok.Getter;
import lombok.Setter;
import org.apache.commons.lang3.StringUtils;
import org.springframework.boot.context.properties.ConfigurationProperties;

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
@Getter
@Setter
@ConfigurationProperties(prefix = "feign")
public class FeignAuthProperties {

    private GlobalAuth auth = new GlobalAuth();

    private Map<String, Service> services = new HashMap<>();

    @Getter
    @Setter
    public static class GlobalAuth {

        private Cache cache = new Cache();

        private boolean failOnNoMatch = true;

    }

    @Getter
    @Setter
    public static class Cache {

        /**
         * local | redis
         */
        private String provider = "local";

        private Redis redis = new Redis();

    }

    @Getter
    @Setter
    public static class Redis {

        private String keyPrefix = "feign-auth:";

    }

    /**
     * Configuration for a single third-party service.
     */
    @Getter
    @Setter
    public static class Service {

        private String baseUrl;

        private Auth auth = new Auth();

    }

    /**
     * Authentication configuration for a service.
     */
    @Getter
    @Setter
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

        public boolean isOAuth2() {
            return AuthType.OAUTH2 == AuthType.from(this.type);
        }

        public boolean isApiKey() {
            return AuthType.API_KEY == AuthType.from(this.type);
        }

        public String resolveHeaderName() {
            if (isApiKey()) {
                return StringUtils.defaultIfBlank(this.headerName, "Authorization");
            }
            return StringUtils.defaultIfBlank(this.tokenHeader, "x-token");
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
    @Getter
    @Setter
    public static class RequestFields {

        private String clientId;

        private String clientSecret;

        private String grantType;

    }

    /**
     * OAuth2 client credential configuration.
     */
    @Getter
    @Setter
    public static class Client {

        private String id;

        private String secret;

        private String grantType;

        private List<String> pathPrefixes = new ArrayList<>();

        public boolean isDefaultClient() {
            return this.pathPrefixes == null || this.pathPrefixes.isEmpty();
        }

    }

}
