package io.github.devoracode.feignauth.feign;

import feign.RequestInterceptor;
import io.github.devoracode.feignauth.oauth2.TokenFetcher;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.Assert;

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
 * @author Wenjie Liu
 * @since 1.0.0
 */
@Configuration(proxyBeanMethods = false)
public class FeignClientConfig {

	private final ServiceMatcher serviceMatcher;

	private final TokenFetcher tokenFetcher;

	public FeignClientConfig(ServiceMatcher serviceMatcher, TokenFetcher tokenFetcher) {
		Assert.notNull(serviceMatcher, "serviceMatcher must not be null");
		Assert.notNull(tokenFetcher, "tokenFetcher must not be null");
		this.serviceMatcher = serviceMatcher;
		this.tokenFetcher = tokenFetcher;
	}

	@Bean
	public RequestInterceptor feignAuthRequestInterceptor() {
		return new FeignAuthRequestInterceptor(this.serviceMatcher, this.tokenFetcher);
	}

}
