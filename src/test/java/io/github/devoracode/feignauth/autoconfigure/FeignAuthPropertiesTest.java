package io.github.devoracode.feignauth.autoconfigure;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.MutablePropertySources;
import org.springframework.core.io.ClassPathResource;

import static org.assertj.core.api.Assertions.assertThat;

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

}
