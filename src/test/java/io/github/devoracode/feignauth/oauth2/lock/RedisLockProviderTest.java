package io.github.devoracode.feignauth.oauth2.lock;

import io.github.devoracode.feignauth.autoconfigure.FeignAuthProperties;
import io.github.devoracode.feignauth.exception.FeignAuthTokenException;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.script.RedisScript;

import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RedisLockProviderTest {

	private final StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);

	@SuppressWarnings("unchecked")
	private final ValueOperations<String, String> valueOperations = mock(ValueOperations.class);

	private final RedisLockProvider provider = new RedisLockProvider(this.redisTemplate,
			new FeignAuthProperties.Redis());

	@Test
	@SuppressWarnings("unchecked")
	void returnsCallableResultAndUnlocksOnSuccess() {
		when(this.redisTemplate.opsForValue()).thenReturn(this.valueOperations);
		when(this.valueOperations.setIfAbsent(anyString(), anyString(), anyLong(), any(TimeUnit.class)))
				.thenReturn(true);

		assertThat(this.provider.execute("svc:client", () -> "token")).isEqualTo("token");
	}

	@Test
	@SuppressWarnings("unchecked")
	void unlockFailureDoesNotMaskCallableException() {
		when(this.redisTemplate.opsForValue()).thenReturn(this.valueOperations);
		when(this.valueOperations.setIfAbsent(anyString(), anyString(), anyLong(), any(TimeUnit.class)))
				.thenReturn(true);
		when(this.redisTemplate.execute(any(RedisScript.class), any(List.class), any()))
				.thenThrow(new IllegalStateException("redis down"));

		assertThatThrownBy(() -> this.provider.execute("svc:client", () -> {
			throw new IllegalArgumentException("business failure");
		}))
				.isInstanceOf(FeignAuthTokenException.class)
				.hasCauseInstanceOf(IllegalArgumentException.class)
				.hasStackTraceContaining("business failure");
	}

	@Test
	@SuppressWarnings("unchecked")
	void unlockFailureAfterSuccessfulCallStillReturnsResult() {
		when(this.redisTemplate.opsForValue()).thenReturn(this.valueOperations);
		when(this.valueOperations.setIfAbsent(anyString(), anyString(), anyLong(), any(TimeUnit.class)))
				.thenReturn(true);
		when(this.redisTemplate.execute(any(RedisScript.class), any(List.class), any()))
				.thenThrow(new IllegalStateException("redis down"));

		assertThat(this.provider.execute("svc:client", () -> "token")).isEqualTo("token");
	}

}
