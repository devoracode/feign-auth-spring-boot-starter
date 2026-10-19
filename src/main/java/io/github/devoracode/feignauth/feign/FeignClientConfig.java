package io.github.devoracode.feignauth.feign;

import com.fasterxml.jackson.databind.ObjectMapper;
import feign.RequestInterceptor;
import feign.Retryer;
import feign.codec.ErrorDecoder;
import io.github.devoracode.feignauth.autoconfigure.FeignAuthProperties;
import io.github.devoracode.feignauth.header.HeaderManager;
import io.github.devoracode.feignauth.oauth2.TokenFetcher;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.Assert;

import java.util.concurrent.TimeUnit;

/**
 * 注册认证请求拦截器、错误解码器与重试器的 Feign 客户端配置。
 *
 * @author Wenjie Liu
 * @since 1.0.0
 */
@Configuration(proxyBeanMethods = false)
public class FeignClientConfig {

	private final ServiceMatcher serviceMatcher;

	private final ObjectProvider<TokenFetcher> tokenFetcherProvider;

	private final HeaderManager headerManager;

	private final ObjectMapper objectMapper;

	private final FeignAuthProperties properties;

	public FeignClientConfig(ServiceMatcher serviceMatcher, ObjectProvider<TokenFetcher> tokenFetcherProvider,
	                         HeaderManager headerManager, ObjectMapper objectMapper,
	                         FeignAuthProperties properties) {
		Assert.notNull(serviceMatcher, "serviceMatcher must not be null");
		Assert.notNull(headerManager, "headerManager must not be null");
		Assert.notNull(objectMapper, "objectMapper must not be null");
		Assert.notNull(properties, "properties must not be null");
		this.serviceMatcher = serviceMatcher;
		// 只持有 ObjectProvider：构造这个配置类时不该顺带解析 TokenFetcher，留给各 @Bean 方法按需取
		this.tokenFetcherProvider = tokenFetcherProvider;
		this.headerManager = headerManager;
		this.objectMapper = objectMapper;
		this.properties = properties;
	}

	@Bean
	public RequestInterceptor feignAuthRequestInterceptor() {
		return new FeignAuthRequestInterceptor(this.serviceMatcher, this.tokenFetcherProvider.getIfAvailable(),
			this.headerManager, this.properties);
	}

	@Bean
	public ErrorDecoder feignAuthErrorDecoder() {
		return new FeignAuthErrorDecoder(this.serviceMatcher, this.tokenFetcherProvider.getIfAvailable(),
			this.objectMapper);
	}

	@Bean
	@ConditionalOnMissingBean(Retryer.class)
	public Retryer feignAuthRetryer() {
		return new Retryer.Default(100, TimeUnit.SECONDS.toMillis(1), 2);
	}

}
