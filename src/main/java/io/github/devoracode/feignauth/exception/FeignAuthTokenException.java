package io.github.devoracode.feignauth.exception;

/**
 * 运行时无法获取或解析 OAuth2 访问令牌时抛出。
 *
 * <p>典型原因包括：令牌端点返回非 2xx 响应、响应体无法解析为 JSON、
 * 响应中的 token 字段缺失或为空、以及调用令牌端点时的网络层故障。
 *
 * <p>与 {@link FeignAuthConfigurationException} 不同，此异常可能只是
 * 暂时性的，调用方可以选择捕获它并进行降级处理。
 *
 * @author Wenjie Liu
 * @since 1.0.0
 */
public class FeignAuthTokenException extends RuntimeException {

    public FeignAuthTokenException(String message) {
        super(message);
    }

    public FeignAuthTokenException(String message, Throwable cause) {
        super(message, cause);
    }

}
