package io.github.devoracode.feignauth.oauth2;

import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;

import java.io.Serializable;

/**
 * 带绝对过期时间戳的已缓存 OAuth2 访问令牌。
 *
 * @author Wenjie Liu
 * @since 1.0.0
 */
@Getter
@Setter
@EqualsAndHashCode
// 令牌值不得进入 toString，防止日志/异常拼接泄露
@ToString(exclude = "accessToken")
public class OAuth2AccessToken implements Serializable {

	private static final long serialVersionUID = 1L;

	private String accessToken;

	private long ttl;

	private long expireAt;

	public boolean isExpired() {
		return System.currentTimeMillis() >= this.expireAt;
	}

}
