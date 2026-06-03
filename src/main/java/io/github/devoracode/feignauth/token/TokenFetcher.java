package io.github.devoracode.feignauth.token;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.devoracode.feignauth.config.FeignAuthProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.math.NumberUtils;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.StreamSupport;

@Slf4j
@RequiredArgsConstructor
public class TokenFetcher {

    private static final long DEFAULT_EXPIRES_IN_SECONDS = 7200L;

    private final FeignAuthProperties feignAuthProperties;
    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;
    private final Map<String, TokenCacheEntry> tokenCache = new ConcurrentHashMap<>();

    public String getToken(String serviceName, String requestPath) {
        FeignAuthProperties.ServiceConfig service = getServiceConfig(serviceName);
        FeignAuthProperties.AuthConfig auth = service.getAuth();
        if (auth == null || !auth.isOAuth2()) {
            throw new IllegalStateException("Service '" + serviceName + "' is not OAuth2 mode");
        }

        FeignAuthProperties.ClientConfig client = resolveClient(serviceName, service, requestPath);
        return getCachedOrFetch(serviceName, service, client);
    }

    public FeignAuthProperties.ClientConfig resolveClient(String serviceName, FeignAuthProperties.ServiceConfig service, String requestPath) {
        FeignAuthProperties.AuthConfig auth = service.getAuth();
        if (auth.getClients() == null || auth.getClients().isEmpty()) {
            throw new IllegalStateException("No OAuth2 clients configured for service: " + serviceName);
        }

        String path = normalizePath(requestPath);
        FeignAuthProperties.ClientConfig bestClient = null;
        int bestMatchLength = 0;
        for (FeignAuthProperties.ClientConfig client : auth.getClients()) {
            if (client == null || client.isDefaultClient()) {
                continue;
            }
            int matchLength = bestPrefixMatchLength(client.getPathPrefixes(), path);
            if (matchLength > bestMatchLength) {
                bestClient = client;
                bestMatchLength = matchLength;
            } else if (matchLength > 0 && matchLength == bestMatchLength) {
                throw new IllegalStateException("Multiple OAuth2 clients matched requestPath=" + requestPath + " for service=" + serviceName);
            }
        }
        if (bestClient != null) {
            return bestClient;
        }

        FeignAuthProperties.ClientConfig defaultClient = null;
        for (FeignAuthProperties.ClientConfig client : auth.getClients()) {
            if (client == null || !client.isDefaultClient()) {
                continue;
            }
            if (defaultClient != null) {
                throw new IllegalStateException("Multiple default OAuth2 clients configured for service: " + serviceName);
            }
            defaultClient = client;
        }
        if (defaultClient != null) {
            return defaultClient;
        }

        throw new IllegalStateException("No OAuth2 client matched requestPath=" + requestPath + " for service=" + serviceName);
    }

    private FeignAuthProperties.ServiceConfig getServiceConfig(String serviceName) {
        Map<String, FeignAuthProperties.ServiceConfig> services = feignAuthProperties.getServices();
        FeignAuthProperties.ServiceConfig service = services == null ? null : services.get(serviceName);
        if (service == null) {
            throw new IllegalStateException("Service config not found: " + serviceName);
        }
        return service;
    }

    private String getCachedOrFetch(String serviceName, FeignAuthProperties.ServiceConfig service, FeignAuthProperties.ClientConfig client) {
        String cacheKey = serviceName + ":" + Objects.toString(client.getId(), "");
        TokenCacheEntry cached = tokenCache.get(cacheKey);
        if (cached != null && !cached.isExpired() && StringUtils.isNotBlank(cached.getAccessToken())) {
            return cached.getAccessToken();
        }

        synchronized (tokenCache) {
            TokenCacheEntry cachedAgain = tokenCache.get(cacheKey);
            if (cachedAgain != null && !cachedAgain.isExpired() && StringUtils.isNotBlank(cachedAgain.getAccessToken())) {
                return cachedAgain.getAccessToken();
            }

            TokenCacheEntry refreshed = fetchToken(service, client);
            tokenCache.put(cacheKey, refreshed);
            return refreshed.getAccessToken();
        }
    }

    private TokenCacheEntry fetchToken(FeignAuthProperties.ServiceConfig service, FeignAuthProperties.ClientConfig client) {
        FeignAuthProperties.AuthConfig auth = service.getAuth();
        if (StringUtils.isBlank(auth.getTokenUrl())) {
            throw new IllegalStateException("token-url is required for OAuth2 service baseUrl=" + service.getBaseUrl());
        }
        if (StringUtils.isBlank(client.getId()) || StringUtils.isBlank(client.getSecret())) {
            throw new IllegalStateException("OAuth2 client id and secret are required for service baseUrl=" + service.getBaseUrl());
        }

        String method = auth.getMethod() == null ? "post" : auth.getMethod().trim().toLowerCase();
        if ("get".equals(method)) {
            return fetchTokenByGet(auth, client);
        }
        if ("post".equals(method)) {
            return fetchTokenByPost(auth, client);
        }
        throw new IllegalStateException("Unsupported OAuth2 token method: " + auth.getMethod());
    }

    private TokenCacheEntry fetchTokenByGet(FeignAuthProperties.AuthConfig auth, FeignAuthProperties.ClientConfig client) {
        FeignAuthProperties.RequestFields fields = auth.getRequestFields();
        String url = UriComponentsBuilder.fromHttpUrl(auth.getTokenUrl())
                .queryParam(fields.getClientId(), client.getId())
                .queryParam(fields.getClientSecret(), client.getSecret())
                .queryParam(fields.getGrantType(), client.getGrantType())
                .toUriString();

        ResponseEntity<String> response = restTemplate.getForEntity(url, String.class);
        return parseTokenResponse(response, auth.getExpireAheadSeconds());
    }

    private TokenCacheEntry fetchTokenByPost(FeignAuthProperties.AuthConfig auth, FeignAuthProperties.ClientConfig client) {
        FeignAuthProperties.RequestFields fields = auth.getRequestFields();
        Map<String, String> body = new HashMap<>();
        body.put(fields.getClientId(), client.getId());
        body.put(fields.getClientSecret(), client.getSecret());
        body.put(fields.getGrantType(), client.getGrantType());

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);

        ResponseEntity<String> response = restTemplate.postForEntity(auth.getTokenUrl(), new HttpEntity<>(body, headers), String.class);
        return parseTokenResponse(response, auth.getExpireAheadSeconds());
    }

    private TokenCacheEntry parseTokenResponse(ResponseEntity<String> response, long expireAheadSeconds) {
        if (response == null || !response.getStatusCode().is2xxSuccessful()) {
            int status = response == null ? -1 : response.getStatusCodeValue();
            throw new IllegalStateException("Token request failed: " + status);
        }
        try {
            JsonNode root = objectMapper.readTree(response.getBody() == null ? "" : response.getBody());
            String token = firstText(root, "access_token", "accessToken", "token");
            if (StringUtils.isBlank(token)) {
                throw new IllegalStateException("Token field not found in response");
            }

            long expiresIn = firstPositiveLong(root, DEFAULT_EXPIRES_IN_SECONDS, "expires_in", "expiresIn", "expire_in", "expireIn");
            long refreshIn = Math.max(0L, expiresIn - Math.max(0L, expireAheadSeconds));

            TokenCacheEntry entry = new TokenCacheEntry();
            entry.setAccessToken(token);
            entry.setExpireAt(System.currentTimeMillis() + refreshIn * 1000L);
            return entry;
        } catch (Exception e) {
            log.error("Parse token response failed", e);
            throw new IllegalStateException("Parse token response failed: " + e.getMessage(), e);
        }
    }

    private static int bestPrefixMatchLength(Iterable<String> prefixes, String requestPath) {
        if (prefixes == null || StringUtils.isBlank(requestPath)) {
            return 0;
        }

        return StreamSupport.stream(prefixes.spliterator(), false)
                .map(TokenFetcher::normalizePath)
                .filter(StringUtils::isNotBlank)
                .filter(prefix -> StringUtils.startsWith(requestPath, prefix))
                .mapToInt(String::length)
                .max()
                .orElse(0);
    }

    private static String normalizePath(String path) {
        if (StringUtils.isBlank(path)) {
            return "";
        }
        return StringUtils.prependIfMissing(StringUtils.substringBefore(path, "?"), "/");
    }

    private static String firstText(JsonNode root, String... fieldNames) {
        return Arrays.stream(fieldNames)
                .map(root::get)
                .filter(Objects::nonNull)
                .filter(JsonNode::isValueNode)
                .map(JsonNode::asText)
                .filter(StringUtils::isNotBlank)
                .findFirst()
                .orElse(null);
    }

    private static long firstPositiveLong(JsonNode root, long defaultValue, String... fieldNames) {
        for (String name : fieldNames) {
            JsonNode node = root.get(name);
            if (node == null || node.isNull()) {
                continue;
            }
            Long value = null;
            if (node.isNumber()) {
                value = node.asLong();
            } else if (node.isTextual()) {
                long parsedValue = NumberUtils.toLong(StringUtils.trim(node.asText()), Long.MIN_VALUE);
                value = parsedValue == Long.MIN_VALUE ? null : parsedValue;
            }
            if (value != null && value > 0) {
                return value;
            }
        }
        return defaultValue;
    }
}
