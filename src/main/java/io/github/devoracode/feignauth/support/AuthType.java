package io.github.devoracode.feignauth.support;

import org.apache.commons.lang3.StringUtils;

/**
 * 已配置的 Feign 服务所支持的认证类型。
 *
 * @author Wenjie Liu
 * @since 1.0.0
 */
public enum AuthType {

	/**
	 * OAuth2 令牌获取，支持按路径前缀进行可选的 Client 路由。
	 */
	OAUTH2("oauth2"),

	/**
	 * 注入请求 header 的静态 API key。
	 */
	API_KEY("api-key");

	private final String value;

	AuthType(String value) {
		this.value = value;
	}

	public String getValue() {
		return this.value;
	}

	/**
	 * 解析配置的认证类型字符串，忽略大小写。
	 * @param type 配置的类型取值
	 * @return 匹配的 {@link AuthType}；{@code type} 为空白时返回 {@code null}
	 */
	public static AuthType from(String type) {
		if (StringUtils.isBlank(type)) {
			return null;
		}
		for (AuthType authType : values()) {
			if (authType.value.equalsIgnoreCase(type.trim())) {
				return authType;
			}
		}
		return null;
	}

}
