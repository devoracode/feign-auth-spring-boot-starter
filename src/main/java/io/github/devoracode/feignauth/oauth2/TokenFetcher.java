package io.github.devoracode.feignauth.oauth2;

import io.github.devoracode.feignauth.autoconfigure.FeignAuthProperties;
import io.github.devoracode.feignauth.exception.FeignAuthConfigurationException;
import io.github.devoracode.feignauth.oauth2.lock.LockProvider;
import io.github.devoracode.feignauth.oauth2.store.TokenStore;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.springframework.util.Assert;

import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * 为已配置的 Feign 服务提供带缓存的 OAuth2 访问令牌。
 *
 * @author Wenjie Liu
 * @since 1.0.0
 */
public class TokenFetcher {

	private static final Log logger = LogFactory.getLog(TokenFetcher.class);

	// 持续 401 场景下，每次都清缓存重试会形成 401->刷新->401 风暴并轰击 IdP，
	// 故对同一缓存键的淘汰做硬编码冷却
	private static final long EVICT_COOLDOWN_MILLIS = TimeUnit.SECONDS.toMillis(30);

	private final FeignAuthProperties properties;

	private final OAuth2ClientMatcher clientMatcher;

	private final OAuth2TokenRequestClient tokenRequestClient;

	private final TokenStore tokenStore;

	private final LockProvider lockProvider;

	/** 各缓存键最近一次淘汰令牌的时间戳（毫秒），用于冷却判定 */
	private final Map<String, Long> lastEvictionMillis = new ConcurrentHashMap<>();

	public TokenFetcher(FeignAuthProperties properties, OAuth2ClientMatcher clientMatcher,
			OAuth2TokenRequestClient tokenRequestClient, TokenStore tokenStore,
			            LockProvider lockProvider) {
		Assert.notNull(properties, "properties must not be null");
		Assert.notNull(clientMatcher, "clientMatcher must not be null");
		Assert.notNull(tokenRequestClient, "tokenRequestClient must not be null");
		Assert.notNull(tokenStore, "tokenStore must not be null");
		Assert.notNull(lockProvider, "lockProvider must not be null");
		this.properties = properties;
		this.clientMatcher = clientMatcher;
		this.tokenRequestClient = tokenRequestClient;
		this.tokenStore = tokenStore;
		this.lockProvider = lockProvider;
	}

	/**
	 * 返回给定服务与请求路径对应的有效 OAuth2 访问令牌。
	 * @param serviceName 逻辑服务名
	 * @param requestPath 归一化后的请求路径
	 * @return 非空的访问令牌
	 */
	public String getToken(String serviceName, String requestPath) {
		FeignAuthProperties.Service service = getRequiredService(serviceName);
		FeignAuthProperties.Auth auth = service.getAuth();
		if (auth == null || !auth.isOAuth2()) {
			throw new FeignAuthConfigurationException("Service '" + serviceName + "' is not OAuth2 mode");
		}

		FeignAuthProperties.Client client = resolveClient(serviceName, service, requestPath);
		return getCachedOrFetch(serviceName, service, client);
	}

	/**
	 * 使按给定请求路径选定的已缓存 OAuth2 访问令牌失效。
	 * <p>同一缓存键在 30 秒冷却期内重复调用会被抑制，以防止持续 401
	 * 场景下反复清缓存、刷新令牌并轰击 IdP。
	 * @param serviceName 逻辑服务名
	 * @param requestPath 归一化后的请求路径
	 * @return 当缓存令牌被移除时返回 {@code true}；处于冷却期或未命中缓存时返回 {@code false}
	 */
	public boolean invalidateToken(String serviceName, String requestPath) {
		FeignAuthProperties.Service service = getRequiredService(serviceName);
		FeignAuthProperties.Auth auth = service.getAuth();
		if (auth == null || !auth.isOAuth2()) {
			return false;
		}

		FeignAuthProperties.Client client = resolveClient(serviceName, service, requestPath);
		String cacheKey = buildCacheKey(serviceName, client);
		long now = System.currentTimeMillis();
		Long lastEviction = this.lastEvictionMillis.get(cacheKey);
		if (lastEviction != null && now - lastEviction < EVICT_COOLDOWN_MILLIS) {
			if (logger.isWarnEnabled()) {
				logger.warn("FeignAuth [oauth2] token eviction suppressed within cooldown for service='"
						+ serviceName + "', clientId='" + client.getId() + "'");
			}
			return false;
		}
		boolean removed = this.tokenStore.remove(cacheKey);
		// 只在真正驱逐到令牌时才起算冷却：空转的淘汰（缓存已按 TTL 自行过期）或 remove 抛异常
		// 都不该写入时间戳，否则会把随后确实能驱逐成功的尝试一并锁死 30 秒
		if (removed) {
			this.lastEvictionMillis.put(cacheKey, now);
			if (logger.isInfoEnabled()) {
				logger.info("FeignAuth [oauth2] token evicted for service='" + serviceName + "', clientId='"
						+ client.getId() + "'");
			}
		}
		return removed;
	}

	/**
	 * 选择与给定请求路径最匹配的 OAuth2 Client。
	 * @param serviceName 逻辑服务名
	 * @param service 服务配置
	 * @param requestPath 归一化后的请求路径
	 * @return 选中的 Client
	 */
	public FeignAuthProperties.Client resolveClient(String serviceName, FeignAuthProperties.Service service,
			String requestPath) {
		return this.clientMatcher.match(serviceName, service, requestPath);
	}

	private FeignAuthProperties.Service getRequiredService(String serviceName) {
		Map<String, FeignAuthProperties.Service> services = this.properties.getServices();
		FeignAuthProperties.Service service = services == null ? null : services.get(serviceName);
		if (service == null) {
			throw new FeignAuthConfigurationException("Service config not found: " + serviceName);
		}
		return service;
	}

	private String getCachedOrFetch(String serviceName, FeignAuthProperties.Service service,
			FeignAuthProperties.Client client) {
		String cacheKey = buildCacheKey(serviceName, client);
		OAuth2AccessToken cached = tokenStore.get(cacheKey);

		if (cached != null && StringUtils.isNotBlank(cached.getAccessToken())) {

			return cached.getAccessToken();
		}
		return lockProvider.execute(
				cacheKey,
				() -> {
					OAuth2AccessToken cachedAgain = tokenStore.get(cacheKey);
					if (cachedAgain != null && StringUtils.isNotBlank(cachedAgain.getAccessToken())) {
						return cachedAgain.getAccessToken();
					}
					OAuth2AccessToken refreshed = tokenRequestClient.requestToken(serviceName, service, client);
					tokenStore.put(cacheKey, refreshed);
					if (logger.isInfoEnabled()) {
						logger.info("FeignAuth [oauth2] fetching new token for service='" + serviceName + "', clientId='" + client.getId() + "'");
					}
					return refreshed.getAccessToken();
				});
	}

	private static String buildCacheKey(String serviceName, FeignAuthProperties.Client client) {
		return serviceName + ":" + Objects.toString(client.getId(), "");
	}

}
