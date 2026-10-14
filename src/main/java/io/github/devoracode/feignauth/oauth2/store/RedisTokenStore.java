package io.github.devoracode.feignauth.oauth2.store;

import io.github.devoracode.feignauth.autoconfigure.FeignAuthProperties;
import io.github.devoracode.feignauth.oauth2.OAuth2AccessToken;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.serializer.GenericJackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.SerializationException;
import org.springframework.data.redis.serializer.StringRedisSerializer;

import java.util.concurrent.TimeUnit;

/**
 * 基于 Redis 的令牌缓存。
 *
 * <p>自带序列化的内部 {@link RedisTemplate}（String 键 + 类型化 JSON 值），
 * 只依赖容器中的 {@link RedisConnectionFactory}，不要求使用方提供特定泛型/序列化器的
 * RedisTemplate Bean，避免泛型不兼容导致的启动失败与外部序列化器破坏读写。
 */
public class RedisTokenStore implements TokenStore {

    private static final Log logger = LogFactory.getLog(RedisTokenStore.class);

    private static final String TOKEN_SUFFIX = "token:";

    private final RedisTemplate<String, Object> redisTemplate;

    private final String keyPrefix;

    public RedisTokenStore(RedisConnectionFactory connectionFactory, FeignAuthProperties.Redis redis) {
        this(buildTemplate(connectionFactory), redis);
    }

    public RedisTokenStore(RedisTemplate<String, Object> redisTemplate, FeignAuthProperties.Redis redis) {
        this.redisTemplate = redisTemplate;
        this.keyPrefix = redis.getKeyPrefix();
    }

    private static RedisTemplate<String, Object> buildTemplate(RedisConnectionFactory connectionFactory) {
        RedisTemplate<String, Object> template = new RedisTemplate<>();
        template.setConnectionFactory(connectionFactory);
        StringRedisSerializer keySerializer = new StringRedisSerializer();
        GenericJackson2JsonRedisSerializer valueSerializer = new GenericJackson2JsonRedisSerializer();
        template.setKeySerializer(keySerializer);
        template.setHashKeySerializer(keySerializer);
        template.setValueSerializer(valueSerializer);
        template.setHashValueSerializer(valueSerializer);
        template.afterPropertiesSet();
        return template;
    }

    // 包内可见，供单元测试校验序列化器装配
    RedisTemplate<String, Object> getRedisTemplate() {
        return this.redisTemplate;
    }

    @Override
    public OAuth2AccessToken get(String key) {
        String fullKey = keyPrefix + TOKEN_SUFFIX + key;
        Object value;
        try {
            value = redisTemplate.opsForValue().get(fullKey);
        }
        catch (SerializationException ex) {
            // 坏值（非法 JSON/旧格式）会让每次请求都静默重新拉取令牌，删除后由 put 以正确类型覆盖
            logger.warn("FeignAuth [redis-store] cannot deserialize key '" + fullKey + "': " + ex.getMessage()
                    + ", removing it");
            redisTemplate.delete(fullKey);
            return null;
        }
        if (value == null) {
            return null;
        }
        if (value instanceof OAuth2AccessToken) {
            return (OAuth2AccessToken) value;
        }
        // 外部写入的异常值（例如序列化器不匹配）会导致每次请求都静默重新拉取
        // 令牌；此处删除一次，使下一次 put 以正确类型覆盖。
        logger.warn("FeignAuth [redis-store] unexpected value type " + value.getClass().getName()
                + " for key '" + fullKey + "', removing it");
        redisTemplate.delete(fullKey);
        return null;
    }

    @Override
    public void put(String key, OAuth2AccessToken token) {
        // SETEX 拒绝 ttl <= 0，因此回退到最短的合法过期时间
        long ttl = Math.max(1L, token.getTtl());
        redisTemplate.opsForValue().set(keyPrefix + TOKEN_SUFFIX + key, token, ttl, TimeUnit.SECONDS);
    }

    @Override
    public boolean remove(String key) {
        return redisTemplate.delete(keyPrefix + TOKEN_SUFFIX + key);
    }
}
