package io.github.devoracode.feignauth.oauth2;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import java.net.URI;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 取号端点的安全告警：凭据进 query string、token-url 非 https。
 *
 * <p>取号是可能反复发生的路径（令牌过期、被驱逐），同一原因的 WARN 只记一次，去重键由本类持有；
 * 键按 {@link OAuth2TokenRequestClient} 实例隔离（即每个客户端一份），与抽取前的字段生命周期一致。
 * 两条文案逐字不变。</p>
 *
 * @author Wenjie Liu
 * @since 2.0.0
 */
final class TokenEndpointWarnings {

	private static final Log logger = LogFactory.getLog(TokenEndpointWarnings.class);

	private final Set<String> warnedOnce = ConcurrentHashMap.newKeySet();

	// 显式选择 parameters + GET 时 secret 会重新出现在 URL 中：提示一次，便于定位泄露来源
	void warnCredentialsInQuery(String serviceName) {
		if (this.warnedOnce.add("credentials-in-query:" + serviceName)) {
			logger.warn("FeignAuth: client-authentication=parameters with method=get puts client_id/client_secret"
					+ " into the query string for service '" + serviceName + "'; they are visible to proxies,"
					+ " gateways and access logs — the default basic mode keeps them in the Authorization header");
		}
	}

	// 非 https 的 token-url 会让 client_secret 与返回的令牌明文过网：按服务提示一次，便于定位是哪条配置。
	// 同一服务下的多个 Client 共用一个 token-url，因此每服务实际只提醒一次。
	// 回环地址是本地开发的正常用法，不提示
	void warnIfInsecureTokenUrl(String serviceName, URI uri) {
		String scheme = uri.getScheme();
		if (scheme == null || "https".equalsIgnoreCase(scheme) || isLoopbackHost(uri.getHost())) {
			return;
		}
		if (this.warnedOnce.add("insecure-token-url:" + serviceName + ":"
				+ scheme.toLowerCase(Locale.ROOT) + "://" + uri.getAuthority())) {
			logger.warn("FeignAuth: token-url '" + uri + "' of service '" + serviceName
					+ "' is not https; client credentials and access tokens"
					+ " can be read or modified in transit");
		}
	}

	private static boolean isLoopbackHost(String host) {
		if (host == null) {
			return false;
		}
		return "localhost".equalsIgnoreCase(host) || "::1".equals(host) || "[::1]".equals(host)
				|| host.startsWith("127.");
	}

}
