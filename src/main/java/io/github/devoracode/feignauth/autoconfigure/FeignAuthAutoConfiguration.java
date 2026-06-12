package io.github.devoracode.feignauth.autoconfigure;

import com.fasterxml.jackson.databind.ObjectMapper;
import feign.RequestInterceptor;
import io.github.devoracode.feignauth.feign.ServiceMatcher;
import io.github.devoracode.feignauth.header.HeaderCustomizer;
import io.github.devoracode.feignauth.header.HeaderManager;
import io.github.devoracode.feignauth.oauth2.OAuth2ClientMatcher;
import io.github.devoracode.feignauth.oauth2.OAuth2TokenRequestClient;
import io.github.devoracode.feignauth.oauth2.OAuth2TokenResponseParser;
import io.github.devoracode.feignauth.oauth2.TokenFetcher;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.AutoConfigureAfter;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.jackson.JacksonAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestTemplate;

import java.util.Collections;
import java.util.List;

/**
 * Auto-configuration for feign-auth-spring-boot-starter.
 *
 * @author Wenjie Liu
 * @since 1.0.0
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnClass(RequestInterceptor.class)
@AutoConfigureAfter(JacksonAutoConfiguration.class)
@EnableConfigurationProperties(FeignAuthProperties.class)
public class FeignAuthAutoConfiguration {

	@Bean("feignAuthRestTemplate")
	public RestTemplate feignAuthRestTemplate() {
		return new RestTemplate();
	}

	@Bean
	@ConditionalOnMissingBean
	public ServiceMatcher serviceMatcher(FeignAuthProperties properties) {
		return new ServiceMatcher(properties);
	}

	@Bean
	@ConditionalOnMissingBean
	public OAuth2ClientMatcher oAuth2ClientMatcher() {
		return new OAuth2ClientMatcher();
	}

	@Bean
	@ConditionalOnMissingBean
	public ObjectMapper objectMapper() {
		return new ObjectMapper();
	}

	@Configuration(proxyBeanMethods = false)
	@ConditionalOnMissingBean(TokenFetcher.class)
	static class TokenFetcherConfiguration {

		@Bean
		TokenFetcher tokenFetcher(FeignAuthProperties properties, OAuth2ClientMatcher clientMatcher,
				@Qualifier("feignAuthRestTemplate") RestTemplate restTemplate, ObjectMapper objectMapper,
				                  ObjectProvider<List<HeaderCustomizer>> headerCustomizersProvider) {
			OAuth2TokenResponseParser responseParser = new OAuth2TokenResponseParser(objectMapper);
			List<HeaderCustomizer> customizers =
					headerCustomizersProvider.getIfAvailable(Collections::emptyList);
			HeaderManager headerManager = new HeaderManager(customizers);
			OAuth2TokenRequestClient tokenRequestClient = new OAuth2TokenRequestClient(restTemplate,
					responseParser, headerManager);
			return new TokenFetcher(properties, clientMatcher, tokenRequestClient);
		}

	}

}
