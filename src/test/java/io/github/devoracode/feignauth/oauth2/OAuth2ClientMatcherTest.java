package io.github.devoracode.feignauth.oauth2;

import io.github.devoracode.feignauth.autoconfigure.FeignAuthProperties;
import io.github.devoracode.feignauth.exception.FeignAuthConfigurationException;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link OAuth2ClientMatcher} 的测试：最长前缀优先、default client 兜底，
 * 以及三类配置歧义/缺失的快速失败文案。
 */
class OAuth2ClientMatcherTest {

	private final OAuth2ClientMatcher matcher = new OAuth2ClientMatcher();

	@Test
	void longestPathPrefixWinsOverShorterOne() {
		FeignAuthProperties.Service service = service(client("event", "/api/measure"),
				client("telemetry", "/api/measure/telemetry"), client("fallback"));

		assertThat(matcher.match("measure", service, "/api/measure/telemetry/1").getId()).isEqualTo("telemetry");
		assertThat(matcher.match("measure", service, "/api/measure/read/1").getId()).isEqualTo("event");
	}

	@Test
	void defaultClientIsOnlyUsedWhenNoPrefixMatches() {
		FeignAuthProperties.Service service = service(client("telemetry", "/api/measure"), client("fallback"));

		assertThat(matcher.match("measure", service, "/api/other/1").getId()).isEqualTo("fallback");
		// 前缀命中的优先级高于列表顺序：default client 排在前面也一样
		assertThat(matcher.match("measure", service, "/api/measure/1").getId()).isEqualTo("telemetry");
	}

	@Test
	void repeatedSlashesInTheRequestPathAreCollapsedBeforeMatching() {
		FeignAuthProperties.Service service = service(client("telemetry", "/api/measure/telemetry"),
				client("fallback"));

		// 与 RequestInterceptor / ErrorDecoder 同一口径，否则 /api//x 会静默落到 default client
		assertThat(matcher.match("measure", service, "/api//measure//telemetry/1").getId()).isEqualTo("telemetry");
	}

	@Test
	void prefixMatchesOnlyOnSegmentBoundaries() {
		FeignAuthProperties.Service service = service(client("telemetry", "/api/measure"), client("fallback"));

		// /api/measurement 不是 /api/measure 的前缀命中，必须落到 default client
		assertThat(matcher.match("measure", service, "/api/measurement/1").getId()).isEqualTo("fallback");
	}

	@Test
	void trailingSlashInAPrefixStillBehavesAsSegmentPrefix() {
		FeignAuthProperties.Service service = service(client("telemetry", "/api/measure/"), client("fallback"));

		assertThat(matcher.match("measure", service, "/api/measure/1").getId()).isEqualTo("telemetry");
		assertThat(matcher.match("measure", service, "/api/measurex").getId()).isEqualTo("fallback");
	}

	@Test
	void nullEntriesInTheClientsListAreSkipped() {
		List<FeignAuthProperties.Client> clients = new ArrayList<>();
		clients.add(null);
		clients.add(client("telemetry", "/api/measure"));
		clients.add(null);

		assertThat(matcher.match("measure", service(clients), "/api/measure/1").getId()).isEqualTo("telemetry");
	}

	@Test
	void noClientsConfiguredFailsFastNamingTheService() {
		FeignAuthProperties.Service service = service();

		assertThatThrownBy(() -> matcher.match("measure", service, "/api/measure/1"))
				.isInstanceOf(FeignAuthConfigurationException.class)
				.hasMessage("No OAuth2 clients configured for service: measure");
	}

	@Test
	void equalLongestPrefixesAcrossClientsAreAmbiguous() {
		FeignAuthProperties.Service service = service(client("first", "/api/measure"),
				client("second", "/api/measure"));

		assertThatThrownBy(() -> matcher.match("measure", service, "/api/measure/1"))
				.isInstanceOf(FeignAuthConfigurationException.class)
				.hasMessage("Multiple OAuth2 clients matched requestPath=/api/measure/1 for service=measure");
	}

	@Test
	void multipleDefaultClientsAreRejected() {
		FeignAuthProperties.Service service = service(client("telemetry", "/api/measure"), client("fallback-a"),
				client("fallback-b"));

		// 前缀不命中时才轮到兜底，两个兜底无法任取其一
		assertThatThrownBy(() -> matcher.match("measure", service, "/api/other/1"))
				.isInstanceOf(FeignAuthConfigurationException.class)
				.hasMessage("Multiple default OAuth2 clients configured for service: measure");
	}

	@Test
	void noMatchWithoutDefaultClientFailsFast() {
		FeignAuthProperties.Service service = service(client("telemetry", "/api/measure"));

		assertThatThrownBy(() -> matcher.match("measure", service, "/api/other/1"))
				.isInstanceOf(FeignAuthConfigurationException.class)
				.hasMessage("No OAuth2 client matched requestPath=/api/other/1 for service=measure");
	}

	// ── 装配辅助 ──────────────────────────────────────────────────────────

	private static FeignAuthProperties.Service service(FeignAuthProperties.Client... clients) {
		return service(Arrays.asList(clients));
	}

	private static FeignAuthProperties.Service service(List<FeignAuthProperties.Client> clients) {
		FeignAuthProperties.Auth auth = new FeignAuthProperties.Auth();
		auth.setType("oauth2");
		auth.setTokenUrl("https://api.example.com/oauth/token");
		auth.setClients(clients);

		FeignAuthProperties.Service service = new FeignAuthProperties.Service();
		service.setBaseUrl("https://api.example.com");
		service.setAuth(auth);
		return service;
	}

	private static FeignAuthProperties.Client client(String id, String... pathPrefixes) {
		FeignAuthProperties.Client client = new FeignAuthProperties.Client();
		client.setId(id);
		client.setSecret("secret-" + id);
		client.setPathPrefixes(Collections.unmodifiableList(Arrays.asList(pathPrefixes)));
		return client;
	}

}
