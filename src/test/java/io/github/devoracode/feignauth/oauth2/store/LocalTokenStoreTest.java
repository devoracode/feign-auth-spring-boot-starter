package io.github.devoracode.feignauth.oauth2.store;

import io.github.devoracode.feignauth.oauth2.OAuth2AccessToken;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link LocalTokenStore} 的测试：过期令牌惰性剔除、覆盖写入与 remove 的返回值语义。
 */
class LocalTokenStoreTest {

	private final LocalTokenStore store = new LocalTokenStore();

	@Test
	void unknownKeyReadsAsNull() {
		assertThat(store.get("measure:telemetry")).isNull();
	}

	@Test
	void tokenValidInTheFutureIsReturned() {
		OAuth2AccessToken token = token("token-live", System.currentTimeMillis() + 60_000L);
		store.put("measure:telemetry", token);

		assertThat(store.get("measure:telemetry")).isSameAs(token);
	}

	@Test
	void expiredTokenIsNotReturnedAndIsEvictedFromTheCache() {
		store.put("measure:telemetry", token("token-dead", System.currentTimeMillis() - 1L));

		assertThat(store.get("measure:telemetry")).isNull();
		// 只有真的从 Map 里摘掉才算剔除：否则同一 key 会永远停在「读不到但占着内存」的状态
		assertThat(store.remove("measure:telemetry")).isFalse();
	}

	@Test
	void expiryIsEvaluatedOnReadSoRotatedTokenReplacesTheDeadOne() {
		store.put("measure:telemetry", token("token-dead", System.currentTimeMillis() - 1L));
		OAuth2AccessToken refreshed = token("token-refreshed", System.currentTimeMillis() + 60_000L);

		store.put("measure:telemetry", refreshed);

		// 覆盖写入用的还是同一个 key，读到的必须是新令牌
		assertThat(store.get("measure:telemetry")).isSameAs(refreshed);
	}

	@Test
	void removeReportsWhetherAnEntryWasPresent() {
		store.put("measure:telemetry", token("token-1", System.currentTimeMillis() + 60_000L));

		assertThat(store.remove("measure:telemetry")).isTrue();
		// 驱逐冷却与重试判定依赖这个返回值，第二次必须报 false
		assertThat(store.remove("measure:telemetry")).isFalse();
	}

	@Test
	void keysAreIndependentPerClient() {
		store.put("measure:telemetry", token("token-telemetry", System.currentTimeMillis() + 60_000L));
		store.put("measure:event", token("token-event", System.currentTimeMillis() + 60_000L));

		assertThat(store.get("measure:telemetry").getAccessToken()).isEqualTo("token-telemetry");
		assertThat(store.remove("measure:telemetry")).isTrue();
		assertThat(store.get("measure:event").getAccessToken()).isEqualTo("token-event");
	}

	private static OAuth2AccessToken token(String accessToken, long expireAt) {
		OAuth2AccessToken token = new OAuth2AccessToken();
		token.setAccessToken(accessToken);
		token.setExpireAt(expireAt);
		return token;
	}

}
