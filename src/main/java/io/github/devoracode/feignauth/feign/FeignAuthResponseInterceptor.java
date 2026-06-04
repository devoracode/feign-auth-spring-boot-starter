package io.github.devoracode.feignauth.feign;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import feign.InvocationContext;
import feign.Response;
import feign.ResponseInterceptor;
import io.github.devoracode.feignauth.exception.FeignAuthTokenException;
import io.github.devoracode.feignauth.oauth2.TokenFetcher;
import org.springframework.util.Assert;
import org.springframework.util.StreamUtils;

import java.io.IOException;

/**
 * Feign {@link ResponseInterceptor} that checks business status embedded in successful JSON bodies.
 *
 * @author Wenjie Liu
 * @since 1.4.0
 */
public class FeignAuthResponseInterceptor implements ResponseInterceptor {

	private final ObjectMapper objectMapper;

	private final FeignAuthStatusHandler statusHandler;

	public FeignAuthResponseInterceptor(ServiceMatcher serviceMatcher, TokenFetcher tokenFetcher, ObjectMapper objectMapper) {
		Assert.notNull(serviceMatcher, "serviceMatcher must not be null");
		Assert.notNull(tokenFetcher, "tokenFetcher must not be null");
		Assert.notNull(objectMapper, "objectMapper must not be null");
		this.objectMapper = objectMapper;
		this.statusHandler = new FeignAuthStatusHandler(serviceMatcher, tokenFetcher);
	}

	@Override
	public Object aroundDecode(InvocationContext invocationContext) throws IOException {
		Response response = invocationContext.response();
		if (response == null || response.status() < 200 || response.status() >= 300 || response.body() == null) {
			return invocationContext.proceed();
		}

		Response replayable = toReplayableResponse(response);
		Integer businessStatus = extractBusinessStatus(replayable);
		Exception handled = this.statusHandler.handle("business-status", replayable, businessStatus, true);
		if (handled instanceof RuntimeException) {
			throw (RuntimeException) handled;
		}
		if (handled != null) {
			throw new FeignAuthTokenException(handled.getMessage(), handled);
		}
		return invocationContext.decoder().decode(replayable, invocationContext.returnType());
	}

	Response inspect(Response response) throws IOException {
		Response replayable = toReplayableResponse(response);
		Exception handled = this.statusHandler.handle("business-status", replayable, extractBusinessStatus(replayable), true);
		if (handled instanceof RuntimeException) {
			throw (RuntimeException) handled;
		}
		if (handled != null) {
			throw new FeignAuthTokenException(handled.getMessage(), handled);
		}
		return replayable;
	}

	private Response toReplayableResponse(Response response) throws IOException {
		byte[] body = StreamUtils.copyToByteArray(response.body().asInputStream());
		return response.toBuilder().body(body).build();
	}

	private Integer extractBusinessStatus(Response response) throws IOException {
		if (response.body() == null) {
			return null;
		}
		byte[] body = StreamUtils.copyToByteArray(response.body().asInputStream());
		if (body.length == 0) {
			return null;
		}
		JsonNode root;
		try {
			root = this.objectMapper.readTree(body);
		}
		catch (Exception ex) {
			return null;
		}
		JsonNode statusNode = root == null ? null : root.get("status");
		if (statusNode == null || statusNode.isNull()) {
			return null;
		}
		if (statusNode.isInt() || statusNode.isLong()) {
			return statusNode.intValue();
		}
		if (statusNode.isTextual()) {
			try {
				return Integer.valueOf(statusNode.textValue().trim());
			}
			catch (Exception ex) {
				return null;
			}
		}
		return null;
	}

}
