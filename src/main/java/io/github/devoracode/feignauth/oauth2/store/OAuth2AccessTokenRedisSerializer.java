package io.github.devoracode.feignauth.oauth2.store;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.devoracode.feignauth.oauth2.OAuth2AccessToken;
import org.springframework.data.redis.serializer.RedisSerializer;
import org.springframework.data.redis.serializer.SerializationException;

import java.io.IOException;

/**
 * 令牌条目的 JSON 序列化器：读写的目标类型固定为 {@link OAuth2AccessToken}。
 *
 * <p>不使用带 default typing 的通用 JSON 序列化器，避免 Redis 值里的类名（{@code @class}）
 * 决定应用实例化哪个类——Redis 数据可被第三方写入时，那等于把反序列化攻击面交给外部输入。
 * 读取时仅忽略 {@code @class}，因此早期版本写入的条目仍能正常读出，而其他业务的 JSON 对象
 * 会因未知字段报错并被上层清理，不会被读成字段全空的令牌。
 */
final class OAuth2AccessTokenRedisSerializer implements RedisSerializer<OAuth2AccessToken> {

	private final ObjectMapper objectMapper = new ObjectMapper()
			.addMixIn(OAuth2AccessToken.class, IgnoreTypeMetadataMixin.class);

	@Override
	public byte[] serialize(OAuth2AccessToken token) throws SerializationException {
		if (token == null) {
			return new byte[0];
		}
		try {
			return this.objectMapper.writeValueAsBytes(token);
		}
		catch (JsonProcessingException ex) {
			throw new SerializationException("Cannot serialize OAuth2 access token to JSON: " + ex.getMessage(), ex);
		}
	}

	@Override
	public OAuth2AccessToken deserialize(byte[] bytes) throws SerializationException {
		if (bytes == null || bytes.length == 0) {
			return null;
		}
		try {
			return this.objectMapper.readValue(bytes, OAuth2AccessToken.class);
		}
		catch (IOException ex) {
			throw new SerializationException("Could not deserialize OAuth2 access token: " + ex.getMessage(), ex);
		}
	}

	@JsonIgnoreProperties(value = { "@class" }, allowGetters = true)
	private interface IgnoreTypeMetadataMixin {
	}

}
