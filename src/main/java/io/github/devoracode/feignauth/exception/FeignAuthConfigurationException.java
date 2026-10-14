package io.github.devoracode.feignauth.exception;

/**
 * feign-auth 配置缺失或非法时抛出。
 *
 * <p>典型原因包括：缺少 {@code token-url}、OAuth2 客户端凭证缺失、
 * 服务或客户端定义存在歧义、以及不支持的认证方式取值。这些问题通常在
 * 首次请求时被检测到，须在修正该配置错误后应用才能正常运行。
 *
 * @author Wenjie Liu
 * @since 1.0.0
 */
public class FeignAuthConfigurationException extends RuntimeException {

    public FeignAuthConfigurationException(String message) {
        super(message);
    }

    public FeignAuthConfigurationException(String message, Throwable cause) {
        super(message, cause);
    }

}
