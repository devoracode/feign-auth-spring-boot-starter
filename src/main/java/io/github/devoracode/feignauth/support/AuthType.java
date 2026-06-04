package io.github.devoracode.feignauth.support;

/**
 * Supported authentication types for configured Feign services.
 *
 * @author Wenjie Liu
 * @since 1.0.0
 */
public enum AuthType {

	/**
	 * OAuth2 token acquisition with optional client routing by path prefix.
	 */
	OAUTH2("oauth2"),

	/**
	 * Static API key injected into a request header.
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
	 * Parses the configured auth type string, ignoring case.
	 * @param type the configured type value
	 * @return the matching {@link AuthType}, or {@code null} when {@code type} is blank
	 */
	public static AuthType from(String type) {
		if (!org.springframework.util.StringUtils.hasText(type)) {
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
