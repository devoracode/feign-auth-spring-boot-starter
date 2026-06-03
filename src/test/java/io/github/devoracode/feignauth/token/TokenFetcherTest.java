package io.github.devoracode.feignauth.token;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.devoracode.feignauth.config.FeignAuthProperties;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestTemplate;

import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TokenFetcherTest {

    @Test
    void resolvesOAuth2ClientByLongestPathPrefixThenDefaultClient() {
        FeignAuthProperties properties = new FeignAuthProperties();
        TokenFetcher tokenFetcher = new TokenFetcher(properties, new RestTemplate(), new ObjectMapper());

        FeignAuthProperties.ServiceConfig service = oauth2Service(
                client("event", "/api/measure"),
                client("telemetry", "/api/measure/telemetry"),
                client("default")
        );

        assertThat(tokenFetcher.resolveClient("measure", service, "/api/measure/telemetry/1").getId())
                .isEqualTo("telemetry");
        assertThat(tokenFetcher.resolveClient("measure", service, "/api/other/1").getId())
                .isEqualTo("default");
    }

    @Test
    void rejectsMultipleDefaultOAuth2Clients() {
        FeignAuthProperties properties = new FeignAuthProperties();
        TokenFetcher tokenFetcher = new TokenFetcher(properties, new RestTemplate(), new ObjectMapper());
        FeignAuthProperties.ServiceConfig service = oauth2Service(client("first"), client("second"));

        assertThatThrownBy(() -> tokenFetcher.resolveClient("measure", service, "/api/other"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Multiple default OAuth2 clients");
    }

    private static FeignAuthProperties.ServiceConfig oauth2Service(FeignAuthProperties.ClientConfig... clients) {
        FeignAuthProperties.ServiceConfig service = new FeignAuthProperties.ServiceConfig();
        service.setBaseUrl("https://api.service-a.com");

        FeignAuthProperties.AuthConfig auth = new FeignAuthProperties.AuthConfig();
        auth.setType("oauth2");
        auth.setTokenUrl("https://api.service-a.com/oauth/token");
        auth.setClients(Arrays.asList(clients));
        service.setAuth(auth);
        return service;
    }

    private static FeignAuthProperties.ClientConfig client(String id, String... pathPrefixes) {
        FeignAuthProperties.ClientConfig client = new FeignAuthProperties.ClientConfig();
        client.setId(id);
        client.setSecret("secret-" + id);
        client.setPathPrefixes(Arrays.asList(pathPrefixes));
        return client;
    }
}
