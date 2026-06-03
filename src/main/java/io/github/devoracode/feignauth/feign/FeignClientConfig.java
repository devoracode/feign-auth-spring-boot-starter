package io.github.devoracode.feignauth.feign;

import feign.RequestInterceptor;
import io.github.devoracode.feignauth.config.FeignAuthProperties;
import io.github.devoracode.feignauth.token.TokenFetcher;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Feign client configuration that registers the authentication request interceptor.
 *
 * <p>This class should be referenced on each {@code @FeignClient} via the
 * {@code configuration} attribute:
 *
 * <pre>
 * &#64;FeignClient(
 *     name = "order-client",
 *     url  = "${feign.services.order.base-url}",
 *     configuration = FeignClientConfig.class
 * )
 * public interface OrderFeignClient { ... }
 * </pre>
 *
 * <p>When the interceptor is invoked for a request, it:
 * <ol>
 *   <li>Extracts the target base URL and request path from the Feign request template.</li>
 *   <li>Looks up the matching service and auth configuration.</li>
 *   <li>For {@code api-key} services, injects the static key directly.</li>
 *   <li>For {@code oauth2} services, retrieves (or caches) a token via
 *       {@link TokenFetcher#getToken} and injects it into the configured header.</li>
 * </ol>
 *
 * @author Wenjie Liu
 * @since 1.0.0
 * @see FeignAuthRequestInterceptor
 * @see FeignAuthProperties
 */
@Configuration
@RequiredArgsConstructor
public class FeignClientConfig {

    private final FeignAuthProperties feignAuthProperties;
    private final TokenFetcher tokenFetcher;

    /**
     * Creates and registers the {@link FeignAuthRequestInterceptor} as a Spring bean.
     *
     * @return a new {@link RequestInterceptor} that handles auth header injection
     */
    @Bean
    public RequestInterceptor feignAuthRequestInterceptor() {
        return new FeignAuthRequestInterceptor(feignAuthProperties, tokenFetcher);
    }
}
