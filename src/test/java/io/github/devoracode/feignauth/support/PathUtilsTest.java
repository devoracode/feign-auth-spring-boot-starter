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

	@Test
	void authorityAndBasePathFallBackIndependently() {
		// 相对 URL 解析成功但没有 authority，路径段照样取得到：两个派生值的回退各自独立
		assertThat(PathUtils.normalizeBaseUrlAuthority("orders")).isNull();
		assertThat(PathUtils.baseUrlPath("orders")).isEqualTo("/orders");

		assertThat(PathUtils.normalizeBaseUrlAuthority("   ")).isNull();
		assertThat(PathUtils.baseUrlPath("   ")).isEmpty();

		// URI.create 失败的输入两半都只落到自己的回退值，不抛异常
		assertThat(PathUtils.normalizeBaseUrlAuthority("http://exa mple.com/v1")).isNull();
		assertThat(PathUtils.baseUrlPath("http://exa mple.com/v1")).isEmpty();

		// 首尾空白在解析前被 trim 掉
		assertThat(PathUtils.normalizeBaseUrlAuthority(" https://API.example.com/v1 "))
				.isEqualTo("https://api.example.com:443");
		assertThat(PathUtils.baseUrlPath(" https://API.example.com/v1 ")).isEqualTo("/v1");

		// 有 authority 但没有路径段
		assertThat(PathUtils.normalizeBaseUrlAuthority("https://api.example.com?x=1"))
				.isEqualTo("https://api.example.com:443");
		assertThat(PathUtils.baseUrlPath("https://api.example.com?x=1")).isEmpty();
	}

	@Test
	void parseBaseUrlAgreesWithBothSingleValueWrappers() {
		List<String> urls = Arrays.asList("https://api.example.com/v1/", "https://API.example.com:443/",
				"http://localhost", "http://localhost:8080/x", "orders", "my-service", "   ", null,
				"http://exa mple.com/v1", "https://api.example.com", "https://api.example.com/api//v1/",
				" https://API.example.com/v1 ", "https://api.example.com?x=1");

		for (String url : urls) {
			PathUtils.BaseUrlParts parts = PathUtils.parseBaseUrl(url);
			// 两个既有方法现在只是 parseBaseUrl 的薄封装，逐输入比对新旧口径必须完全一致
			assertThat(parts.getAuthority()).isEqualTo(PathUtils.normalizeBaseUrlAuthority(url));
			assertThat(parts.getBasePath()).isEqualTo(PathUtils.baseUrlPath(url));
		}

		assertThat(PathUtils.parseBaseUrl("   ")).isSameAs(PathUtils.BaseUrlParts.EMPTY);
		assertThat(PathUtils.parseBaseUrl("http://exa mple.com/v1")).isSameAs(PathUtils.BaseUrlParts.EMPTY);
		assertThat(PathUtils.BaseUrlParts.EMPTY.getAuthority()).isNull();
		assertThat(PathUtils.BaseUrlParts.EMPTY.getBasePath()).isEmpty();
	}

}
