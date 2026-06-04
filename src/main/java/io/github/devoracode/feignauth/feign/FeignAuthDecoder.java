package io.github.devoracode.feignauth.feign;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import feign.FeignException;
import feign.Response;
import feign.codec.DecodeException;
import feign.codec.Decoder;
import org.springframework.util.StreamUtils;

import java.io.IOException;
import java.lang.reflect.Type;

/**
 * Feign {@link Decoder} that inspects HTTP 200 response bodies for a business-level
 * {@code status} field before delegating to the underlying decoder.
 *
 * <p>Some APIs return HTTP 200 with a JSON body containing a {@code status} field
 * that signals an application-level error (e.g. {@code {"status": 421}}). This decoder
 * detects such cases and delegates to {@link FeignAuthStatusHandler} so that expired
 * OAuth2 tokens can be evicted and the request retried.
 *
 * @author Wenjie Liu
 * @since 1.0.0
 */
class FeignAuthDecoder implements Decoder {

	private final Decoder delegate;

	private final ObjectMapper objectMapper;

	private final FeignAuthStatusHandler statusHandler;

	FeignAuthDecoder(Decoder delegate, ObjectMapper objectMapper, FeignAuthStatusHandler statusHandler) {
		this.delegate = delegate;
		this.objectMapper = objectMapper;
		this.statusHandler = statusHandler;
	}

	@Override
	public Object decode(Response response, Type type) throws IOException, DecodeException, FeignException {
		if (response == null || response.status() < 200 || response.status() >= 300 || response.body() == null) {
			return this.delegate.decode(response, type);
		}

		// Buffer body so it can be read twice (once for status check, once for decoding).
		byte[] body = StreamUtils.copyToByteArray(response.body().asInputStream());
		Response buffered = response.toBuilder().body(body).build();

		Integer businessStatus = extractStatus(body);
		Exception ex = this.statusHandler.handle("business-status", buffered, businessStatus);
		if (ex instanceof RuntimeException) {
			throw (RuntimeException) ex;
		}
		if (ex != null) {
			throw new DecodeException(response.status(), ex.getMessage(), response.request(), ex);
		}

		return this.delegate.decode(buffered, type);
	}

	private Integer extractStatus(byte[] body) {
		if (body.length == 0) {
			return null;
		}
		try {
			JsonNode root = this.objectMapper.readTree(body);
			JsonNode node = (root != null) ? root.get("status") : null;
			if (node == null || node.isNull()) {
				return null;
			}
			if (node.isNumber()) {
				return node.intValue();
			}
			if (node.isTextual()) {
				return Integer.parseInt(node.textValue().trim());
			}
		}
		catch (Exception ignored) {
			// unparseable body — treat as no business status
		}
		return null;
	}

}
