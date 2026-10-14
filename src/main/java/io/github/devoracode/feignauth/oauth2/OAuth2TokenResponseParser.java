package io.github.devoracode.feignauth.oauth2;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.devoracode.feignauth.exception.FeignAuthTokenException;
import org.apache.commons.lang3.StringUtils;
import org.springframework.util.Assert;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 将 OAuth2 令牌端点的响应解析为 {@link OAuth2AccessToken} 实例。
 *
 * @author Wenjie Liu
 * @since 1.0.0
 */
public class OAuth2TokenResponseParser {

	private static final long DEFAULT_EXPIRES_IN_SECONDS = 7200L;

	// TTL 上限取 1 天：超长有效期收敛为每日刷新，同时保证 *1000 不会 long 溢出
	private static final long MAX_TTL_SECONDS = 24 * 60 * 60L;

	private static final int MAX_EXPIRES_IN_SEARCH_DEPTH = 3;

	// 异常信息中保留的响应体上限：足够定位 IdP 错误，又不会把整页 HTML 塞进日志
	private static final int MAX_ERROR_BODY_CHARS = 512;

	private static final List<String> EXPIRES_IN_FIELD_NAMES = Arrays.asList("expires_in", "expiresIn", "expire_in",
			"expireIn");

	private final ObjectMapper objectMapper;

	public OAuth2TokenResponseParser(ObjectMapper objectMapper) {
		Assert.notNull(objectMapper, "objectMapper must not be null");
		this.objectMapper = objectMapper;
	}

	/**
	 * 解析令牌端点返回的 HTTP 响应。
	 * @param statusCode 令牌响应的 HTTP 状态码
	 * @param body 原始响应体
	 * @param expireAheadSeconds 令牌到期前多少秒即应刷新
	 * @param tokenField 访问令牌可选的点分隔 JSON 路径
	 * @param configuredExpiresInSeconds 可选的配置令牌 TTL（秒）；为正数时覆盖
	 *        对响应体的解析
	 * @return 装配完成的访问令牌
	 */
	public OAuth2AccessToken parse(int statusCode, String body, long expireAheadSeconds, String tokenField,
			Long configuredExpiresInSeconds) {
		if (statusCode < 200 || statusCode >= 300) {
			throw new FeignAuthTokenException(describeFailure(statusCode, body));
		}
		try {
			JsonNode root = this.objectMapper.readTree(body == null ? "" : body);
			String token = extractAccessToken(root, tokenField);
			long expiresIn = resolveExpiresInSeconds(root, configuredExpiresInSeconds);
			// 钳制取值使 refreshIn 始终 >= 1：0 秒 TTL 会破坏 Redis SETEX，
			// 并导致本地缓存立即过期（引发令牌端点请求风暴）。
			long expireAhead = Math.max(0L, Math.min(expireAheadSeconds, expiresIn - 1L));
			long refreshIn = Math.max(1L, expiresIn - expireAhead);

			OAuth2AccessToken accessToken = new OAuth2AccessToken();
			accessToken.setAccessToken(token);
			// 巨大 expires_in（如服务端误把毫秒当秒返回）不得溢出为负 expireAt，
			// 否则 token 立即被判过期、每个请求都触发取号；统一钳制到 1 天上限。
			long safeRefreshIn = Math.min(refreshIn, MAX_TTL_SECONDS);
			accessToken.setExpireAt(System.currentTimeMillis() + safeRefreshIn * 1000L);
			accessToken.setTtl(safeRefreshIn);
			return accessToken;
		}
		catch (FeignAuthTokenException ex) {
			throw ex;
		}
		catch (Exception ex) {
			throw new FeignAuthTokenException("Parse token response failed: " + ex.getMessage(), ex);
		}
	}

	// 非 2xx 时 IdP 通常返回 {"error":..,"error_description":..}，只报状态码会让排障无从下手
	private String describeFailure(int statusCode, String body) {
		StringBuilder message = new StringBuilder("Token request failed: ").append(statusCode);
		if (StringUtils.isBlank(body)) {
			return message.toString();
		}
		try {
			JsonNode root = this.objectMapper.readTree(body);
			String error = textValue(root.get("error"));
			String description = textValue(root.get("error_description"));
			if (error != null || description != null) {
				if (error != null) {
					message.append(", error=").append(truncate(error));
				}
				if (description != null) {
					message.append(", error_description=").append(truncate(description));
				}
				return message.toString();
			}
		}
		catch (Exception ignored) {
			// 响应体不是 JSON，走下方原文截断
		}
		return message.append(", body=").append(truncate(body)).toString();
	}

	private static String textValue(JsonNode node) {
		if (node == null || !node.isValueNode()) {
			return null;
		}
		String value = node.asText();
		return StringUtils.isNotBlank(value) ? value : null;
	}

	private static String truncate(String value) {
		if (value.length() <= MAX_ERROR_BODY_CHARS) {
			return value;
		}
		return value.substring(0, MAX_ERROR_BODY_CHARS) + "...(truncated)";
	}

	private static long resolveExpiresInSeconds(JsonNode root, Long configuredExpiresInSeconds) {
		if (configuredExpiresInSeconds != null && configuredExpiresInSeconds > 0) {
			return configuredExpiresInSeconds;
		}
		return findExpiresInSeconds(root, DEFAULT_EXPIRES_IN_SECONDS);
	}

	private static long findExpiresInSeconds(JsonNode root, long defaultValue) {
		List<JsonNode> currentLevel = Collections.singletonList(root);
		for (int depth = 0; depth < MAX_EXPIRES_IN_SEARCH_DEPTH; depth++) {
			for (JsonNode node : currentLevel) {
				if (node != null && node.isObject()) {
					Long value = readPositiveLongFromKnownFields(node);
					if (value != null) {
						return value;
					}
				}
			}
			List<JsonNode> nextLevel = new ArrayList<>();
			for (JsonNode node : currentLevel) {
				collectObjectChildren(node, nextLevel);
			}
			currentLevel = nextLevel;
			if (currentLevel.isEmpty()) {
				break;
			}
		}
		return defaultValue;
	}

	private static void collectObjectChildren(JsonNode node, List<JsonNode> children) {
		if (node == null || !node.isObject()) {
			return;
		}
		Iterator<Map.Entry<String, JsonNode>> fields = node.fields();
		while (fields.hasNext()) {
			JsonNode child = fields.next().getValue();
			if (child != null && child.isObject()) {
				children.add(child);
			}
		}
	}

	private static Long readPositiveLongFromKnownFields(JsonNode node) {
		for (String fieldName : EXPIRES_IN_FIELD_NAMES) {
			Long value = readPositiveLong(node.get(fieldName));
			if (value != null) {
				return value;
			}
		}
		return null;
	}

	private static Long readPositiveLong(JsonNode node) {
		if (node == null || node.isNull()) {
			return null;
		}
		Long value = null;
		if (node.isNumber()) {
			value = node.asLong();
		}
		else if (node.isTextual()) {
			try {
				value = Long.parseLong(StringUtils.strip(node.asText()));
			}
			catch (NumberFormatException ignored) {
				// 忽略，继续
			}
		}
		if (value != null && value > 0) {
			return value;
		}
		return null;
	}

	private static String extractAccessToken(JsonNode root, String tokenField) {
		if (StringUtils.isNotBlank(tokenField)) {
			String token = resolveTokenByPath(root, tokenField.trim());
			if (StringUtils.isBlank(token)) {
				throw new FeignAuthTokenException(
						"Token field '" + tokenField + "' not found or blank in response");
			}
			return token;
		}
		String token = firstText(root, "access_token", "accessToken", "token");
		if (StringUtils.isBlank(token)) {
			throw new FeignAuthTokenException("Token field not found in response");
		}
		return token;
	}

	private static String resolveTokenByPath(JsonNode root, String tokenField) {
		String[] segments = tokenField.split("\\.");
		JsonNode node = root;
		for (String segment : segments) {
			if (node == null || !node.isObject()) {
				return null;
			}
			node = node.get(segment);
		}
		if (node == null || !node.isValueNode()) {
			return null;
		}
		String value = node.asText();
		return StringUtils.isNotBlank(value) ? value : null;
	}

	private static String firstText(JsonNode root, String... fieldNames) {
		return Arrays.stream(fieldNames)
				.map(root::get)
				.filter(Objects::nonNull)
				.filter(JsonNode::isValueNode)
				.map(JsonNode::asText)
				.filter(StringUtils::isNotBlank)
				.findFirst()
				.orElse(null);
	}

}
