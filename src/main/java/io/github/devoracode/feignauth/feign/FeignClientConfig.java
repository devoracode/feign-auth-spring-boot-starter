package io.github.devoracode.feignauth.feign;

import feign.RequestInterceptor;
import feign.Retryer;
import feign.codec.ErrorDecoder;
import io.github.devoracode.feignauth.oauth2.TokenFetcher;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.Assert;

import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Feign client configuration that registers the authentication request interceptor,
 * error decoder, and retryer.
 *
 * <p>Referenced via {@code @FeignClient(configuration = FeignClientConfig.class)}.
 *
 * @author Wenjie Liu
 * @since 1.0.0
 */
@Configuration(proxyBeanMethods = false)
public class FeignClientConfig {

	private final ServiceMatcher serviceMatcher;

	private final TokenFetcher tokenFetcher;

	private final List<FeignHeaderInjector> headerInjectors;

	public FeignClientConfig(ServiceMatcher serviceMatcher, TokenFetcher tokenFetcher,
	                         ObjectProvider<List<FeignHeaderInjector>> headerInjectorsProvider) {
		Assert.notNull(serviceMatcher, "serviceMatcher must not be null");
		Assert.notNull(tokenFetcher, "tokenFetcher must not be null");
		this.serviceMatcher = serviceMatcher;
		this.tokenFetcher = tokenFetcher;
		this.headerInjectors = headerInjectorsProvider.getIfAvailable(Collections::emptyList);
	}

	@Bean
	public RequestInterceptor feignAuthRequestInterceptor() {
		return new FeignAuthRequestInterceptor(this.serviceMatcher, this.tokenFetcher, headerInjectors);
	}

	@Bean
	public ErrorDecoder feignAuthErrorDecoder() {
		return new FeignAuthErrorDecoder(this.serviceMatcher, this.tokenFetcher);
	}

	@Bean
	public Retryer feignAuthRetryer() {
		return new Retryer.Default(100, TimeUnit.SECONDS.toMillis(1), 2);
	}

}
