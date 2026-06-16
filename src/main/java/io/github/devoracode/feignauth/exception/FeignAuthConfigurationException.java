package io.github.devoracode.feignauth.exception;

/**
 * Thrown when the feign-auth configuration is missing or invalid.
 *
 * <p>Typical causes include missing {@code token-url}, absent OAuth2 client
 * credentials, ambiguous service or client definitions, and unsupported
 * authentication method values. These problems are normally detected on the
 * first request and indicate a configuration error that must be corrected
 * before the application can operate correctly.
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
