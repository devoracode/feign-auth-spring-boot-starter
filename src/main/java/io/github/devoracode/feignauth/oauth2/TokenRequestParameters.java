package io.github.devoracode.feignauth.oauth2;

import io.github.devoracode.feignauth.autoconfigure.FeignAuthProperties;
import org.apache.commons.lang3.StringUtils;

import java.util.HashMap;
import java.util.Map;

/**
 * 取号请求参数的组装与客户端凭证剥离。
 *
 * <p>凭证字段名取自 {@code request-fields}，未配置时用 RFC 6749 约定的 {@code client_id} /
 * {@code client_secret}；剥离凭证时复用同一份字段名口径，因此 {@code request-fields} 改名后两侧
 * 依然对齐。grant_type 的写入规则依赖字段名是否被配置，与组装留在同一个类里。</p>
 *
 * @author Wenjie Liu
 * @since 2.0.0
 */
final class TokenRequestParameters {

	private TokenRequestParameters() {
	}

	static Map<String, String> build(FeignAuthProperties.Auth auth, FeignAuthProperties.Client client) {
		FeignAuthProperties.RequestFields fields = auth.getRequestFields();
		Map<String, String> parameters = new HashMap<>();
		parameters.put(clientIdField(fields), client.getId());
		parameters.put(clientSecretField(fields), client.getSecret());

		if (StringUtils.isBlank(fields.getClientId()) && StringUtils.isBlank(fields.getClientSecret())) {
			parameters.put("grant_type", StringUtils.defaultString(client.getGrantType(), "client_credentials"));
		}
		else if (StringUtils.isNotBlank(fields.getGrantType()) && StringUtils.isNotBlank(client.getGrantType())) {
			parameters.put(fields.getGrantType(), client.getGrantType());
		}
		return parameters;
	}

	// RFC 6749 §2.3.1：一个请求只能使用一种客户端认证方式，走 Basic 时参数里不能再留凭证
	static void removeClientCredentials(FeignAuthProperties.Auth auth, Map<String, String> parameters) {
		FeignAuthProperties.RequestFields fields = auth.getRequestFields();
		parameters.remove(clientIdField(fields));
		parameters.remove(clientSecretField(fields));
	}

	private static String clientIdField(FeignAuthProperties.RequestFields fields) {
		return StringUtils.defaultString(fields.getClientId(), "client_id");
	}

	private static String clientSecretField(FeignAuthProperties.RequestFields fields) {
		return StringUtils.defaultString(fields.getClientSecret(), "client_secret");
	}

}
