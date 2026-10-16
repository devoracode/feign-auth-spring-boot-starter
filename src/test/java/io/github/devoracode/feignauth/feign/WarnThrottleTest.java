package io.github.devoracode.feignauth.feign;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class WarnThrottleTest {

	@Test
	void firstOccurrenceOfAKeyWarnsAndRepeatsAreSuppressed() {
		WarnThrottle throttle = new WarnThrottle();

		assertThat(throttle.shouldWarn("https://api.example.com")).isTrue();
		assertThat(throttle.shouldWarn("https://api.example.com")).isFalse();
		assertThat(throttle.shouldWarn("https://other.example.com")).isTrue();
	}

	@Test
	void nullKeyNeverWarns() {
		WarnThrottle throttle = new WarnThrottle();

		assertThat(throttle.shouldWarn(null)).isFalse();
	}

	@Test
	void trackingStopsAfterTheKeyCapIsReached() {
		WarnThrottle throttle = new WarnThrottle();

		for (int i = 0; i < WarnThrottle.MAX_TRACKED_KEYS; i++) {
			assertThat(throttle.shouldWarn("key-" + i)).isTrue();
		}

		// 达到上限后新 key 不再记录：宁可少一条提醒，也不让 Set 无界增长
		assertThat(throttle.shouldWarn("key-" + WarnThrottle.MAX_TRACKED_KEYS)).isFalse();
		assertThat(throttle.shouldWarn("key-0")).isFalse();
	}

}
