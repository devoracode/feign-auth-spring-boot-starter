package io.github.devoracode.feignauth.oauth2;

import io.github.devoracode.feignauth.autoconfigure.FeignAuthProperties;
import io.github.devoracode.feignauth.exception.FeignAuthConfigurationException;
import io.github.devoracode.feignauth.support.PathUtils;
import org.springframework.util.Assert;

/**
 * 为服务请求路径解析匹配的 OAuth2 客户端凭证。
 *
 * @author Wenjie Liu
 * @since 1.0.0
 */
public class OAuth2ClientMatcher {

	/**
	 * 选择与给定请求路径最匹配的 OAuth2 Client。
	 * @param serviceName 逻辑服务名
	 * @param service 服务配置
	 * @param requestPath 归一化后的请求路径
	 * @return 选中的 Client
	 */
	public FeignAuthProperties.Client match(String serviceName, FeignAuthProperties.Service service,
			String requestPath) {
		Assert.notNull(service, "service must not be null");
		FeignAuthProperties.Auth auth = service.getAuth();
		if (auth.getClients() == null || auth.getClients().isEmpty()) {
			throw new FeignAuthConfigurationException("No OAuth2 clients configured for service: " + serviceName);
		}

		String path = PathUtils.normalizePath(requestPath);
		FeignAuthProperties.Client bestClient = null;
		int bestMatchLength = 0;
		for (FeignAuthProperties.Client client : auth.getClients()) {
			if (client == null || client.isDefaultClient()) {
				continue;
			}
			int matchLength = PathUtils.bestPrefixMatchLength(client.getPathPrefixes(), path);
			if (matchLength > bestMatchLength) {
				bestClient = client;
				bestMatchLength = matchLength;
			}
			else if (matchLength > 0 && matchLength == bestMatchLength) {
				throw new FeignAuthConfigurationException("Multiple OAuth2 clients matched requestPath=" + requestPath
						+ " for service=" + serviceName);
			}
		}
		if (bestClient != null) {
			return bestClient;
		}

		FeignAuthProperties.Client defaultClient = null;
		for (FeignAuthProperties.Client client : auth.getClients()) {
			if (client == null || !client.isDefaultClient()) {
				continue;
			}
			if (defaultClient != null) {
				throw new FeignAuthConfigurationException(
						"Multiple default OAuth2 clients configured for service: " + serviceName);
			}
			defaultClient = client;
		}
		if (defaultClient != null) {
			return defaultClient;
		}

		throw new FeignAuthConfigurationException("No OAuth2 client matched requestPath=" + requestPath
				+ " for service=" + serviceName);
	}

}
