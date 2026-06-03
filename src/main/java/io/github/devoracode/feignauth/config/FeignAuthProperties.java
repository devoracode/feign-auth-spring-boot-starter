package io.github.devoracode.feignauth.config;

import lombok.Data;
import org.apache.commons.lang3.StringUtils;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Data
@ConfigurationProperties(prefix = "feign")
public class FeignAuthProperties {

    private Map<String, ServiceConfig> services = new HashMap<>();

    @Data
    public static class ServiceConfig {

        private String baseUrl;
        private AuthConfig auth = new AuthConfig();
    }

    @Data
    public static class AuthConfig {

        private String type;
        private String tokenUrl;
        private String method = "post";
        private String tokenHeader = "x-token";
        private String headerName = "Authorization";
        private String value;
        private long expireAheadSeconds = 60;
        private RequestFields requestFields = new RequestFields();
        private List<ClientConfig> clients = new ArrayList<>();
        private List<String> pathPrefixes = new ArrayList<>();

        public boolean isOAuth2() {
            return "oauth2".equalsIgnoreCase(type);
        }

        public boolean isApiKey() {
            return "api-key".equalsIgnoreCase(type);
        }

        public String resolvedHeaderName() {
            if (isApiKey()) {
                return StringUtils.isNotBlank(headerName) ? headerName : "Authorization";
            }
            return StringUtils.isNotBlank(tokenHeader) ? tokenHeader : "x-token";
        }

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

    @Data
    public static class RequestFields {

        private String clientId = "clientId";
        private String clientSecret = "clientSecret";
        private String grantType = "grantType";
    }

    @Data
    public static class ClientConfig {

        private String id;
        private String secret;
        private String grantType = "client_credentials";
        private List<String> pathPrefixes = new ArrayList<>();

        public boolean isDefaultClient() {
            return pathPrefixes == null || pathPrefixes.isEmpty();
        }
    }
}
