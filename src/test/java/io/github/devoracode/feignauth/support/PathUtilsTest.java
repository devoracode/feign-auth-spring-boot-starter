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

	@Test
	void collapsesRepeatedSlashesAndDropsTrailingSlashForMatching() {
		assertThat(PathUtils.normalizeRequestPath("/api//measure/read/1")).isEqualTo("/api/measure/read/1");
		assertThat(PathUtils.normalizeRequestPath("/api/measure/")).isEqualTo("/api/measure");
		assertThat(PathUtils.normalizeRequestPath("api/measure?x=1")).isEqualTo("/api/measure");
		assertThat(PathUtils.normalizeRequestPath("/")).isEqualTo("/");
		assertThat(PathUtils.normalizeRequestPath("//")).isEqualTo("/");
		assertThat(PathUtils.normalizeRequestPath("")).isEmpty();
		assertThat(PathUtils.normalizeRequestPath(null)).isEmpty();
	}

	@Test
	void normalizesAuthorityWithDefaultPortsAndLowercase() {
		assertThat(PathUtils.normalizeBaseUrlAuthority("https://API.example.com")).isEqualTo("https://api.example.com:443");
		assertThat(PathUtils.normalizeBaseUrlAuthority("https://api.example.com:443/")).isEqualTo("https://api.example.com:443");
		assertThat(PathUtils.normalizeBaseUrlAuthority("http://localhost")).isEqualTo("http://localhost:80");
		assertThat(PathUtils.normalizeBaseUrlAuthority("https://api.example.com/v1")).isEqualTo("https://api.example.com:443");
		assertThat(PathUtils.normalizeBaseUrlAuthority("https://api.example.com:8443")).isEqualTo("https://api.example.com:8443");
		assertThat(PathUtils.normalizeBaseUrlAuthority("my-service")).isNull();
		assertThat(PathUtils.normalizeBaseUrlAuthority(null)).isNull();
	}

	@Test
	void extractsBaseUrlPathWithoutTrailingSlash() {
		assertThat(PathUtils.baseUrlPath("https://api.example.com/v1/")).isEqualTo("/v1");
		assertThat(PathUtils.baseUrlPath("https://api.example.com")).isEmpty();
		assertThat(PathUtils.baseUrlPath("https://api.example.com/v1/deep")).isEqualTo("/v1/deep");
		// 与请求路径同口径折叠连续斜杠，否则 base-url 写成 /api//v1 时剥不掉已归一化的请求路径
		assertThat(PathUtils.baseUrlPath("https://api.example.com/api//v1/")).isEqualTo("/api/v1");
		assertThat(PathUtils.baseUrlPath(null)).isEmpty();
	}

}
