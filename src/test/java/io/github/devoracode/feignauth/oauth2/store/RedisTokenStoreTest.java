package io.github.devoracode.feignauth.oauth2.store;

import io.github.devoracode.feignauth.autoconfigure.FeignAuthProperties;
import io.github.devoracode.feignauth.oauth2.OAuth2AccessToken;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RedisTokenStoreTest {

	@Test
	void putFallsBackToOneSecondWhenTtlIsZero() {
		RedisTemplate<Object, Object> redisTemplate = mock(RedisTemplate.class);
		@SuppressWarnings("unchecked")
		ValueOperations<Object, Object> valueOperations = mock(ValueOperations.class);
		when(redisTemplate.opsForValue()).thenReturn(valueOperations);

		RedisTokenStore store = new RedisTokenStore(redisTemplate, new FeignAuthProperties.Redis());
		OAuth2AccessToken token = new OAuth2AccessToken();
		token.setAccessToken("t");
		token.setTtl(0L);

		store.put("svc:client", token);

		verify(valueOperations).set(eq("feign-auth:token:svc:client"), eq(token), eq(1L), eq(TimeUnit.SECONDS));
	}

	@Test
	void putUsesTokenTtlWhenPositive() {
		RedisTemplate<Object, Object> redisTemplate = mock(RedisTemplate.class);
		@SuppressWarnings("unchecked")
		ValueOperations<Object, Object> valueOperations = mock(ValueOperations.class);
		when(redisTemplate.opsForValue()).thenReturn(valueOperations);

		RedisTokenStore store = new RedisTokenStore(redisTemplate, new FeignAuthProperties.Redis());
		OAuth2AccessToken token = new OAuth2AccessToken();
		token.setAccessToken("t");
		token.setTtl(1740L);

		store.put("svc:client", token);

		verify(valueOperations).set(eq("feign-auth:token:svc:client"), eq(token), eq(1740L), eq(TimeUnit.SECONDS));
	}

	@Test
	void getReturnsNullAndRemovesKeyWhenValueTypeIsForeign() {
		RedisTemplate<Object, Object> redisTemplate = mock(RedisTemplate.class);
		@SuppressWarnings("unchecked")
		ValueOperations<Object, Object> valueOperations = mock(ValueOperations.class);
		when(redisTemplate.opsForValue()).thenReturn(valueOperations);
		when(valueOperations.get("feign-auth:token:svc:client")).thenReturn("not-a-token-object");

		RedisTokenStore store = new RedisTokenStore(redisTemplate, new FeignAuthProperties.Redis());

		assertThat(store.get("svc:client")).isNull();
		verify(redisTemplate).delete("feign-auth:token:svc:client");
	}

	@Test
	void getReturnsTokenWhenValueTypeMatches() {
		RedisTemplate<Object, Object> redisTemplate = mock(RedisTemplate.class);
		@SuppressWarnings("unchecked")
		ValueOperations<Object, Object> valueOperations = mock(ValueOperations.class);
		when(redisTemplate.opsForValue()).thenReturn(valueOperations);
		OAuth2AccessToken token = new OAuth2AccessToken();
		token.setAccessToken("t");
		when(valueOperations.get("feign-auth:token:svc:client")).thenReturn(token);

		RedisTokenStore store = new RedisTokenStore(redisTemplate, new FeignAuthProperties.Redis());

		assertThat(store.get("svc:client")).isSameAs(token);
	}

}
