package io.github.devoracode.feignauth.autoconfigure;

import com.fasterxml.jackson.databind.ObjectMapper;
import feign.RequestInterceptor;
import io.github.devoracode.feignauth.exception.FeignAuthConfigurationException;
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
import org.springframework.data.redis.connection.RedisConnectionFactory;
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
    @ConditionalOnProperty(prefix = "feign-auth.cache", name = "provider", havingValue = "local",
        matchIfMissing = true)
    public TokenStore tokenStore() {
        return new LocalTokenStore();
    }

    @Bean
    @ConditionalOnMissingBean(LockProvider.class)
    @ConditionalOnProperty(prefix = "feign-auth.cache", name = "provider",
        havingValue = "local", matchIfMissing = true)
    public LockProvider lockProvider() {
        return new LocalLockProvider();
    }

    private static String redisCacheNotAvailable() {
        return "FeignAuth: feign-auth.cache.provider=redis but no RedisConnectionFactory is available in the"
            + " context; add spring-boot-starter-data-redis and configure spring.redis.*, or set"
            + " feign-auth.cache.provider=local for the in-memory cache";
    }

    // 两个 Redis Bean 必须待在 @ConditionalOnClass 守卫的嵌套类里，不能挂在外层类上：
    // spring-boot-starter-data-redis 是 optional 依赖，缺省时外层类自己的某个 @Bean 做类型解析会触发
    // ReflectionUtils.getUniqueDeclaredMethods()（内部调用 Class.getDeclaredMethods()，需要解析类里
    // *每一个*方法描述符），外层方法签名上的 RedisConnectionFactory 直接抛 ClassNotFoundException，
    // 连 provider=local 的正常应用都启动失败。嵌套类被条件挡掉时不会注册，也就不会被反射解析。
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(RedisConnectionFactory.class)
    static class RedisCacheConfiguration {

        @Bean
        @ConditionalOnMissingBean(TokenStore.class)
        @ConditionalOnBean(RedisConnectionFactory.class)
        @ConditionalOnProperty(prefix = "feign-auth.cache", name = "provider", havingValue = "redis")
        public TokenStore redisTokenStore(RedisConnectionFactory redisConnectionFactory,
                                          FeignAuthProperties properties) {
            return new RedisTokenStore(redisConnectionFactory, properties.getCache().getRedis());
        }

        @Bean
        @ConditionalOnMissingBean(LockProvider.class)
        @ConditionalOnBean(RedisConnectionFactory.class)
        @ConditionalOnProperty(prefix = "feign-auth.cache", name = "provider", havingValue = "redis")
        public LockProvider redisLockProvider(RedisConnectionFactory redisConnectionFactory,
                                              FeignAuthProperties properties) {
            // 自建 StringRedisTemplate：不依赖容器里是否存在同名/同类型 Bean
            return new RedisLockProvider(new StringRedisTemplate(redisConnectionFactory),
                properties.getCache().getRedis());
        }

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
                objectMapper, properties.getHttp());
        }

        // TokenStore / LockProvider 用 ObjectProvider 取而不是按类型注入：provider=redis 而容器里没有 Redis
        // 连接（未引入 spring-data-redis、排除了 RedisAutoConfiguration 等）时，上面两个 redis Bean 的
        // @ConditionalOnBean 都不成立，按类型注入只会得到「TokenFetcher 参数注入不上」这种与根因无关的
        // NoSuchBeanDefinitionException；这里补一条点名根因的快速失败。
        //
        // 这条判断必须放在本配置类内（而不是外层类的 @Bean 哨兵）：@Bean 方法的条件在注册期按顺序求值，
        // 而嵌套配置类的 Bean 定义先于外层类注册（Spring 先递归处理成员类），外层哨兵因此无法区分
        // 「使用方自备 TokenFetcher」与「本 starter 自己注册的 TokenFetcher」。本配置类带
        // @ConditionalOnMissingBean(TokenFetcher.class)，使用方自备 TokenFetcher 时整体让位，
        // 天然不会再触发这条快速失败。
        @Bean
        TokenFetcher tokenFetcher(FeignAuthProperties properties, OAuth2ClientMatcher clientMatcher,
                                  HeaderManager headerManager, ObjectProvider<TokenStore> tokenStoreProvider,
                                  ObjectProvider<LockProvider> lockProviderProvider,
                                  OAuth2TokenRequestClient tokenRequestClient) {
            TokenStore tokenStore = tokenStoreProvider.getIfAvailable();
            LockProvider lockProvider = lockProviderProvider.getIfAvailable();
            if (tokenStore == null || lockProvider == null) {
                throw new FeignAuthConfigurationException(redisCacheNotAvailable());
            }
            return new TokenFetcher(properties, clientMatcher, tokenRequestClient, tokenStore, lockProvider);
        }

    }

}
