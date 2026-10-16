package io.github.devoracode.feignauth.feign;

import io.github.devoracode.feignauth.autoconfigure.FeignAuthProperties;
import io.github.devoracode.feignauth.exception.FeignAuthConfigurationException;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link ServiceMatcher} 的表征测试：匹配优先级、fallback 选择与异常文案在单趟匹配重写前后必须逐字一致。
 */
class ServiceMatcherTest {

	/** 与 {@code ServiceMatcher.MAX_URL_CACHE_ENTRIES} 同值；写成字面量是为了让本文件在重写前也能编译。 */
	private static final int EXPECTED_URL_CACHE_CAP = 256;

	@Test
	void longestConfiguredPrefixWins() {
		ServiceMatcher matcher = new ServiceMatcher(properties(
				service("order", "https://api.example.com", "api-key", "/api/orders"),
				service("account", "https://api.example.com", "api-key", "/api")));

		assertThat(matcher.match("https://api.example.com", "/api/orders/1").getServiceName()).isEqualTo("order");
	}

	@Test
	void equalLongestPrefixesAcrossServicesAreAmbiguous() {
		ServiceMatcher matcher = new ServiceMatcher(properties(
				service("order-a", "https://api.example.com", "api-key", "/api/orders"),
				service("order-b", "https://api.example.com", "api-key", "/api/orders")));

		assertThatThrownBy(() -> matcher.match("https://api.example.com", "/api/orders/1"))
				.isInstanceOf(FeignAuthConfigurationException.class)
				.hasMessage("Multiple services matched requestPath=/api/orders/1 for baseUrl=https://api.example.com");
	}

	@Test
	void longerPrefixAppearingLaterClearsTheAmbiguity() {
		ServiceMatcher matcher = new ServiceMatcher(properties(
				service("detail", "https://api.example.com", "api-key", "/api/orders"),
				service("summary", "https://api.example.com", "api-key", "/api/orders"),
				service("deep", "https://api.example.com", "api-key", "/api/orders/item")));

		// 前两个服务等长（歧义），更长的服务排在其后：歧义标记必须被重置，否则合法配置会被误判
		assertThat(matcher.match("https://api.example.com", "/api/orders/item/9").getServiceName()).isEqualTo("deep");
	}

	@Test
	void fallbackServiceIsUsedOnlyWhenNoPrefixMatches() {
		ServiceMatcher matcher = new ServiceMatcher(properties(
				service("order", "https://api.example.com", "api-key", "/api/orders"),
				service("default", "https://api.example.com", "api-key")));

		assertThat(matcher.match("https://api.example.com", "/api/other").getServiceName()).isEqualTo("default");
		assertThat(matcher.match("https://api.example.com", "/api/orders/1").getServiceName()).isEqualTo("order");
	}

	@Test
	void multipleFallbackServicesAreRejected() {
		ServiceMatcher matcher = new ServiceMatcher(properties(
				service("default-a", "https://api.example.com", "api-key"),
				service("default-b", "https://api.example.com", "api-key")));

		assertThatThrownBy(() -> matcher.match("https://api.example.com", "/api/other"))
				.isInstanceOf(FeignAuthConfigurationException.class)
				.hasMessage("Multiple fallback services configured for baseUrl=https://api.example.com");
	}

	@Test
	void invalidAuthConfigsAreReportedTogetherWithTheirReasons() {
		ServiceMatcher matcher = new ServiceMatcher(properties(
				service("order", "https://api.example.com", null, "/api/orders"),
				service("account", "https://api.example.com", "oauth", "/api/accounts")));

		assertThatThrownBy(() -> matcher.match("https://api.example.com", "/api/orders/1"))
				.isInstanceOf(FeignAuthConfigurationException.class)
				.hasMessage("FeignAuth: service(s) matched baseUrl=https://api.example.com but have invalid auth"
						+ " config: order (auth config is missing, expected auth.type 'oauth2' or 'api-key'); "
						+ "account (unsupported auth.type 'oauth', expected 'oauth2' or 'api-key')");
	}

	@Test
	void validServiceStillWinsWhenAnotherServiceOnTheSameAuthorityHasInvalidAuth() {
		// 非法配置只在「该 authority 下一个合法服务都没有」时才报错，此语义必须保留
		ServiceMatcher matcher = new ServiceMatcher(properties(
				service("order", "https://api.example.com", "api-key", "/api/orders"),
				service("broken", "https://api.example.com", "apikey", "/api/accounts")));

		assertThat(matcher.match("https://api.example.com", "/api/orders/1").getServiceName()).isEqualTo("order");
	}

	@Test
	void configuredBaseUrlPathIsStrippedBeforePrefixMatching() {
		ServiceMatcher matcher = new ServiceMatcher(properties(
				service("order", "https://api.example.com/api/v1", "api-key", "/orders")));

		assertThat(matcher.match("https://api.example.com", "/api/v1/orders/1").getServiceName()).isEqualTo("order");
	}

	@Test
	void authorityComparisonNormalizesCaseAndDefaultPort() {
		ServiceMatcher matcher = new ServiceMatcher(properties(
				service("order", "https://API.example.com", "api-key", "/api/orders")));

		assertThat(matcher.match("https://api.example.com:443", "/api/orders/1").getServiceName()).isEqualTo("order");
		assertThat(matcher.match("http://api.example.com:443", "/api/orders/1")).isNull();
	}

	@Test
	void unparsableBaseUrlDegradesToRawStringComparison() {
		ServiceMatcher matcher = new ServiceMatcher(properties(
				service("order", "Order-Service ", "api-key", "/api/orders")));

		// 服务发现名不是 URI：退化为去空白+小写的原串，保持旧有字符串比较语义
		assertThat(matcher.match("order-service", "/api/orders/1").getServiceName()).isEqualTo("order");
	}

	@Test
	void requestOnUnknownAuthorityReturnsNull() {
		ServiceMatcher matcher = new ServiceMatcher(properties(
				service("order", "https://api.example.com", "api-key", "/api/orders")));

		assertThat(matcher.match("https://other.example.com", "/api/orders/1")).isNull();
		// 有服务命中 authority 但没有任何前缀命中、也没有 fallback 时同样是 null
		assertThat(matcher.match("https://api.example.com", "/api/unknown")).isNull();
	}

	@Test
	void emptyServicesNeverMatch() {
		ServiceMatcher matcher = new ServiceMatcher(new FeignAuthProperties());

		assertThat(matcher.match("https://api.example.com", "/api/orders/1")).isNull();
	}

	@Test
	void oauth2ClientPrefixesParticipateInLongestMatch() {
		ServiceMatcher matcher = new ServiceMatcher(properties(
				oauth2Service("telemetry", "https://api.example.com", "/api/measure", "/api"),
				oauth2Service("billing", "https://api.example.com", "/api/bill")));

		assertThat(matcher.match("https://api.example.com", "/api/measure/read/1").getServiceName())
				.isEqualTo("telemetry");
	}

	@Test
	void urlDerivedValuesAreMemoizedUpToTheCap() throws Exception {
		ServiceMatcher matcher = new ServiceMatcher(properties(
				service("order", "https://api.example.com", "api-key", "/api/orders")));
		ConcurrentMap<?, ?> authorityCache = cache(matcher, "authorityCache");

		matcher.match("https://api.example.com", "/api/orders/1");
		int afterFirstRequest = authorityCache.size();
		matcher.match("https://api.example.com", "/api/orders/2");

		// 同一 URL 重复请求不再重新解析，也不会往缓存里追加条目
		assertThat(authorityCache).hasSize(afterFirstRequest);
		assertThat(afterFirstRequest).isPositive();

		// 动态 target URL 不该让缓存无界增长
		for (int i = 0; i < EXPECTED_URL_CACHE_CAP + 50; i++) {
			matcher.match("https://host-" + i + ".example.com", "/api/orders/1");
		}
		assertThat(authorityCache).hasSize(EXPECTED_URL_CACHE_CAP);
	}

	private static ConcurrentMap<?, ?> cache(ServiceMatcher matcher, String fieldName) throws Exception {
		Field field = ServiceMatcher.class.getDeclaredField(fieldName);
		field.setAccessible(true);
		return (ConcurrentMap<?, ?>) field.get(matcher);
	}

	@SafeVarargs
	private static FeignAuthProperties properties(Map.Entry<String, FeignAuthProperties.Service>... services) {
		Map<String, FeignAuthProperties.Service> map = new LinkedHashMap<>();
		for (Map.Entry<String, FeignAuthProperties.Service> service : services) {
			map.put(service.getKey(), service.getValue());
		}
		FeignAuthProperties properties = new FeignAuthProperties();
		properties.setServices(map);
		return properties;
	}

	private static Map.Entry<String, FeignAuthProperties.Service> service(String name, String baseUrl, String type,
			String... prefixes) {
		FeignAuthProperties.Service service = new FeignAuthProperties.Service();
		service.setBaseUrl(baseUrl);
		if (type == null) {
			// P1-b 语义：per-service auth 显式置空不做兜底，匹配期按非法配置快速失败
			service.setAuth(null);
			return entry(name, service);
		}
		FeignAuthProperties.Auth auth = new FeignAuthProperties.Auth();
		auth.setType(type);
		auth.setValue("sk-test");
		if (prefixes.length > 0) {
			auth.setPathPrefixes(Arrays.asList(prefixes));
		}
		service.setAuth(auth);
		return entry(name, service);
	}

	private static Map.Entry<String, FeignAuthProperties.Service> oauth2Service(String name, String baseUrl,
			String... prefixes) {
		FeignAuthProperties.Service service = new FeignAuthProperties.Service();
		service.setBaseUrl(baseUrl);
		FeignAuthProperties.Auth auth = new FeignAuthProperties.Auth();
		auth.setType("oauth2");
		auth.setTokenUrl(baseUrl + "/oauth/token");
		List<FeignAuthProperties.Client> clients = new ArrayList<>();
		for (String prefix : prefixes) {
			FeignAuthProperties.Client client = new FeignAuthProperties.Client();
			client.setId(name);
			client.setSecret("secret");
			client.setPathPrefixes(Collections.singletonList(prefix));
			clients.add(client);
		}
		auth.setClients(clients);
		service.setAuth(auth);
		return entry(name, service);
	}

	private static Map.Entry<String, FeignAuthProperties.Service> entry(String name,
			FeignAuthProperties.Service service) {
		return new AbstractMap.SimpleEntry<>(name, service);
	}

}
