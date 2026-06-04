package io.github.devoracode.feignauth.feign;

import com.fasterxml.jackson.databind.ObjectMapper;
import feign.Retryer;
import feign.RequestInterceptor;
import feign.codec.Decoder;
import feign.codec.ErrorDecoder;
import io.github.devoracode.feignauth.oauth2.TokenFetcher;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.Assert;

import java.util.concurrent.TimeUnit;

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

	private final ObjectMapper objectMapper;

	public FeignClientConfig(ServiceMatcher serviceMatcher, TokenFetcher tokenFetcher, ObjectMapper objectMapper) {
		Assert.notNull(serviceMatcher, "serviceMatcher must not be null");
		Assert.notNull(tokenFetcher, "tokenFetcher must not be null");
		Assert.notNull(objectMapper, "objectMapper must not be null");
		this.serviceMatcher = serviceMatcher;
		this.tokenFetcher = tokenFetcher;
		this.objectMapper = objectMapper;
	}

	@Bean
	public RequestInterceptor feignAuthRequestInterceptor() {
		return new FeignAuthRequestInterceptor(this.serviceMatcher, this.tokenFetcher);
	}

	@Bean
	public ErrorDecoder feignAuthErrorDecoder() {
		return new FeignAuthErrorDecoder(this.serviceMatcher, this.tokenFetcher);
	}

	@Bean
	public Decoder feignAuthDecoder(ObjectProvider<Decoder> decoderProvider) {
		Decoder delegate = decoderProvider.getIfAvailable();
		FeignAuthStatusHandler statusHandler = new FeignAuthStatusHandler(this.serviceMatcher, this.tokenFetcher);
		return new FeignAuthDecoder(
				delegate != null ? delegate : new Decoder.Default(),
				this.objectMapper,
				statusHandler);
	}

	@Bean
	public Retryer feignAuthRetryer() {
		return new Retryer.Default(100, TimeUnit.SECONDS.toMillis(1), 2);
	}

}
