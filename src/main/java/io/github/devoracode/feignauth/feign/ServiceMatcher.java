package io.github.devoracode.feignauth.feign;

import io.github.devoracode.feignauth.autoconfigure.FeignAuthProperties;
import io.github.devoracode.feignauth.exception.FeignAuthConfigurationException;
import io.github.devoracode.feignauth.support.PathUtils;
import org.apache.commons.lang3.StringUtils;
import org.springframework.util.Assert;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 基于基础 URL 与路径，为发出的 Feign 请求解析匹配的配置服务。
 * <p>
 * 基础 URL 按 {@code scheme://host:port} authority 归一化比较（补全默认端口、忽略大小写），
 * 配置 base-url 携带的路径段视为外层前缀：请求路径落在其下时先剥离再做前缀匹配。
 *
 * @author Wenjie Liu
 * @since 1.0.0
 */
public class ServiceMatcher {

	private final FeignAuthProperties properties;

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
		List<MatchCandidate> candidates = new ArrayList<>();
		List<String> invalidConfigs = new ArrayList<>();
		for (Map.Entry<String, FeignAuthProperties.Service> entry : services.entrySet()) {
			FeignAuthProperties.Service service = entry.getValue();
			if (service == null || !requestAuthority.equals(authorityKey(service.getBaseUrl()))) {
				continue;
			}
			FeignAuthProperties.Auth auth = service.getAuth();
			if (auth == null) {
				invalidConfigs.add(entry.getKey() + " (auth config is missing, expected auth.type 'oauth2' or 'api-key')");
				continue;
			}
			if (!auth.isApiKey() && !auth.isOAuth2()) {
				invalidConfigs.add(entry.getKey() + " (unsupported auth.type '" + auth.getType()
						+ "', expected 'oauth2' or 'api-key')");
				continue;
			}
			String matchPath = stripConfiguredBasePath(requestPath, PathUtils.baseUrlPath(service.getBaseUrl()));
			candidates.add(new MatchCandidate(new ResolvedService(entry.getKey(), service), matchPath));
		}

		if (candidates.isEmpty()) {
			if (!invalidConfigs.isEmpty()) {
				throw new FeignAuthConfigurationException(
						"FeignAuth: service(s) matched baseUrl=" + baseUrl + " but have invalid auth config: "
								+ String.join("; ", invalidConfigs));
			}
			return null;
		}

		List<PrefixMatch> prefixMatches = new ArrayList<>();
		for (MatchCandidate candidate : candidates) {
			int matchLength = bestPrefixMatchLength(candidate.getService().getService().getAuth(),
					candidate.getMatchPath());
			if (matchLength > 0) {
				prefixMatches.add(new PrefixMatch(candidate.getService(), matchLength));
			}
		}

		if (!prefixMatches.isEmpty()) {
			prefixMatches.sort(Comparator.comparingInt(PrefixMatch::getMatchLength).reversed());
			PrefixMatch best = prefixMatches.get(0);
			if (prefixMatches.size() > 1 && prefixMatches.get(1).getMatchLength() == best.getMatchLength()) {
				throw new FeignAuthConfigurationException(
						"Multiple services matched requestPath=" + requestPath + " for baseUrl=" + baseUrl);
			}
			return best.getService();
		}

		List<ResolvedService> fallbackCandidates = new ArrayList<>();
		for (MatchCandidate candidate : candidates) {
			if (candidate.getService().getService().getAuth().isFallback()) {
				fallbackCandidates.add(candidate.getService());
			}
		}

		if (fallbackCandidates.isEmpty()) {
			return null;
		}
		if (fallbackCandidates.size() > 1) {
			throw new FeignAuthConfigurationException("Multiple fallback services configured for baseUrl=" + baseUrl);
		}
		return fallbackCandidates.get(0);
	}

	// 解析失败（如服务发现名）时退化为小写去空白的原串，保持旧有字符串比较语义
	private static String authorityKey(String url) {
		String normalized = PathUtils.normalizeBaseUrlAuthority(url);
		if (normalized != null) {
			return normalized;
		}
		return StringUtils.isBlank(url) ? "" : StringUtils.strip(url).toLowerCase(Locale.ROOT);
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

	private static int bestPrefixMatchLength(FeignAuthProperties.Auth auth, String requestPath) {
		if (StringUtils.isBlank(requestPath)) {
			return 0;
		}

		if (auth.isApiKey()) {
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

	private static final class MatchCandidate {

		private final ResolvedService service;

		private final String matchPath;

		private MatchCandidate(ResolvedService service, String matchPath) {
			this.service = service;
			this.matchPath = matchPath;
		}

		private ResolvedService getService() {
			return this.service;
		}

		private String getMatchPath() {
			return this.matchPath;
		}

	}

	private static final class PrefixMatch {

		private final ResolvedService service;

		private final int matchLength;

		private PrefixMatch(ResolvedService service, int matchLength) {
			this.service = service;
			this.matchLength = matchLength;
		}

		private ResolvedService getService() {
			return this.service;
		}

		private int getMatchLength() {
			return this.matchLength;
		}

	}

}
