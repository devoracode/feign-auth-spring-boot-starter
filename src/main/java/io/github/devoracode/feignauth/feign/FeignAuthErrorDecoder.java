package io.github.devoracode.feignauth.feign;

import feign.Response;
import feign.codec.ErrorDecoder;
import io.github.devoracode.feignauth.oauth2.TokenFetcher;
import org.springframework.util.Assert;

/**
 * Feign {@link ErrorDecoder} that evicts expired OAuth2 tokens and classifies common remote errors.
 *
 * @author Wenjie Liu
 * @since 1.4.0
 */
public class FeignAuthErrorDecoder implements ErrorDecoder {

	private final FeignAuthStatusHandler statusHandler;

	private final ErrorDecoder defaultErrorDecoder = new Default();

	public FeignAuthErrorDecoder(ServiceMatcher serviceMatcher, TokenFetcher tokenFetcher) {
		Assert.notNull(serviceMatcher, "serviceMatcher must not be null");
		Assert.notNull(tokenFetcher, "tokenFetcher must not be null");
		this.statusHandler = new FeignAuthStatusHandler(serviceMatcher, tokenFetcher);
	}

	@Override
	public Exception decode(String methodKey, Response response) {
		if (response == null || response.request() == null) {
			return this.defaultErrorDecoder.decode(methodKey, response);
		}
		Exception handled = this.statusHandler.handle(methodKey, response, response.status(), false);
		if (handled != null) {
			return handled;
		}
		return this.defaultErrorDecoder.decode(methodKey, response);
	}

}
