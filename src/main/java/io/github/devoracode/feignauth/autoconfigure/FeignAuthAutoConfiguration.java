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
import io.github.devoracode.feignauth.oauth2.lock.LocalLockProvider;
import io.github.devoracode.feignauth.oauth2.lock.LockProvider;
import io.github.devoracode.feignauth.oauth2.lock.RedisLockProvider;
import io.github.devoracode.feignauth.oauth2.store.LocalTokenStore;
import io.github.devoracode.feignauth.oauth2.store.RedisTokenStore;
import io.github.devoracode.feignauth.oauth2.store.TokenStore;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfigureAfter;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration;
import org.springframework.boot.autoconfigure.jackson.JacksonAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.Collections;
import java.util.List;

/**
 * feign-auth-spring-boot-starter 的自动配置。
 *
 * @author Wenjie Liu
 * @since 1.0.0
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnClass(RequestInterceptor.class)
@AutoConfigureAfter(value = {JacksonAutoConfiguration.class, RedisAutoConfiguration.class})
@EnableConfigurationProperties(FeignAuthProperties.class)
public class FeignAuthAutoConfiguration {

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

    @Bean
    @ConditionalOnMissingBean
    public HeaderManager headerManager(ObjectProvider<List<HeaderCustomizer>> customizersProvider) {
        List<HeaderCustomizer> customizers = customizersProvider.getIfAvailable(Collections::emptyList);
        return new HeaderManager(customizers);
    }

    @Bean
    @ConditionalOnMissingBean(TokenStore.class)
    @ConditionalOnProperty(prefix = "feign.auth.cache", name = "provider", havingValue = "local",
        matchIfMissing = true)
    public TokenStore tokenStore() {
        return new LocalTokenStore();
    }

    @Bean
    @ConditionalOnMissingBean(TokenStore.class)
    @ConditionalOnClass(RedisTemplate.class)
    @ConditionalOnBean(RedisTemplate.class)
    @ConditionalOnProperty(prefix = "feign.auth.cache", name = "provider", havingValue = "redis")
    public TokenStore redisTokenStore(RedisTemplate<Object, Object> redisTemplate,
                                      FeignAuthProperties properties) {
        return new RedisTokenStore(redisTemplate, properties.getAuth().getCache().getRedis());
    }

    @Bean
    @ConditionalOnMissingBean(LockProvider.class)
    @ConditionalOnProperty(prefix = "feign.auth.cache", name = "provider",
        havingValue = "local", matchIfMissing = true)
    public LockProvider lockProvider() {
        return new LocalLockProvider();
    }

    @Bean
    @ConditionalOnMissingBean(LockProvider.class)
    @ConditionalOnClass(StringRedisTemplate.class)
    @ConditionalOnBean(StringRedisTemplate.class)
    @ConditionalOnProperty(prefix = "feign.auth.cache",
        name = "provider", havingValue = "redis")
    public LockProvider redisLockProvider(StringRedisTemplate redisTemplate, FeignAuthProperties properties) {
        return new RedisLockProvider(redisTemplate, properties.getAuth().getCache().getRedis());
    }

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnMissingBean(TokenFetcher.class)
    static class TokenFetcherConfiguration {

        @Bean
        @ConditionalOnMissingBean
        OAuth2TokenRequestClient oAuth2TokenRequestClient(ObjectMapper objectMapper, HeaderManager headerManager,
                                                          FeignAuthProperties properties) {
            // 注册为容器 Bean：close() 会被推断为 destroyMethod，上下文关闭时释放连接池
            return new OAuth2TokenRequestClient(new OAuth2TokenResponseParser(objectMapper), headerManager,
                objectMapper, properties.getAuth().getHttp());
        }

        @Bean
        TokenFetcher tokenFetcher(FeignAuthProperties properties, OAuth2ClientMatcher clientMatcher,
                                  HeaderManager headerManager, TokenStore tokenStore, LockProvider lockProvider,
                                  OAuth2TokenRequestClient tokenRequestClient) {
            return new TokenFetcher(properties, clientMatcher, tokenRequestClient, tokenStore, lockProvider);
        }

    }

}
