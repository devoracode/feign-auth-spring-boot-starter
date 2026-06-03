package io.github.devoracode.feignauth.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.devoracode.feignauth.token.TokenFetcher;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestTemplate;

/**
 * Auto-configuration for feign-auth-spring-boot-starter.
 *
 * <p>This class registers the core beans required by the starter:
 * {@link RestTemplate}, {@link ObjectMapper}, and {@link TokenFetcher}.
 * All beans are guarded by {@link ConditionalOnMissingBean}, so applications
 * can override any of them by declaring their own beans.
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
     * Creates a default {@link RestTemplate} bean used for fetching OAuth2 tokens.
     *
     * <p>The bean is only registered when no other {@link RestTemplate} bean is present
     * in the application context.
     *
     * @return a new {@link RestTemplate} instance
     */
    @Bean
    @ConditionalOnMissingBean
    public RestTemplate restTemplate() {
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
     * in the application context.
     *
     * @param feignAuthProperties the configuration properties for all services
     * @param restTemplate        the HTTP client used to call token endpoints
     * @param objectMapper        the JSON parser used to deserialize token responses
     * @return a new {@link TokenFetcher} instance
     */
    @Bean
    @ConditionalOnMissingBean
    public TokenFetcher tokenFetcher(FeignAuthProperties feignAuthProperties, RestTemplate restTemplate, ObjectMapper objectMapper) {
        return new TokenFetcher(feignAuthProperties, restTemplate, objectMapper);
    }
}
