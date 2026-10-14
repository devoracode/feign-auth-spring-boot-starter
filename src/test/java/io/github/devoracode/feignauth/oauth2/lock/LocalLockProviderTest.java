package io.github.devoracode.feignauth.oauth2.lock;

import io.github.devoracode.feignauth.exception.FeignAuthTokenException;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LocalLockProviderTest {

	private final LocalLockProvider provider = new LocalLockProvider();

	@Test
	void serializesConcurrentCallsForSameKey() throws Exception {
		int threads = 8;
		int incrementsPerThread = 500;
		AtomicInteger counter = new AtomicInteger();
		ExecutorService executor = Executors.newFixedThreadPool(threads);
		try {
			CountDownLatch start = new CountDownLatch(1);
			Future<?>[] futures = new Future<?>[threads];
			for (int i = 0; i < threads; i++) {
				futures[i] = executor.submit(() -> {
					start.await();
					for (int j = 0; j < incrementsPerThread; j++) {
						provider.execute("svc:client", () -> counter.getAndIncrement());
					}
					return null;
				});
			}
			start.countDown();
			for (Future<?> future : futures) {
				future.get(30, TimeUnit.SECONDS);
			}
		}
		finally {
			executor.shutdownNow();
		}
		assertThat(counter.get()).isEqualTo(threads * incrementsPerThread);
	}

	@Test
	void blocksSecondCallerUntilFirstReleasesSameKey() throws Exception {
		CountDownLatch entered = new CountDownLatch(1);
		CountDownLatch release = new CountDownLatch(1);
		AtomicInteger secondDone = new AtomicInteger();
		ExecutorService executor = Executors.newFixedThreadPool(2);
		try {
			executor.submit(() -> provider.execute("key", () -> {
				entered.countDown();
				release.await();
				return null;
			}));
			assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();

			executor.submit(() -> provider.execute("key", () -> secondDone.incrementAndGet()));
			Thread.sleep(200L);
			assertThat(secondDone.get()).isZero();

			release.countDown();
			long deadline = System.currentTimeMillis() + 5_000L;
			while (secondDone.get() == 0 && System.currentTimeMillis() < deadline) {
				Thread.sleep(20L);
			}
			assertThat(secondDone.get()).isEqualTo(1);
		}
		finally {
			executor.shutdownNow();
		}
	}

	@Test
	void wrapsCallableFailure() {
		assertThatThrownBy(() -> this.provider.execute("key", () -> {
			throw new IllegalStateException("boom");
		}))
				.isInstanceOf(FeignAuthTokenException.class)
				.hasMessageContaining("key")
				.hasCauseInstanceOf(IllegalStateException.class);
	}

	@Test
	void releasesLockAfterCallableFailure() {
		assertThatThrownBy(() -> this.provider.execute("key", () -> {
			throw new IllegalStateException("boom");
		})).isInstanceOf(FeignAuthTokenException.class);

		assertThat(this.provider.execute("key", () -> "ok")).isEqualTo("ok");
	}

}
