package io.github.devoracode.feignauth.feign;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import feign.FeignException;
import feign.Request;
import feign.Response;
import feign.RetryableException;
import io.github.devoracode.feignauth.autoconfigure.FeignAuthProperties;
import io.github.devoracode.feignauth.exception.FeignAuthTokenException;
import io.github.devoracode.feignauth.oauth2.TokenFetcher;
import io.github.devoracode.feignauth.support.AuthType;
import io.github.devoracode.feignauth.support.PathUtils;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.springframework.util.Assert;
import org.apache.commons.lang3.StringUtils;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Date;

/**
 * 处理 Feign 请求的 HTTP 错误响应，包括在 token 过期状态码下淘汰
 * OAuth2 令牌缓存，并为常见 HTTP 错误生成清晰的错误信息。
 *
 * @author Wenjie Liu
 * @since 1.0.0
 */
final class FeignAuthStatusHandler {

	private static final Log logger = LogFactory.getLog(FeignAuthStatusHandler.class);

	private final ServiceMatcher serviceMatcher;

	private final TokenFetcher tokenFetcher;

	private final ObjectMapper objectMapper;

	// URL 解析失败通常来自同一个客户端方法，每次重试都提醒一次毫无增量信息，同一 URL 只提醒一次
	private final WarnThrottle urlParseWarnings = new WarnThrottle();

	FeignAuthStatusHandler(ServiceMatcher serviceMatcher, TokenFetcher tokenFetcher, ObjectMapper objectMapper) {
		Assert.notNull(serviceMatcher, "serviceMatcher must not be null");
		Assert.notNull(tokenFetcher, "tokenFetcher must not be null");
		Assert.notNull(objectMapper, "objectMapper must not be null");
		this.serviceMatcher = serviceMatcher;
		this.tokenFetcher = tokenFetcher;
		this.objectMapper = objectMapper;
	}

	/**
	 * 处理 HTTP 错误响应。
	 * <p>当状态码为 421 或 423 且服务使用 OAuth2 时，会淘汰缓存的 token 并返回
	 * {@link RetryableException} 以触发重试；若该令牌的淘汰正处于冷却期，则返回
	 * 不可重试的原始错误。对于其他 4xx/5xx 状态码，返回带有
	 * 描述信息的 {@link FeignAuthTokenException}。
	 * @param methodKey Feign 方法键（用于构造 cause 异常）
	 * @param response Feign 响应
	 * @return 需要向上抛出的异常；状态码低于 400 时返回 {@code null}
	 */
	Exception handle(String methodKey, Response response) {
		if (response == null || response.request() == null) {
			return null;
		}

		int status = response.status();
		ResolvedRequest request = ResolvedRequest.from(response.request(), this.urlParseWarnings);
		ResolvedService resolved = (request != null)
				? this.serviceMatcher.match(request.getBaseUrl(), request.getRequestPath()) : null;
		// 认证类型只解析一次：下面的判定原本会把 isOAuth2() 连调三次，每次都重扫一遍枚举
		AuthType authType = authTypeOf(resolved);

		// 检查是否需要读取响应体
		byte[] bodyBytes = null;
		if (needResponseBodyCheck(resolved, authType, status) && response.body() != null) {
			ByteArrayOutputStream buffer = new ByteArrayOutputStream();
			boolean bodyFullyRead = false;
			try {
				InputStream in = response.body().asInputStream();
				byte[] chunk = new byte[8192];
				int read;
				while ((read = in.read(chunk)) != -1) {
					buffer.write(chunk, 0, read);
				}
				bodyFullyRead = true;
			}
			catch (IOException e) {
				if (logger.isWarnEnabled()) {
					logger.warn("Failed to read response body for expired token check", e);
				}
			}
			// 流一旦被读就消费掉了，必须把读到的字节（异常时只有一半）回灌：
			// 不回灌的话后续 FeignException.errorStatus 只会拿到所剩无几甚至为空的正文
			byte[] readBytes = buffer.toByteArray();
			response = response.toBuilder().body(readBytes).build();
			// 令牌过期判定仍只用完整读到的正文：截断内容本就不该据此清缓存重放
			if (bodyFullyRead) {
				bodyBytes = readBytes;
			}
		}

		// 检查 token 是否过期（HTTP 状态码或响应体状态）
		Integer detectedStatus = detectExpiredTokenStatus(resolved, status, bodyBytes);
		if (detectedStatus != null && authType == AuthType.OAUTH2) {
			return handleExpiredToken(methodKey, response, resolved, request, detectedStatus);
		}

		if (status < 400) {
			return null;
		}

		String serviceName = (resolved != null) ? resolved.getServiceName() : "unknown";
		return remoteFailure(methodKey, response, serviceName, status);
	}

	private boolean needResponseBodyCheck(ResolvedService resolved, AuthType authType, int status) {
		FeignAuthProperties.Auth auth = authOf(resolved);
		if (auth == null) {
			return false;
		}
		// 仅在以下条件同时满足时才检查响应体：
		// 1. HTTP 状态码本身不是 token 过期状态
		// 2. 该服务为 OAuth2 服务
		// 3. 已显式配置 responseStatusField
		return !isExpiredTokenStatus(auth, status) && authType == AuthType.OAUTH2
				&& StringUtils.isNotBlank(auth.getResponseStatusField());
	}

	private Integer detectExpiredTokenStatus(ResolvedService resolved, int status, byte[] bodyBytes) {
		FeignAuthProperties.Auth auth = authOf(resolved);
		// 首先检查 HTTP 状态码
		if (isExpiredTokenStatus(auth, status)) {
			return status;
		}

		// 若响应体可用，再检查响应体中的状态
		if (bodyBytes == null || bodyBytes.length == 0 || auth == null) {
			return null;
		}

		String responseStatusField = auth.getResponseStatusField();
		if (StringUtils.isBlank(responseStatusField)) {
			return null;
		}

		try {
			String body = new String(bodyBytes, StandardCharsets.UTF_8);
			Integer responseStatus = parseJsonField(body, responseStatusField);
			if (responseStatus != null && auth.isExpiredTokenStatus(responseStatus)) {
				if (logger.isDebugEnabled()) {
					logger.debug("FeignAuth: detected expired token status " + responseStatus + " in response body");
				}
				return responseStatus;
			}
		} catch (Exception e) {
			if (logger.isDebugEnabled()) {
				logger.debug("Failed to parse response body for expired token check", e);
			}
		}

		return null;
	}

	private Integer parseJsonField(String jsonBody, String fieldPath) {
		if (StringUtils.isBlank(jsonBody) || StringUtils.isBlank(fieldPath)) {
			return null;
		}

		try {
			JsonNode root = this.objectMapper.readTree(jsonBody);
			String[] fields = fieldPath.split("\\.");
			JsonNode current = root;

			for (String field : fields) {
				if (current == null || current.isNull()) {
					return null;
				}
				current = current.get(field);
			}

			if (current != null && !current.isNull()) {
				if (current.isNumber()) {
					return current.asInt();
				} else if (current.isTextual()) {
					return Integer.parseInt(current.asText());
				}
			}
		} catch (Exception e) {
			if (logger.isDebugEnabled()) {
				logger.debug("Failed to parse JSON field: " + fieldPath, e);
			}
		}

		return null;
	}

	private Exception handleExpiredToken(String methodKey, Response response, ResolvedService resolved,
			ResolvedRequest request, Integer detectedStatus) {

		boolean evicted = this.tokenFetcher.invalidateToken(resolved.getServiceName(), request.getRequestPath());
		if (!evicted) {
			// 冷却期内不再清缓存重试：持续 401 说明新令牌同样被拒，重试只会放大请求量
			String message = failureMessage(resolved.getServiceName(), detectedStatus,
					"OAuth2 token eviction skipped (cooldown or cache empty), request not retried");
			if (logger.isWarnEnabled()) {
				logger.warn(message);
			}
			return new FeignAuthTokenException(message, FeignException.errorStatus(methodKey, response));
		}
		String message = failureMessage(resolved.getServiceName(), detectedStatus,
				"OAuth2 token cache evicted, retrying request");
		if (logger.isWarnEnabled()) {
			logger.warn(message);
		}
		Date retryAfter = new Date(System.currentTimeMillis() + 1000L);
		return new RetryableException(detectedStatus, message, response.request().httpMethod(), retryAfter,
				response.request());
	}

	// 远程错误的对外说明：异常类型与文案都属于契约，新增状态码只需要往表里加一行
	private static FeignAuthTokenException remoteFailure(String methodKey, Response response, String serviceName,
			int status) {
		return new FeignAuthTokenException(failureMessage(serviceName, status, adviceFor(status)),
				FeignException.errorStatus(methodKey, response));
	}

	private static String failureMessage(String serviceName, int status, String advice) {
		return "FeignAuth: service '" + serviceName + "' returned " + status + ", " + advice;
	}

	private static String adviceFor(int status) {
		switch (status) {
		case 401:
			return "current credentials are not allowed to access this endpoint";
		case 403:
			return "the remote service explicitly rejected this request";
		case 404:
			return "the target resource or path does not exist";
		case 429:
			return "the remote service rate limit has been exceeded";
		default:
			return status >= 500 ? "the remote service is temporarily unavailable"
					: "request failed on the remote side";
		}
	}

	private static boolean isExpiredTokenStatus(FeignAuthProperties.Auth auth, int status) {
		return auth != null && auth.isExpiredTokenStatus(status);
	}

	// resolved → service → auth 三段判空原本在四个调用点各写一遍，收敛到这里。
	// 任一段缺失都按「该服务没有可读的 auth 配置」处理，返回 null 后由各调用方决定后果
	private static FeignAuthProperties.Auth authOf(ResolvedService resolved) {
		if (resolved == null || resolved.getService() == null) {
			return null;
		}
		return resolved.getService().getAuth();
	}

	private static AuthType authTypeOf(ResolvedService resolved) {
		FeignAuthProperties.Auth auth = authOf(resolved);
		return (auth == null) ? null : AuthType.from(auth.getType());
	}

	static final class ResolvedRequest {

		private final String baseUrl;

		private final String requestPath;

		private ResolvedRequest(String baseUrl, String requestPath) {
			this.baseUrl = baseUrl;
			this.requestPath = requestPath;
		}

		String getBaseUrl() {
			return this.baseUrl;
		}

		String getRequestPath() {
			return this.requestPath;
		}

		static ResolvedRequest from(Request request, WarnThrottle warnings) {
			if (request == null || StringUtils.isBlank(request.url())) {
				return null;
			}
			try {
				UrlParts urlParts = UrlParts.parse(request.url());
				return new ResolvedRequest(urlParts.getBaseUrl(),
						PathUtils.normalizeRequestPath(urlParts.getRawPath()));
			}
			catch (RuntimeException ex) {
				// 最终 URL 无法解析（如路径里含未编码的空格/非 ASCII 字面量）时，不能把一次正常的 4xx
				// 变成解析异常：返回 null 走「未匹配服务」分支，仍会抛出 FeignAuthTokenException，
				// 只是不做令牌驱逐与重放
				if (warnings.shouldWarn(request.url())) {
					logger.warn("FeignAuth: cannot parse request url '" + request.url()
							+ "' for expired-token check: " + ex.getMessage()
							+ "; this warning is logged once per url, later occurrences are logged at debug level");
				} else if (logger.isDebugEnabled()) {
					logger.debug("FeignAuth: cannot parse request url '" + request.url()
							+ "' for expired-token check: " + ex.getMessage());
				}
				return null;
			}
		}

	}

}
