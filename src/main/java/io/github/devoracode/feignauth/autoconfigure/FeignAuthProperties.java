package io.github.devoracode.feignauth.autoconfigure;

import io.github.devoracode.feignauth.exception.FeignAuthConfigurationException;
import io.github.devoracode.feignauth.support.AuthType;
import lombok.Getter;
import lombok.Setter;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * feign-auth-spring-boot-starter 的配置属性。
 *
 * @author Wenjie Liu
 * @since 1.0.0
 */
@Getter
@Setter
@ConfigurationProperties(prefix = "feign")
public class FeignAuthProperties implements InitializingBean {

    private GlobalAuth auth = new GlobalAuth();

    private Map<String, Service> services = new HashMap<>();

    /**
     * 启动期校验数值类配置。越界的池上限会在建池时抛出与配置键无关的
     * IllegalArgumentException，而超时配成 0 或负值会被当作「无限等待」让取号永久挂起，
     * 两者都拖到运行期才暴露，故在此按配置键快速失败。
     */
    @Override
    public void afterPropertiesSet() {
        validateHttp(this.auth.getHttp());
        validateKeyPrefix(this.auth.getCache().getRedis());
        if (this.services != null) {
            this.services.forEach(FeignAuthProperties::validateService);
        }
    }

    private static void validateHttp(Http http) {
        requirePositiveDuration("feign.auth.http.connect-timeout", http.getConnectTimeout());
        requirePositiveDuration("feign.auth.http.connection-request-timeout", http.getConnectionRequestTimeout());
        requirePositiveDuration("feign.auth.http.socket-timeout", http.getSocketTimeout());
        requirePositive("feign.auth.http.max-total", http.getMaxTotal());
        requirePositive("feign.auth.http.default-max-per-route", http.getDefaultMaxPerRoute());
    }

    private static void validateKeyPrefix(Redis redis) {
        if (redis.getKeyPrefix() == null) {
            throw invalid("feign.auth.cache.redis.key-prefix", null, "must not be null");
        }
    }

    /**
     * 只校验数值边界；服务级 auth 缺失或 {@code type} 非法仍由请求期匹配阶段快速失败。
     */
    private static void validateService(String name, Service service) {
        if (service == null || service.getAuth() == null) {
            return;
        }
        Auth auth = service.getAuth();
        String keyPrefix = "feign.services." + name + ".auth.";
        if (auth.getExpireAheadSeconds() < 0L) {
            throw invalid(keyPrefix + "expire-ahead-seconds", auth.getExpireAheadSeconds(), "must not be negative");
        }
        Long tokenExpiresInSeconds = auth.getTokenExpiresInSeconds();
        if (tokenExpiresInSeconds != null && tokenExpiresInSeconds <= 0L) {
            throw invalid(keyPrefix + "token-expires-in-seconds", tokenExpiresInSeconds, "must be greater than 0");
        }
        List<Integer> statuses = auth.getExpiredTokenStatuses();
        if (statuses != null) {
            for (Integer status : statuses) {
                if (status == null || status < 100 || status > 599) {
                    throw invalid(keyPrefix + "expired-token-statuses", status,
                            "must be an HTTP status code between 100 and 599");
                }
            }
        }
    }

    // RequestConfig 把 0 解释为无限等待，负 Duration 又被 toMillis 钳成 0，故两者一律拒绝
    private static void requirePositiveDuration(String key, Duration timeout) {
        if (timeout == null || timeout.isZero() || timeout.isNegative()) {
            throw invalid(key, timeout, "must be a positive duration (0 means infinite wait)");
        }
    }

    private static void requirePositive(String key, int value) {
        if (value <= 0) {
            throw invalid(key, value, "must be greater than 0");
        }
    }

    private static FeignAuthConfigurationException invalid(String key, Object value, String requirement) {
        return new FeignAuthConfigurationException(
                "FeignAuth: invalid configuration " + key + "=" + value + ", " + requirement);
    }

    // 自定义 @Bean 时显式置空不该让下游 getAuth() 链路 NPE，统一回退默认值
    public void setAuth(GlobalAuth auth) {
        this.auth = auth == null ? new GlobalAuth() : auth;
    }

    @Getter
    @Setter
    public static class GlobalAuth {

        private Cache cache = new Cache();

        private Http http = new Http();

        private boolean failOnNoMatch = true;

        public void setCache(Cache cache) {
            this.cache = cache == null ? new Cache() : cache;
        }

        public void setHttp(Http http) {
            this.http = http == null ? new Http() : http;
        }

    }

    /**
     * OAuth2 令牌请求内置 Apache HttpClient 的连接池与超时配置。
     */
    @Getter
    @Setter
    public static class Http {

        private Duration connectTimeout = Duration.ofSeconds(5);

        private Duration connectionRequestTimeout = Duration.ofSeconds(5);

        private Duration socketTimeout = Duration.ofSeconds(10);

        private int maxTotal = 200;

        private int defaultMaxPerRoute = 50;

    }

    @Getter
    @Setter
    public static class Cache {

        /**
         * local | redis
         */
        private String provider = "local";

        private Redis redis = new Redis();

        public void setRedis(Redis redis) {
            this.redis = redis == null ? new Redis() : redis;
        }

    }

    @Getter
    @Setter
    public static class Redis {

        private String keyPrefix = "feign-auth:";

    }

    /**
     * 单个第三方服务的配置。
     */
    @Getter
    @Setter
    public static class Service {

        private String baseUrl;

        private Auth auth = new Auth();

    }

    /**
     * 服务的鉴权配置。
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
     * OAuth2 令牌请求载荷的自定义字段名。
     */
    @Getter
    @Setter
    public static class RequestFields {

        private String clientId;

        private String clientSecret;

        private String grantType;

    }

    /**
     * OAuth2 客户端凭证配置。
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
