package io.github.devoracode.feignauth.exception;

/**
 * Thrown when an OAuth2 access token cannot be acquired or parsed at runtime.
 *
 * <p>Typical causes include a non-2xx response from the token endpoint, a
 * response body that cannot be parsed as JSON, a missing or blank token field
 * in the response, and network-level failures when calling the token endpoint.
 *
 * <p>Unlike {@link FeignAuthConfigurationException}, this exception may occur
 * transiently and callers may choose to catch it for fallback handling.
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
