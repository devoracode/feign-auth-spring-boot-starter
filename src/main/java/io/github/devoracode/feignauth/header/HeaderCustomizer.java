package io.github.devoracode.feignauth.header;

import io.github.devoracode.feignauth.autoconfigure.FeignAuthProperties;
import org.springframework.http.HttpHeaders;

/**
 * 为 Feign 业务请求与 OAuth2 令牌请求注入动态 header 的统一策略接口。
 *
 * @author Wenjie Liu
 * @since 1.14.0
 */
public interface HeaderCustomizer {

	/**
	 * 返回该定制器是否应当应用于给定服务。
	 * @param serviceName 逻辑服务名（{@code feign-auth.services} 下的键）
	 * @param service 服务配置
	 * @return 当该定制器应对该服务生效时返回 {@code true}
	 */
	boolean supports(String serviceName, FeignAuthProperties.Service service);

	/**
	 * 为发出的 Feign 业务请求添加或修改 header。
	 * <p>默认实现不做任何事。
	 * @param context 业务请求上下文；可访问路径、查询参数、HTTP 方法、请求体以及完整的
	 *                {@link feign.RequestTemplate}
	 * @param headers 将被写入请求模板的可变 header 集合
	 */
	default void customize(RequestContext context, HttpHeaders headers) {
	}

	/**
	 * 为发出的 OAuth2 令牌 HTTP 请求添加或修改 header。
	 * <p>默认实现不做任何事。
	 * @param context 令牌请求上下文；可访问匹配到的 {@link FeignAuthProperties.Client}
	 *                及解析后的令牌参数
	 * @param headers 发出的令牌请求的可变 HTTP header
	 */
	default void customize(TokenRequestContext context, HttpHeaders headers) {
	}

}
