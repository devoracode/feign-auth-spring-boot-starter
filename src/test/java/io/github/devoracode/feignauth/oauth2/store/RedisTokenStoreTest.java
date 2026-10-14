package io.github.devoracode.feignauth.oauth2.store;

import io.github.devoracode.feignauth.autoconfigure.FeignAuthProperties;
import io.github.devoracode.feignauth.oauth2.OAuth2AccessToken;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.serializer.GenericJackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.SerializationException;
import org.springframework.data.redis.serializer.StringRedisSerializer;

import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RedisTokenStoreTest {

	@Test
	void builtTemplateUsesStringKeysAndTypedJsonValues() {
		RedisTokenStore store = new RedisTokenStore(mock(RedisConnectionFactory.class),
				new FeignAuthProperties.Redis());

		RedisTemplate<String, Object> template = store.getRedisTemplate();
		assertThat(template.getKeySerializer()).isInstanceOf(StringRedisSerializer.class);
		assertThat(template.getValueSerializer()).isInstanceOf(GenericJackson2JsonRedisSerializer.class);
	}

	@Test
	void jsonValueSerializerRoundTripsToken() {
		GenericJackson2JsonRedisSerializer serializer = new GenericJackson2JsonRedisSerializer();
		OAuth2AccessToken token = new OAuth2AccessToken();
		token.setAccessToken("t");
		token.setTtl(60L);
		token.setExpireAt(123L);

		Object restored = serializer.deserialize(serializer.serialize(token));

		assertThat(restored).isEqualTo(token);
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

	@SuppressWarnings("unchecked")
	private static RedisTemplate<String, Object> mockTemplateWithValues() {
		RedisTemplate<String, Object> redisTemplate = mock(RedisTemplate.class);
		ValueOperations<String, Object> valueOperations = mock(ValueOperations.class);
		when(redisTemplate.opsForValue()).thenReturn(valueOperations);
		return redisTemplate;
	}

}
