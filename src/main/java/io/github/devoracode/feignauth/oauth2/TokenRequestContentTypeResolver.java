package io.github.devoracode.feignauth.oauth2;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.devoracode.feignauth.exception.FeignAuthConfigurationException;
import io.github.devoracode.feignauth.exception.FeignAuthTokenException;
import org.apache.commons.lang3.StringUtils;
import org.apache.http.HttpEntity;
import org.apache.http.NameValuePair;
import org.apache.http.ParseException;
import org.apache.http.client.entity.UrlEncodedFormEntity;
import org.apache.http.entity.ContentType;
import org.apache.http.entity.StringEntity;
import org.apache.http.message.BasicNameValuePair;
import org.springframework.http.HttpHeaders;
import org.springframework.util.Assert;

import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 取号请求 Content-Type 的协商与请求体构建。
 *
 * <p>配置的 Content-Type 决定请求体的编码方案，避免头体错配：JSON 族（{@code /json} 或
 * {@code +json}）写 JSON，{@code application/x-www-form-urlencoded} 写 UTF-8 表单对，
 * 其余取值或未配置分别按快速失败与默认 JSON 处理。两条 {@link FeignAuthConfigurationException}
 * 的文案都点名用户配置的原始值。</p>
 *
 * @author Wenjie Liu
 * @since 2.0.0
 */
final class TokenRequestContentTypeResolver {

	private final ObjectMapper objectMapper;

	TokenRequestContentTypeResolver(ObjectMapper objectMapper) {
		Assert.notNull(objectMapper, "objectMapper must not be null");
		this.objectMapper = objectMapper;
	}

	ContentType resolve(HttpHeaders headers) {
		String configured = headers.getFirst(HttpHeaders.CONTENT_TYPE);
		if (StringUtils.isBlank(configured)) {
			return ContentType.APPLICATION_JSON;
		}
		ContentType contentType;
		try {
			contentType = ContentType.parse(configured);
		}
		// ParseException 也是 RuntimeException：`;`、`,` 这类被拆成空元素的值只抛它，不一起接住就绕过下面的快速失败
		catch (IllegalArgumentException | ParseException ex) {
			contentType = null;
		}
		if (contentType == null) {
			throw new FeignAuthConfigurationException("Invalid Content-Type in token-request-headers: '" + configured + "'");
		}
		String mimeType = contentType.getMimeType().toLowerCase(Locale.ROOT);
		boolean json = mimeType.endsWith("/json") || mimeType.endsWith("+json");
		boolean form = ContentType.APPLICATION_FORM_URLENCODED.getMimeType().equals(mimeType);
		if (!json && !form) {
			throw new FeignAuthConfigurationException("Unsupported OAuth2 token request Content-Type: '" + configured
					+ "', expected application/json or application/x-www-form-urlencoded");
		}
		return contentType;
	}

	HttpEntity buildEntity(ContentType contentType, Map<String, String> parameters) {
		if (ContentType.APPLICATION_FORM_URLENCODED.getMimeType().equalsIgnoreCase(contentType.getMimeType())) {
			List<NameValuePair> pairs = new ArrayList<>();
			parameters.forEach((name, value) -> pairs.add(new BasicNameValuePair(name, value)));
			return new UrlEncodedFormEntity(pairs, StandardCharsets.UTF_8);
		}
		Charset charset = contentType.getCharset();
		return new StringEntity(writeJson(parameters), charset != null ? contentType
				: contentType.withCharset(StandardCharsets.UTF_8));
	}

	private String writeJson(Map<String, String> parameters) {
		try {
			return this.objectMapper.writeValueAsString(parameters);
		}
		catch (IOException ex) {
			throw new FeignAuthTokenException("Serialize token request failed", ex);
		}
	}

}
