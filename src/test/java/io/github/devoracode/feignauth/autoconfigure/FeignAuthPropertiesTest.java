package io.github.devoracode.feignauth.autoconfigure;

import io.github.devoracode.feignauth.exception.FeignAuthConfigurationException;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.MutablePropertySources;
import org.springframework.core.io.ClassPathResource;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FeignAuthPropertiesTest {

	@Test
	void bindsApplicationDemoConfiguration() throws Exception {
		MutablePropertySources propertySources = new MutablePropertySources();
		YamlPropertySourceLoader loader = new YamlPropertySourceLoader();
		loader.load("application-demo", new ClassPathResource("application-demo.yml"))
				.forEach(propertySources::addLast);

		FeignAuthProperties properties = new Binder(ConfigurationPropertySources.from(propertySources))
				.bind("feign", Bindable.of(FeignAuthProperties.class))
				.orElseThrow(IllegalStateException::new);

		assertThat(properties.getServices()).containsKeys("measure", "measure-2", "psr", "ast", "cons", "cons2", "cons3");

		FeignAuthProperties.Auth measureAuth = properties.getServices().get("measure").getAuth();
		assertThat(measureAuth.getType()).isEqualTo("oauth2");
		assertThat(measureAuth.getTokenUrl()).isEqualTo("https://api.service-a.com/oauth/token");
		assertThat(measureAuth.getTokenHeader()).isEqualTo("x-token");
		assertThat(measureAuth.getRequestFields().getClientId()).isEqualTo("clientId");
		assertThat(measureAuth.getRequestFields().getClientSecret()).isEqualTo("clientSecret");
		assertThat(measureAuth.getRequestFields().getGrantType()).isEqualTo("grantType");
		assertThat(measureAuth.getClients()).hasSize(3);
		assertThat(measureAuth.getClients().get(0).getId()).isEqualTo("xxxxxxeeeee");
		assertThat(measureAuth.getClients().get(0).getPathPrefixes()).containsExactly("/api/measure/event", "/api/measure/event2");
		assertThat(measureAuth.getClients().get(2).isDefaultClient()).isTrue();

		FeignAuthProperties.Auth consAuth = properties.getServices().get("cons").getAuth();
		assertThat(consAuth.isApiKey()).isTrue();
		assertThat(consAuth.resolveHeaderName()).isEqualTo("Authorization");
		assertThat(consAuth.getValue()).isEqualTo("sk-xxxxxxxxxxxxxxxx");
		assertThat(consAuth.getPathPrefixes()).containsExactly("/api/orders");
	}

	@Test
	void defaultsPassStartupValidation() {
		new FeignAuthProperties().afterPropertiesSet();
	}

	@Test
	void poolSizesMustBePositive() {
		FeignAuthProperties maxTotal = new FeignAuthProperties();
		maxTotal.getAuth().getHttp().setMaxTotal(0);
		assertThatThrownBy(maxTotal::afterPropertiesSet)
				.isInstanceOf(FeignAuthConfigurationException.class)
				.hasMessageContaining("feign.auth.http.max-total");

		FeignAuthProperties perRoute = new FeignAuthProperties();
		perRoute.getAuth().getHttp().setDefaultMaxPerRoute(0);
		assertThatThrownBy(perRoute::afterPropertiesSet)
				.hasMessageContaining("feign.auth.http.default-max-per-route");
	}

	@Test
	void timeoutsMustBePositiveAndNonNull() {
		// 0 在 RequestConfig 里是无限等待，负值被 toMillis 钳成 0，同样永远挂起
		FeignAuthProperties zero = new FeignAuthProperties();
		zero.getAuth().getHttp().setSocketTimeout(Duration.ZERO);
		assertThatThrownBy(zero::afterPropertiesSet).hasMessageContaining("feign.auth.http.socket-timeout");

		FeignAuthProperties negative = new FeignAuthProperties();
		negative.getAuth().getHttp().setConnectTimeout(Duration.ofSeconds(-1));
		assertThatThrownBy(negative::afterPropertiesSet).hasMessageContaining("feign.auth.http.connect-timeout");

		FeignAuthProperties absent = new FeignAuthProperties();
		absent.getAuth().getHttp().setConnectionRequestTimeout(null);
		assertThatThrownBy(absent::afterPropertiesSet)
				.hasMessageContaining("feign.auth.http.connection-request-timeout");

		// 不足 1 毫秒的值四舍五入后同样是 0 = 无限等待。启动期必须与 OAuth2TokenRequestClient.toMillis
		// 同一口径：使用方自定义 TokenFetcher Bean 时内嵌配置整组让位、客户端 Bean 不再创建，
		// 启动校验是这类值唯一的拦截点
		FeignAuthProperties subMillisecond = new FeignAuthProperties();
		subMillisecond.getAuth().getHttp().setSocketTimeout(Duration.ofNanos(500_000L));
		assertThatThrownBy(subMillisecond::afterPropertiesSet)
				.hasMessageContaining("feign.auth.http.socket-timeout");
	}

	@Test
	void hugeTimeoutIsAcceptedInsteadOfOverflowingToArithmeticException() {
		// Duration.toMillis() 内部是 multiplyExact：超大时长会先抛 ArithmeticException，
		// 消息里既没有配置键、也不是「invalid configuration」口径
		FeignAuthProperties properties = new FeignAuthProperties();
		properties.getAuth().getHttp().setSocketTimeout(Duration.ofSeconds(Long.MAX_VALUE / 2));

		properties.afterPropertiesSet();
	}

	@Test
	void illegalCacheProviderIsRejectedAtStartup() {
		// provider 拼错时 local/redis 两组 Bean 的条件都不成立，使用方只会看到「没有 RedisConnectionFactory」
		// 这条与根因无关的消息；取值本身应该在启动期就拦住
		FeignAuthProperties properties = new FeignAuthProperties();
		properties.getAuth().getCache().setProvider("rediss");

		assertThatThrownBy(properties::afterPropertiesSet)
				.isInstanceOf(FeignAuthConfigurationException.class)
				.hasMessageContaining("feign.auth.cache.provider");
	}

	@Test
	void nullCacheProviderIsRejectedAtStartup() {
		FeignAuthProperties properties = new FeignAuthProperties();
		properties.getAuth().getCache().setProvider(null);

		assertThatThrownBy(properties::afterPropertiesSet)
				.isInstanceOf(FeignAuthConfigurationException.class)
				.hasMessageContaining("feign.auth.cache.provider");
	}

	@Test
	void cacheProviderAcceptsAnyLetterCase() {
		// @ConditionalOnProperty 的 havingValue 比较忽略大小写（实测 REDIS/Local 都能命中对应 Bean），
		// 校验必须同口径，否则会拒掉本来可用的配置
		for (String provider : new String[] {"local", "LOCAL", "Local", "redis", "REDIS", "Redis"}) {
			FeignAuthProperties properties = new FeignAuthProperties();
			properties.getAuth().getCache().setProvider(provider);
			properties.afterPropertiesSet();
		}
	}

	@Test
	void tokenNumericBoundsAreValidatedPerService() {
		assertThatThrownBy(propertiesWithAuth(auth -> auth.setExpireAheadSeconds(-1))::afterPropertiesSet)
				.hasMessageContaining("feign.services.order.auth.expire-ahead-seconds");
		assertThatThrownBy(propertiesWithAuth(auth -> auth.setTokenExpiresInSeconds(0L))::afterPropertiesSet)
				.hasMessageContaining("feign.services.order.auth.token-expires-in-seconds");
	}

	@Test
	void nonHttpExpiredTokenStatusIsRejectedWhenOnlyHttpCodesAreUsed() {
		// 未配置 response-status-field 时列表只会与 HTTP 码比较，范围外的值必然是笔误
		assertThatThrownBy(propertiesWithAuth(auth -> auth.getExpiredTokenStatuses().add(4010))::afterPropertiesSet)
				.hasMessageContaining("feign.services.order.auth.expired-token-statuses");
	}

	@Test
	void businessStatusCodeIsAllowedTogetherWithResponseStatusField() {
		// 业务状态码（如 10001）是 response-status-field 的正常用法，不得被判为非法配置
		propertiesWithAuth(auth -> {
			auth.setResponseStatusField("code");
			auth.getExpiredTokenStatuses().add(10001);
		}).afterPropertiesSet();
	}

	@Test
	void nullEntryInExpiredTokenStatusesIsAlwaysRejected() {
		assertThatThrownBy(propertiesWithAuth(auth -> auth.getExpiredTokenStatuses().add(null))::afterPropertiesSet)
				.hasMessageContaining("feign.services.order.auth.expired-token-statuses");
	}

	@Test
	void redisKeyPrefixMustNotBeNull() {
		FeignAuthProperties properties = new FeignAuthProperties();
		properties.getAuth().getCache().getRedis().setKeyPrefix(null);
		assertThatThrownBy(properties::afterPropertiesSet).hasMessageContaining("feign.auth.cache.redis.key-prefix");
	}

	@Test
	void servicesWithoutUsableAuthAreNotRejectedAtStartup() {
		// 单个服务的 auth 缺失/非法仍由 P1-b 在请求期快速失败，启动校验不接管这层语义
		FeignAuthProperties properties = new FeignAuthProperties();
		properties.getServices().put("blank", null);
		FeignAuthProperties.Service withoutAuth = new FeignAuthProperties.Service();
		withoutAuth.setAuth(null);
		properties.getServices().put("legacy", withoutAuth);

		properties.afterPropertiesSet();
	}

	private static FeignAuthProperties propertiesWithAuth(
			java.util.function.Consumer<FeignAuthProperties.Auth> customizer) {
		FeignAuthProperties properties = new FeignAuthProperties();
		FeignAuthProperties.Auth auth = new FeignAuthProperties.Auth();
		customizer.accept(auth);
		FeignAuthProperties.Service service = new FeignAuthProperties.Service();
		service.setAuth(auth);
		properties.getServices().put("order", service);
		return properties;
	}

	@Test
	void globalAuthTreeKeepsDefaultsWhenExplicitlySetToNull() {
		FeignAuthProperties properties = new FeignAuthProperties();
		properties.setAuth(null);

		assertThat(properties.getAuth()).isNotNull();
		assertThat(properties.getAuth().isFailOnNoMatch()).isTrue();

		FeignAuthProperties.GlobalAuth globalAuth = new FeignAuthProperties.GlobalAuth();
		globalAuth.setCache(null);
		globalAuth.setHttp(null);
		assertThat(globalAuth.getCache()).isNotNull();
		assertThat(globalAuth.getHttp()).isNotNull();

		FeignAuthProperties.Cache cache = new FeignAuthProperties.Cache();
		cache.setRedis(null);
		assertThat(cache.getRedis().getKeyPrefix()).isEqualTo("feign-auth:");
	}

	@Test
	void illegalClientAuthenticationIsRejectedAtStartup() {
		// 拼错时取号会静默退回默认认证方式，使用方以为切换成功却毫无提示
		assertThatThrownBy(propertiesWithAuth(auth -> auth.setClientAuthentication("secret-basic"))::afterPropertiesSet)
				.isInstanceOf(FeignAuthConfigurationException.class)
				.hasMessageContaining("feign.services.order.auth.client-authentication");

		assertThatThrownBy(propertiesWithAuth(auth -> auth.setClientAuthentication(null))::afterPropertiesSet)
				.hasMessageContaining("feign.services.order.auth.client-authentication");
	}

	@Test
	void clientAuthenticationAcceptsItsThreeValuesInAnyLetterCase() {
		for (String mode : new String[] {"auto", "AUTO", "basic", "Basic", "parameters", "PARAMETERS"}) {
			propertiesWithAuth(auth -> auth.setClientAuthentication(mode)).afterPropertiesSet();
		}
	}

}
