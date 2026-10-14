package io.github.devoracode.feignauth.feign;

import com.fasterxml.jackson.databind.ObjectMapper;
import feign.Response;
import feign.codec.ErrorDecoder;
import io.github.devoracode.feignauth.oauth2.TokenFetcher;
import org.springframework.util.Assert;

/**
 * 淘汰过期 OAuth2 令牌并对常见远程错误进行分类的 Feign {@link ErrorDecoder}。
 *
 * @author Wenjie Liu
 * @since 1.4.0
 */
public class FeignAuthErrorDecoder implements ErrorDecoder {

    private final FeignAuthStatusHandler statusHandler;

    private final ErrorDecoder defaultErrorDecoder = new Default();

    public FeignAuthErrorDecoder(ServiceMatcher serviceMatcher, TokenFetcher tokenFetcher, ObjectMapper objectMapper) {
        Assert.notNull(serviceMatcher, "serviceMatcher must not be null");
        Assert.notNull(tokenFetcher, "tokenFetcher must not be null");
        Assert.notNull(objectMapper, "objectMapper must not be null");
        this.statusHandler = new FeignAuthStatusHandler(serviceMatcher, tokenFetcher, objectMapper);
    }

    @Override
    public Exception decode(String methodKey, Response response) {
        if (response == null || response.request() == null) {
            return this.defaultErrorDecoder.decode(methodKey, response);
        }
        Exception handled = this.statusHandler.handle(methodKey, response);
        if (handled != null) {
            return handled;
        }
        return this.defaultErrorDecoder.decode(methodKey, response);
    }

}
