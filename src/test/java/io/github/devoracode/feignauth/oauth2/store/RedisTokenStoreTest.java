package io.github.devoracode.feignauth.oauth2.store;

import io.github.devoracode.feignauth.autoconfigure.FeignAuthProperties;
import io.github.devoracode.feignauth.oauth2.OAuth2AccessToken;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.serializer.RedisSerializer;
import org.springframework.data.redis.serializer.SerializationException;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RedisTokenStoreTest {

	@Test
	void storedPayloadTypeMetadataDoesNotDriveInstantiation() {
		RedisTokenStore store = new RedisTokenStore(mock(RedisConnectionFactory.class),
				new FeignAuthProperties.Redis());
		RedisSerializer<Object> valueSerializer = valueSerializer(store);
		String payload = "{\"@class\":\"" + ProbeBean.class.getName() + "\",\"flagged\":true}";

		Object restored = null;
		try {
			valueSerializer.deserialize(payload.getBytes(StandardCharsets.UTF_8));
		}
		catch (SerializationException expected) {
			// 载荷被拒绝即可，重点是不得按其中的类名实例化
		}

		// Redis 值可被第三方写入时，类型元数据不得决定实例化哪个类
		assertThat(ProbeBean.INSTANTIATED.get()).isFalse();
		assertThat(restored).isNull();
	}

	@Test
	void foreignJsonObjectIsRejectedInsteadOfReadAsEmptyToken() {
		RedisTokenStore store = new RedisTokenStore(mock(RedisConnectionFactory.class),
				new FeignAuthProperties.Redis());
		RedisSerializer<Object> valueSerializer = valueSerializer(store);

		// 其他业务写在同一 key 上的 JSON 对象必须报错（上层据此 WARN 删除），不能读成字段全空的令牌
		assertThatThrownBy(
				() -> valueSerializer.deserialize("{\"orderId\":\"A-1\"}".getBytes(StandardCharsets.UTF_8)))
				.isInstanceOf(SerializationException.class);
	}

	@Test
	void valueSerializerWritesNoTypeMetadataAndRoundTripsToken() {
		RedisTokenStore store = new RedisTokenStore(mock(RedisConnectionFactory.class),
				new FeignAuthProperties.Redis());
		RedisSerializer<Object> valueSerializer = valueSerializer(store);

		byte[] stored = valueSerializer.serialize(token());
		String json = new String(stored, StandardCharsets.UTF_8);
		assertThat(json).doesNotContain("@class");
		assertThat(valueSerializer.deserialize(stored)).isEqualTo(token());
	}

	@Test
	void legacyTypedEntryIsStillReadable() {
		RedisTokenStore store = new RedisTokenStore(mock(RedisConnectionFactory.class),
				new FeignAuthProperties.Redis());
		RedisSerializer<Object> valueSerializer = valueSerializer(store);
		String legacy = "{\"@class\":\"io.github.devoracode.feignauth.oauth2.OAuth2AccessToken\","
				+ "\"accessToken\":\"t\",\"ttl\":60,\"expireAt\":123}";

		assertThat(valueSerializer.deserialize(legacy.getBytes(StandardCharsets.UTF_8))).isEqualTo(token());
	}

	@Test
	void badValueRaisesSerializationExceptionForStoreCleanup() {
		RedisTokenStore store = new RedisTokenStore(mock(RedisConnectionFactory.class),
				new FeignAuthProperties.Redis());
		RedisSerializer<Object> valueSerializer = valueSerializer(store);

		assertThatThrownBy(
				() -> valueSerializer.deserialize("not json".getBytes(StandardCharsets.UTF_8)))
				.isInstanceOf(SerializationException.class);
	}

	@Test
	void putFallsBackToOneSecondWhenTtlIsZero() {
		RedisTemplate<String, Object> redisTemplate = mockTemplateWithValues();

		RedisTokenStore store = new RedisTokenStore(redisTemplate, new FeignAuthProperties.Redis());
		OAuth2AccessToken token = new OAuth2AccessToken();
		token.setAccessToken("t");
		token.setTtl(0L);

		store.put("svc:client", token);

		@SuppressWarnings("unchecked")
		ValueOperations<String, Object> valueOperations = (ValueOperations<String, Object>) redisTemplate.opsForValue();
		verify(valueOperations).set(eq("feign-auth:token:svc:client"), eq(token), eq(1L), eq(TimeUnit.SECONDS));
	}

	@Test
	void putUsesTokenTtlWhenPositive() {
		RedisTemplate<String, Object> redisTemplate = mockTemplateWithValues();

		RedisTokenStore store = new RedisTokenStore(redisTemplate, new FeignAuthProperties.Redis());
		OAuth2AccessToken token = new OAuth2AccessToken();
		token.setAccessToken("t");
		token.setTtl(1740L);

		store.put("svc:client", token);

		@SuppressWarnings("unchecked")
		ValueOperations<String, Object> valueOperations = (ValueOperations<String, Object>) redisTemplate.opsForValue();
		verify(valueOperations).set(eq("feign-auth:token:svc:client"), eq(token), eq(1740L), eq(TimeUnit.SECONDS));
	}

	@Test
	void getReturnsNullAndRemovesKeyWhenValueTypeIsForeign() {
		RedisTemplate<String, Object> redisTemplate = mock(RedisTemplate.class);
		@SuppressWarnings("unchecked")
		ValueOperations<String, Object> valueOperations = mock(ValueOperations.class);
		when(redisTemplate.opsForValue()).thenReturn(valueOperations);
		when(valueOperations.get("feign-auth:token:svc:client")).thenReturn("not-a-token-object");

		RedisTokenStore store = new RedisTokenStore(redisTemplate, new FeignAuthProperties.Redis());

		assertThat(store.get("svc:client")).isNull();
		verify(redisTemplate).delete("feign-auth:token:svc:client");
	}

	@Test
	void getReturnsNullAndRemovesKeyWhenValueFailsToDeserialize() {
		RedisTemplate<String, Object> redisTemplate = mock(RedisTemplate.class);
		@SuppressWarnings("unchecked")
		ValueOperations<String, Object> valueOperations = mock(ValueOperations.class);
		when(redisTemplate.opsForValue()).thenReturn(valueOperations);
		when(valueOperations.get("feign-auth:token:svc:client")).thenThrow(new SerializationException("bad json"));

		RedisTokenStore store = new RedisTokenStore(redisTemplate, new FeignAuthProperties.Redis());

		assertThat(store.get("svc:client")).isNull();
		verify(redisTemplate).delete("feign-auth:token:svc:client");
	}

	@Test
	void getReturnsTokenWhenValueTypeMatches() {
		RedisTemplate<String, Object> redisTemplate = mock(RedisTemplate.class);
		@SuppressWarnings("unchecked")
		ValueOperations<String, Object> valueOperations = mock(ValueOperations.class);
		when(redisTemplate.opsForValue()).thenReturn(valueOperations);
		OAuth2AccessToken token = new OAuth2AccessToken();
		token.setAccessToken("t");
		when(valueOperations.get("feign-auth:token:svc:client")).thenReturn(token);

		RedisTokenStore store = new RedisTokenStore(redisTemplate, new FeignAuthProperties.Redis());

		assertThat(store.get("svc:client")).isSameAs(token);
	}

	@Test
	void removeReportsNotRemovedWhenTemplateGivesNullResult() {
		RedisTemplate<String, Object> redisTemplate = mock(RedisTemplate.class);
		when(redisTemplate.delete("feign-auth:token:svc:client")).thenReturn(null);

		RedisTokenStore store = new RedisTokenStore(redisTemplate, new FeignAuthProperties.Redis());

		assertThat(store.remove("svc:client")).isFalse();
	}

	@Test
	void removeReportsRemovedWhenTemplateGivesTrue() {
		RedisTemplate<String, Object> redisTemplate = mock(RedisTemplate.class);
		when(redisTemplate.delete("feign-auth:token:svc:client")).thenReturn(Boolean.TRUE);

		RedisTokenStore store = new RedisTokenStore(redisTemplate, new FeignAuthProperties.Redis());

		assertThat(store.remove("svc:client")).isTrue();
	}

	@SuppressWarnings("unchecked")
	private static RedisTemplate<String, Object> mockTemplateWithValues() {
		RedisTemplate<String, Object> redisTemplate = mock(RedisTemplate.class);
		ValueOperations<String, Object> valueOperations = mock(ValueOperations.class);
		when(redisTemplate.opsForValue()).thenReturn(valueOperations);
		return redisTemplate;
	}

	@SuppressWarnings("unchecked")
	private static RedisSerializer<Object> valueSerializer(RedisTokenStore store) {
		return (RedisSerializer<Object>) store.getRedisTemplate().getValueSerializer();
	}

	private static OAuth2AccessToken token() {
		OAuth2AccessToken token = new OAuth2AccessToken();
		token.setAccessToken("t");
		token.setTtl(60L);
		token.setExpireAt(123L);
		return token;
	}

	/** 仅用于证明「实例化哪个类由 Redis 里的字符串决定」 */
	public static class ProbeBean {

		static final AtomicBoolean INSTANTIATED = new AtomicBoolean();

		private boolean flagged;

		public ProbeBean() {
			INSTANTIATED.set(true);
		}

		public boolean isFlagged() {
			return this.flagged;
		}

		public void setFlagged(boolean flagged) {
			this.flagged = flagged;
		}

	}

}
