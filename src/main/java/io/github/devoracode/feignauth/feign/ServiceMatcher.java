package io.github.devoracode.feignauth.feign;

import io.github.devoracode.feignauth.autoconfigure.FeignAuthProperties;
import io.github.devoracode.feignauth.exception.FeignAuthConfigurationException;
import io.github.devoracode.feignauth.support.AuthType;
import io.github.devoracode.feignauth.support.PathUtils;
import org.apache.commons.lang3.StringUtils;
import org.springframework.util.Assert;

import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * 基于基础 URL 与路径，为发出的 Feign 请求解析匹配的配置服务。
 * <p>
 * 基础 URL 按 {@code scheme://host:port} authority 归一化比较（补全默认端口、忽略大小写），
 * 配置 base-url 携带的路径段视为外层前缀：请求路径落在其下时先剥离再做前缀匹配。
 * <p>
 * 本类每个请求都会被调用一次，因此匹配走单趟、不分配中间集合，URL 派生值按 URL 串记忆化。
 *
 * @author Wenjie Liu
 * @since 1.0.0
 */
public class ServiceMatcher {

	/** URL 派生值缓存的条目上限：配置里的 base-url 通常只有几个，动态 target 也不该无界增长 */
	private static final int MAX_URL_CACHE_ENTRIES = 256;

	private final FeignAuthProperties properties;

	// URL 串到 (authority, basePath) 是纯函数映射（同一个字符串永远得到同一个结果），因此缓存不会因配置变更而失效
	// 记忆化与 MAX_URL_CACHE_ENTRIES 上限都没有对外契约可观察，ServiceMatcherTest 反射读取本字段来钉住
	private final ConcurrentMap<String, PathUtils.BaseUrlParts> urlCache = new ConcurrentHashMap<>();

	public ServiceMatcher(FeignAuthProperties properties) {
		Assert.notNull(properties, "properties must not be null");
		this.properties = properties;
	}

	/**
	 * 解析应当处理给定请求的配置服务。
	 * @param baseUrl Feign target 的基础 URL
	 * @param requestPath 归一化后的请求路径
	 * @return 解析得到的服务；若没有服务匹配该基础 URL 则返回 {@code null}
	 * @throws FeignAuthConfigurationException authority 匹配但 auth 配置非法（缺失或 type 无法识别）时抛出
	 */
	public ResolvedService match(String baseUrl, String requestPath) {
		Map<String, FeignAuthProperties.Service> services = this.properties.getServices();
		if (services == null || services.isEmpty()) {
			return null;
		}

		String requestAuthority = authorityKey(baseUrl);
		// 单趟完成匹配：路径前缀最长的服务优先，没有任何前缀命中时才考虑 fallback。
		// 排序与中间集合都省掉（本方法每个请求都会执行），非法配置信息按需拼接（只有要报错时才读）
		ResolvedService bestPrefixMatch = null;
		int bestPrefixLength = 0;
		boolean ambiguousPrefixMatch = false;
		ResolvedService fallbackMatch = null;
		int fallbackCount = 0;
		int validCandidates = 0;
		StringBuilder invalidConfigs = null;

		for (Map.Entry<String, FeignAuthProperties.Service> entry : services.entrySet()) {
			FeignAuthProperties.Service service = entry.getValue();
			if (service == null || !requestAuthority.equals(authorityKey(service.getBaseUrl()))) {
				continue;
			}
			FeignAuthProperties.Auth auth = service.getAuth();
			if (auth == null) {
				invalidConfigs = appendInvalidConfig(invalidConfigs, entry.getKey(),
						" (auth config is missing, expected auth.type 'oauth2' or 'api-key')");
				continue;
			}
			// 认证类型每个请求只需解析一次，后续判定都用枚举比较：
			// isApiKey()/isOAuth2() 各自都要重扫一遍枚举，而本方法每个请求都会执行
			AuthType authType = AuthType.from(auth.getType());
			if (authType == null) {
				invalidConfigs = appendInvalidConfig(invalidConfigs, entry.getKey(),
						" (unsupported auth.type '" + auth.getType() + "', expected 'oauth2' or 'api-key')");
				continue;
			}
			validCandidates++;

			// 配置 base-url 携带的路径段是外层前缀：请求路径落在其下时先剥离再匹配
			int matchLength = bestPrefixMatchLength(auth, authType,
					stripConfiguredBasePath(requestPath, basePath(service.getBaseUrl())));
			if (matchLength > 0) {
				if (matchLength > bestPrefixLength) {
					bestPrefixLength = matchLength;
					bestPrefixMatch = new ResolvedService(entry.getKey(), service);
					ambiguousPrefixMatch = false;
				}
				else if (matchLength == bestPrefixLength) {
					ambiguousPrefixMatch = true;
				}
			}
			if (auth.isFallback()) {
				fallbackCount++;
				if (fallbackMatch == null) {
					fallbackMatch = new ResolvedService(entry.getKey(), service);
				}
			}
		}

		if (validCandidates == 0) {
			if (invalidConfigs != null) {
				throw new FeignAuthConfigurationException(
						"FeignAuth: service(s) matched baseUrl=" + baseUrl + " but have invalid auth config: "
								+ invalidConfigs);
			}
			return null;
		}

		if (bestPrefixMatch != null) {
			if (ambiguousPrefixMatch) {
				throw new FeignAuthConfigurationException(
						"Multiple services matched requestPath=" + requestPath + " for baseUrl=" + baseUrl);
			}
			return bestPrefixMatch;
		}

		if (fallbackCount == 0) {
			return null;
		}
		if (fallbackCount > 1) {
			throw new FeignAuthConfigurationException("Multiple fallback services configured for baseUrl=" + baseUrl);
		}
		return fallbackMatch;
	}

	// 非法配置信息只在最终抛出异常时才会被读取，因此按需拼接，成功路径上不分配集合
	private static StringBuilder appendInvalidConfig(StringBuilder invalidConfigs, String serviceName, String reason) {
		if (invalidConfigs == null) {
			invalidConfigs = new StringBuilder();
		}
		else {
			invalidConfigs.append("; ");
		}
		return invalidConfigs.append(serviceName).append(reason);
	}

	// 解析失败（如服务发现名）时退化为小写去空白的原串，保持旧有字符串比较语义
	private String authorityKey(String url) {
		String authority = derive(url).getAuthority();
		if (authority != null) {
			return authority;
		}
		return StringUtils.isBlank(url) ? "" : StringUtils.strip(url).toLowerCase(Locale.ROOT);
	}

	private String basePath(String url) {
		return derive(url).getBasePath();
	}

	// 一个 URL 串只解析一次，authority 与 basePath 两个派生值同时得到；null 不入缓存也不报错，与旧实现一致
	private PathUtils.BaseUrlParts derive(String url) {
		if (url == null) {
			return PathUtils.BaseUrlParts.EMPTY;
		}
		PathUtils.BaseUrlParts cached = this.urlCache.get(url);
		if (cached != null) {
			return cached;
		}
		PathUtils.BaseUrlParts computed = PathUtils.parseBaseUrl(url);
		if (this.urlCache.size() < MAX_URL_CACHE_ENTRIES) {
			this.urlCache.put(url, computed);
		}
		return computed;
	}

	private static String stripConfiguredBasePath(String requestPath, String basePath) {
		if (StringUtils.isBlank(basePath) || StringUtils.isBlank(requestPath)) {
			return requestPath;
		}
		if (requestPath.equals(basePath)) {
			return "/";
		}
		if (requestPath.startsWith(basePath) && requestPath.charAt(basePath.length()) == '/') {
			return requestPath.substring(basePath.length());
		}
		return requestPath;
	}

	private static int bestPrefixMatchLength(FeignAuthProperties.Auth auth, AuthType authType, String requestPath) {
		if (StringUtils.isBlank(requestPath)) {
			return 0;
		}

		if (authType == AuthType.API_KEY) {
			return PathUtils.bestPrefixMatchLength(auth.getPathPrefixes(), requestPath);
		}
		if (auth.getClients() == null) {
			return 0;
		}

		int best = 0;
		for (FeignAuthProperties.Client client : auth.getClients()) {
			if (client != null && !client.isDefaultClient()) {
				best = Math.max(best, PathUtils.bestPrefixMatchLength(client.getPathPrefixes(), requestPath));
			}
		}
		return best;
	}

}
