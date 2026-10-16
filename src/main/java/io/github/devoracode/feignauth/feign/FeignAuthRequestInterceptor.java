package io.github.devoracode.feignauth.feign;

import feign.RequestInterceptor;
import feign.RequestTemplate;
import io.github.devoracode.feignauth.autoconfigure.FeignAuthProperties;
import io.github.devoracode.feignauth.exception.FeignAuthConfigurationException;
import io.github.devoracode.feignauth.exception.FeignAuthTokenException;
import io.github.devoracode.feignauth.header.HeaderManager;
import io.github.devoracode.feignauth.oauth2.TokenFetcher;
import io.github.devoracode.feignauth.support.PathUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.springframework.util.Assert;

import java.net.URI;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 向每个发出的 Feign 业务请求注入认证 header 及额外自定义 header 的
 * Feign {@link RequestInterceptor}。
 *
 * @author Wenjie Liu
 * @since 1.0.0
 */
public class FeignAuthRequestInterceptor implements RequestInterceptor {

    private static final Log logger = LogFactory.getLog(FeignAuthRequestInterceptor.class);

    private final ServiceMatcher serviceMatcher;

    private final TokenFetcher tokenFetcher;

    private final HeaderManager headerManager;

    private final FeignAuthProperties properties;

    public FeignAuthRequestInterceptor(ServiceMatcher serviceMatcher, TokenFetcher tokenFetcher,
                                       HeaderManager headerManager, FeignAuthProperties properties) {
        Assert.notNull(serviceMatcher, "serviceMatcher must not be null");
        Assert.notNull(headerManager, "headerManager must not be null");
        Assert.notNull(properties, "properties must not be null");
        this.serviceMatcher = serviceMatcher;
        this.tokenFetcher = tokenFetcher;
        this.headerManager = headerManager;
        this.properties = properties;
    }

    @Override
    public void apply(RequestTemplate template) {
        if (template == null || template.feignTarget() == null) {
            return;
        }

        String targetUrl = template.feignTarget().url();
        String baseUrl;
        String targetPathPrefix;
        try {
            URI uri = URI.create(targetUrl);
            String path = StringUtils.isNotBlank(uri.getRawPath()) ? uri.getRawPath() : "";
            baseUrl = uri.getScheme() + "://" + uri.getAuthority();
            targetPathPrefix = StringUtils.stripEnd(path, "/");
        } catch (Exception ex) {
            baseUrl = targetUrl;
            targetPathPrefix = "";
        }

        // 必须与 FeignAuthStatusHandler 侧收敛到同一个路径字符串：Feign 自己拼接 target 前缀与
        // 方法路径时只去掉一个末尾斜杠、也不折叠 //，直接比较会得到两个不同的路径，
        // 于是 4xx 时匹配不到同一服务，既不驱逐缓存也不重放
        String methodPath = PathUtils.normalizePath(template.path());
        String requestPath = PathUtils.normalizeRequestPath(
            StringUtils.isNotBlank(targetPathPrefix) ? targetPathPrefix + methodPath : methodPath);

        ResolvedService resolved = this.serviceMatcher.match(baseUrl, requestPath);
        if (resolved == null) {
            if (this.properties.getAuth().isFailOnNoMatch()) {
                throw new FeignAuthConfigurationException(
                    "FeignAuth: no service config matched for baseUrl=" + baseUrl + ", path=" + requestPath
                        + "; configured services: " + describeConfiguredServices()
                        + "; the Feign target URL must point to the same scheme/host/port as"
                        + " feign.services.<name>.base-url (default ports 80/443 are normalized, a base-url path is"
                        + " treated as an outer prefix for path matching)"
                        + "; set feign.auth.fail-on-no-match=false to send unmatched requests without auth headers");
            }
            if (logger.isWarnEnabled()) {
                logger.warn("FeignAuth: no service config matched for baseUrl=" + baseUrl + ", path=" + requestPath
                    + " — request will be sent without auth header");
            }
            return;
        }

        FeignAuthProperties.Auth auth = resolved.getService().getAuth();
        // ServiceMatcher 已拒绝非法 auth，此处仅作防御
        if (auth == null) {
            throw new FeignAuthConfigurationException("FeignAuth: service '" + resolved.getServiceName()
                + "' has no auth config — fix feign.services." + resolved.getServiceName() + ".auth");
        }

        if (auth.isApiKey()) {
            applyApiKey(template, resolved, auth, requestPath);
        } else if (auth.isOAuth2()) {
            applyOAuth2(template, resolved, auth, requestPath);
        } else {
            throw new FeignAuthConfigurationException("FeignAuth: service '" + resolved.getServiceName()
                + "' has unsupported auth.type '" + auth.getType()
                + "', expected 'oauth2' or 'api-key'");
        }

        this.headerManager.applyRequestHeaders(
            resolved.getServiceName(), resolved.getService(), requestPath, template);
    }

    /**
     * 解析应当处理给定请求的配置服务。
     *
     * @param baseUrl     Feign target 的基础 URL
     * @param requestPath 归一化后的请求路径
     * @return 解析得到的服务；若无匹配服务则返回 {@code null}
     */
    public ResolvedService matchService(String baseUrl, String requestPath) {
        return this.serviceMatcher.match(baseUrl, requestPath);
    }

    private void applyApiKey(RequestTemplate template, ResolvedService resolved, FeignAuthProperties.Auth auth,
                             String requestPath) {
        if (StringUtils.isBlank(auth.getValue())) {
            throw new FeignAuthConfigurationException(
                "API key value is required for service: " + resolved.getServiceName());
        }
        String headerName = auth.resolveHeaderName();
        String headerValue = prefix(auth) + auth.getValue();
        setHeader(template, headerName, headerValue);
        if (logger.isDebugEnabled()) {
            logger.debug("FeignAuth [api-key] service='" + resolved.getServiceName() + "' header='" + headerName
                + "' path=" + requestPath);
        }
    }

    private void applyOAuth2(RequestTemplate template, ResolvedService resolved, FeignAuthProperties.Auth auth,
                             String requestPath) {
        Assert.notNull(this.tokenFetcher, "tokenFetcher must not be null for OAuth2 services");
        String token;
        try {
            token = this.tokenFetcher.getToken(resolved.getServiceName(), requestPath);
        } catch (Exception ex) {
            String errorMessage = "FeignAuth [oauth2] failed to obtain token for service='"
                + resolved.getServiceName() + "', path=" + requestPath;
            if (logger.isErrorEnabled()) {
                logger.error(errorMessage + ": " + ex.getMessage(), ex);
            }
            throw new FeignAuthTokenException(errorMessage, ex);
        }
        String headerName = auth.resolveHeaderName();
        String headerValue = prefix(auth) + token;
        setHeader(template, headerName, headerValue);
        if (logger.isDebugEnabled()) {
            logger.debug("FeignAuth [oauth2] service='" + resolved.getServiceName() + "' header='" + headerName
                + "' path=" + requestPath);
        }
    }

    private static String prefix(FeignAuthProperties.Auth auth) {
        String prefix = auth.getTokenPrefix();
        return StringUtils.defaultString(prefix);
    }

    // header() 是追加语义，且 Feign 重试时会在同一个 template 上重新执行拦截器，因此先清除同名 header。
    private static void setHeader(RequestTemplate template, String name, String value) {
        template.header(name);
        template.header(name, value);
    }

    private String describeConfiguredServices() {
        Map<String, FeignAuthProperties.Service> services = this.properties.getServices();
        if (services == null || services.isEmpty()) {
            return "<none configured>";
        }
        return services.entrySet().stream()
            .map(entry -> entry.getKey() + " -> "
                + (entry.getValue() == null ? "<empty>" : entry.getValue().getBaseUrl()))
            .collect(Collectors.joining(", ", "[", "]"));
    }

}
