package io.github.devoracode.feignauth.feign;

import io.github.devoracode.feignauth.config.FeignAuthProperties;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.LinkedHashMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FeignClientConfigTest {

    @Test
    void matchesLongestPathPrefixBeforeFallbackForSameBaseUrl() {
        FeignAuthProperties properties = new FeignAuthProperties();
        properties.setServices(new LinkedHashMap<>());
        properties.getServices().put("fallback", apiKeyService("https://api.service-d.com", "sk-fallback"));
        properties.getServices().put("orders", apiKeyService("https://api.service-d.com", "sk-orders", "/api/orders"));

        FeignAuthRequestInterceptor interceptor = new FeignAuthRequestInterceptor(properties, null);

        assertThat(interceptor.matchService("https://api.service-d.com", "/api/orders/1").getServiceName())
                .isEqualTo("orders");
        assertThat(interceptor.matchService("https://api.service-d.com/", "/api/products/1").getServiceName())
                .isEqualTo("fallback");
    }

    @Test
    void rejectsAmbiguousFallbackServicesForSameBaseUrl() {
        FeignAuthProperties properties = new FeignAuthProperties();
        properties.setServices(new LinkedHashMap<>());
        properties.getServices().put("first", apiKeyService("https://api.service-d.com", "sk-first"));
        properties.getServices().put("second", apiKeyService("https://api.service-d.com", "sk-second"));

        FeignAuthRequestInterceptor interceptor = new FeignAuthRequestInterceptor(properties, null);

        assertThatThrownBy(() -> interceptor.matchService("https://api.service-d.com", "/api/anything"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Multiple fallback services");
    }

    @Test
    void rejectsAmbiguousSameLengthPathPrefixesForSameBaseUrl() {
        FeignAuthProperties properties = new FeignAuthProperties();
        properties.setServices(new LinkedHashMap<>());
        properties.getServices().put("first", apiKeyService("https://api.service-d.com", "sk-first", "/api/orders"));
        properties.getServices().put("second", apiKeyService("https://api.service-d.com", "sk-second", "/api/orders"));

        FeignAuthRequestInterceptor interceptor = new FeignAuthRequestInterceptor(properties, null);

        assertThatThrownBy(() -> interceptor.matchService("https://api.service-d.com", "/api/orders/1"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Multiple services matched");
    }

    private static FeignAuthProperties.ServiceConfig apiKeyService(String baseUrl, String apiKey, String... pathPrefixes) {
        FeignAuthProperties.ServiceConfig service = new FeignAuthProperties.ServiceConfig();
        service.setBaseUrl(baseUrl);

        FeignAuthProperties.AuthConfig auth = new FeignAuthProperties.AuthConfig();
        auth.setType("api-key");
        auth.setHeaderName("Authorization");
        auth.setValue(apiKey);
        auth.setPathPrefixes(Arrays.asList(pathPrefixes));
        service.setAuth(auth);
        return service;
    }
}
