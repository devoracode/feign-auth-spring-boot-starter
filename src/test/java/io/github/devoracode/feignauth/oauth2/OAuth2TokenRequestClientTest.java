package io.github.devoracode.feignauth.oauth2;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import io.github.devoracode.feignauth.autoconfigure.FeignAuthProperties;
import io.github.devoracode.feignauth.exception.FeignAuthTokenException;
import io.github.devoracode.feignauth.header.HeaderManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OAuth2TokenRequestClientTest {

	private HttpServer server;

	private String tokenUrl;

	@BeforeEach
	void startServer() throws IOException {
		this.server = HttpServer.create(new InetSocketAddress(0), 0);
		this.server.start();
		this.tokenUrl = "http://127.0.0.1:" + this.server.getAddress().getPort() + "/token";
	}

	@AfterEach
	void stopServer() {
		this.server.stop(0);
	}

	@Test
	void socketTimeoutIsTakenFromProperties() {
		this.server.createContext("/token", exchange -> {
			sleep(1000L);
			respond(exchange, 200, "{\"access_token\":\"t\",\"expires_in\":600}");
		});

		FeignAuthProperties.Http http = new FeignAuthProperties.Http();
		http.setSocketTimeout(Duration.ofMillis(200));
		OAuth2TokenRequestClient client = createClient(http);

		assertThatThrownBy(() -> client.requestToken("svc", service(this.tokenUrl), clientCredentials()))
				.isInstanceOf(FeignAuthTokenException.class)
				.hasStackTraceContaining("Read timed out");
	}

	@Test
	void closeShutsDownConnectionPool() throws IOException {
		this.server.createContext("/token",
				exchange -> respond(exchange, 200, "{\"access_token\":\"t\",\"expires_in\":600}"));

		OAuth2TokenRequestClient client = createClient(new FeignAuthProperties.Http());
		OAuth2AccessToken token = client.requestToken("svc", service(this.tokenUrl), clientCredentials());
		assertThat(token.getAccessToken()).isEqualTo("t");

		client.close();

		assertThatThrownBy(() -> client.requestToken("svc", service(this.tokenUrl), clientCredentials()))
				.hasStackTraceContaining("shut down");
	}

	private OAuth2TokenRequestClient createClient(FeignAuthProperties.Http http) {
		OAuth2TokenResponseParser parser = new OAuth2TokenResponseParser(new ObjectMapper());
		return new OAuth2TokenRequestClient(parser, new HeaderManager(), http);
	}

	private static FeignAuthProperties.Service service(String tokenUrl) {
		FeignAuthProperties.Service service = new FeignAuthProperties.Service();
		service.setBaseUrl("http://127.0.0.1");
		FeignAuthProperties.Auth auth = new FeignAuthProperties.Auth();
		auth.setType("oauth2");
		auth.setTokenUrl(tokenUrl);
		service.setAuth(auth);
		return service;
	}

	private static FeignAuthProperties.Client clientCredentials() {
		FeignAuthProperties.Client client = new FeignAuthProperties.Client();
		client.setId("id");
		client.setSecret("secret");
		return client;
	}

	private static void sleep(long millis) {
		try {
			Thread.sleep(millis);
		}
		catch (InterruptedException ex) {
			Thread.currentThread().interrupt();
		}
	}

	private static void respond(com.sun.net.httpserver.HttpExchange exchange, int status, String body) {
		try {
			byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
			exchange.getResponseHeaders().add("Content-Type", "application/json");
			exchange.sendResponseHeaders(status, bytes.length);
			try (OutputStream os = exchange.getResponseBody()) {
				os.write(bytes);
			}
		}
		catch (IOException ex) {
			// 客户端超时后可能已断开，忽略
		}
	}

}
