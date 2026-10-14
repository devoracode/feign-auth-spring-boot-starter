package io.github.devoracode.feignauth.support;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class PathUtilsTest {

	@Test
	void matchesPrefixOnlyAtSegmentBoundary() {
		List<String> prefixes = Collections.singletonList("/api/measure");

		assertThat(PathUtils.bestPrefixMatchLength(prefixes, "/api/measure/1")).isEqualTo(12);
		assertThat(PathUtils.bestPrefixMatchLength(prefixes, "/api/measure")).isEqualTo(12);
		assertThat(PathUtils.bestPrefixMatchLength(prefixes, "/api/measurement/1")).isZero();
	}

	@Test
	void treatsTrailingSlashPrefixAsSegmentPrefix() {
		List<String> prefixes = Collections.singletonList("/api/orders/");

		assertThat(PathUtils.bestPrefixMatchLength(prefixes, "/api/orders/1")).isEqualTo(11);
		assertThat(PathUtils.bestPrefixMatchLength(prefixes, "/api/ordersx")).isZero();
	}

	@Test
	void picksLongestSegmentAlignedPrefix() {
		List<String> prefixes = Arrays.asList("/api", "/api/v1");

		assertThat(PathUtils.bestPrefixMatchLength(prefixes, "/api/v1/users")).isEqualTo(7);
		assertThat(PathUtils.bestPrefixMatchLength(prefixes, "/api/v10/users")).isEqualTo(4);
	}

	@Test
	void keepsExistingNoMatchSemantics() {
		List<String> prefixes = Collections.singletonList("/api/v1");

		assertThat(PathUtils.bestPrefixMatchLength(prefixes, "/other")).isZero();
		assertThat(PathUtils.bestPrefixMatchLength(prefixes, "")).isZero();
		assertThat(PathUtils.bestPrefixMatchLength(null, "/api/v1")).isZero();
	}

}
