package io.github.devoracode.feignauth.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.devoracode.feignauth.token.TokenFetcher;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestTemplate;

@Configuration
@EnableConfigurationProperties(FeignAuthProperties.class)
public class FeignAuthAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public RestTemplate restTemplate() {
        return new RestTemplate();
    }

    @Bean
    @ConditionalOnMissingBean
    public ObjectMapper objectMapper() {
        return new ObjectMapper();
    }

    @Bean
    @ConditionalOnMissingBean
    public TokenFetcher tokenFetcher(FeignAuthProperties feignAuthProperties, RestTemplate restTemplate, ObjectMapper objectMapper) {
        return new TokenFetcher(feignAuthProperties, restTemplate, objectMapper);
    }
}
