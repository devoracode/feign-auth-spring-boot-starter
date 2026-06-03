package io.github.devoracode.feignauth.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.devoracode.feignauth.token.TokenFetcher;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestTemplate;

/**
 * Auto-configuration for feign-auth-spring-boot-starter.
 *
 * <p>This class registers the core beans required by the starter:
 * a dedicated {@link RestTemplate} (bean name {@code feignAuthRestTemplate}),
 * {@link ObjectMapper}, and {@link TokenFetcher}.
 *
 * <p>The {@link RestTemplate} used by this starter is always registered under its
 * own bean name and never conflicts with a {@link RestTemplate} declared by the
 * application. The {@link ObjectMapper} and {@link TokenFetcher} beans are guarded
 * by {@link ConditionalOnMissingBean} so the application can override them if needed.
 *
 * <p>The configuration is activated automatically via Spring Boot's
 * auto-configuration mechanism. No additional setup is needed beyond
 * adding the starter dependency.
 *
 * @author Wenjie Liu
 * @since 1.0.0
 */
@Configuration
@EnableConfigurationProperties(FeignAuthProperties.class)
public class FeignAuthAutoConfiguration {

    /**
     * Creates a dedicated {@link RestTemplate} bean used exclusively by this starter
     * to fetch OAuth2 tokens.
     *
     * <p>This bean is always registered under the name {@code feignAuthRestTemplate}
     * and is independent of any {@link RestTemplate} bean declared by the application.
     *
     * @return a new {@link RestTemplate} instance
     */
    @Bean("feignAuthRestTemplate")
    public RestTemplate feignAuthRestTemplate() {
        return new RestTemplate();
    }

    /**
     * Creates a default {@link ObjectMapper} bean used for parsing token responses.
     *
     * <p>The bean is only registered when no other {@link ObjectMapper} bean is present
     * in the application context.
     *
     * @return a new {@link ObjectMapper} instance
     */
    @Bean
    @ConditionalOnMissingBean
    public ObjectMapper objectMapper() {
        return new ObjectMapper();
    }

    /**
     * Creates a {@link TokenFetcher} bean responsible for acquiring and caching OAuth2 tokens.
     *
     * <p>The bean is only registered when no other {@link TokenFetcher} bean is present
     * in the application context. It uses the starter's own
     * {@code feignAuthRestTemplate} bean, not the application's {@link RestTemplate}.
     *
     * @param feignAuthProperties the configuration properties for all services
     * @param restTemplate        the dedicated HTTP client used to call token endpoints
     * @param objectMapper        the JSON parser used to deserialize token responses
     * @return a new {@link TokenFetcher} instance
     */
    @Bean
    @ConditionalOnMissingBean
    public TokenFetcher tokenFetcher(FeignAuthProperties feignAuthProperties,
                                     @Qualifier("feignAuthRestTemplate") RestTemplate restTemplate,
                                     ObjectMapper objectMapper) {
        return new TokenFetcher(feignAuthProperties, restTemplate, objectMapper);
    }
}
