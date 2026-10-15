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
	}

	@Test
	void tokenNumericBoundsAreValidatedPerService() {
		assertThatThrownBy(propertiesWithAuth(auth -> auth.setExpireAheadSeconds(-1))::afterPropertiesSet)
				.hasMessageContaining("feign.services.order.auth.expire-ahead-seconds");
		assertThatThrownBy(propertiesWithAuth(auth -> auth.setTokenExpiresInSeconds(0L))::afterPropertiesSet)
				.hasMessageContaining("feign.services.order.auth.token-expires-in-seconds");
		assertThatThrownBy(propertiesWithAuth(auth -> auth.getExpiredTokenStatuses().add(4010))::afterPropertiesSet)
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

}
