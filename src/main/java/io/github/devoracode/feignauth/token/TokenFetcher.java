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

/**
 * Fetches and caches OAuth2 access tokens for configured services.
 *
 * <p>For each (service, client) pair, the token is retrieved once and then stored in
 * an in-memory {@link ConcurrentHashMap}. Subsequent calls return the cached token
 * until it expires (taking {@code expireAheadSeconds} into account), at which point a
 * new token is fetched under a {@code synchronized} block to avoid duplicate requests.
 *
 * <p>Token acquisition supports both {@code GET} and {@code POST} HTTP methods. The
 * field names used in the request (client ID, client secret, grant type) are
 * configurable via {@link FeignAuthProperties.RequestFields}.
 *
 * <p>Token responses are parsed leniently: the access token value is looked up under
 * the field names {@code access_token}, {@code accessToken}, and {@code token}
 * (in that order). Expiry information is read from {@code expires_in}, {@code expiresIn},
 * {@code expire_in}, and {@code expireIn}; if none is present, a default TTL of
 * {@value DEFAULT_EXPIRES_IN_SECONDS} seconds is used.
 *
 * @author Wenjie Liu
 * @since 1.0.0
 * @see TokenCacheEntry
 * @see FeignAuthProperties
 */
@Slf4j
@RequiredArgsConstructor
public class TokenFetcher {

    /** Default token TTL used when the token response does not include an expiry field. */
    private static final long DEFAULT_EXPIRES_IN_SECONDS = 7200L;

    private final FeignAuthProperties feignAuthProperties;
    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;

    /** In-memory token cache keyed by {@code "<serviceName>:<clientId>"}. */
    private final Map<String, TokenCacheEntry> tokenCache = new ConcurrentHashMap<>();

    /**
     * Returns a valid OAuth2 access token for the given service and request path.
     *
     * <p>The method selects the appropriate {@link FeignAuthProperties.ClientConfig} for
     * the provided {@code requestPath} and then returns a cached or freshly fetched token.
     *
     * @param serviceName the logical service name as defined in
     *                    {@link FeignAuthProperties#services}
     * @param requestPath the normalized request path used to select the correct client
     * @return a non-blank access token string
     * @throws IllegalStateException if the service is not configured, is not in OAuth2 mode,
     *                               no matching client exists, or token acquisition fails
     */
    public String getToken(String serviceName, String requestPath) {
        FeignAuthProperties.ServiceConfig service = getServiceConfig(serviceName);
        FeignAuthProperties.AuthConfig auth = service.getAuth();
        if (auth == null || !auth.isOAuth2()) {
            throw new IllegalStateException("Service '" + serviceName + "' is not OAuth2 mode");
        }

        FeignAuthProperties.ClientConfig client = resolveClient(serviceName, service, requestPath);
        return getCachedOrFetch(serviceName, service, client);
    }

    /**
     * Selects the {@link FeignAuthProperties.ClientConfig} that best matches the given
     * request path within a service.
     *
     * <p>Selection follows the longest-prefix-match rule:
     * <ol>
     *   <li>Clients whose {@code pathPrefixes} match the request path are ranked by
     *       the length of the matched prefix; the client with the longest match wins.</li>
     *   <li>If no prefix matches, the unique default client (one without any
     *       {@code pathPrefixes}) is returned.</li>
     * </ol>
     *
     * @param serviceName the logical service name (used in exception messages)
     * @param service     the service configuration
     * @param requestPath the normalized request path
     * @return the selected {@link FeignAuthProperties.ClientConfig}; never {@code null}
     * @throws IllegalStateException if no client matches, multiple clients share the same
     *                               best prefix length, or multiple default clients are found
     */
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

    /**
     * Retrieves the service configuration for the given service name.
     *
     * @param serviceName the logical service name
     * @return the {@link FeignAuthProperties.ServiceConfig}; never {@code null}
     * @throws IllegalStateException if the service is not found in the configuration
     */
    private FeignAuthProperties.ServiceConfig getServiceConfig(String serviceName) {
        Map<String, FeignAuthProperties.ServiceConfig> services = feignAuthProperties.getServices();
        FeignAuthProperties.ServiceConfig service = services == null ? null : services.get(serviceName);
        if (service == null) {
            throw new IllegalStateException("Service config not found: " + serviceName);
        }
        return service;
    }

    /**
     * Returns the cached token if it is still valid, or fetches a fresh token otherwise.
     *
     * <p>A double-checked locking pattern is used to prevent redundant token requests
     * when multiple threads detect an expired cache entry simultaneously.
     * An INFO line is logged only when a new token is actually fetched from the endpoint.
     *
     * @param serviceName the logical service name (used as part of the cache key)
     * @param service     the service configuration
     * @param client      the selected OAuth2 client
     * @return a valid, non-blank access token
     */
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

            log.info("FeignAuth [oauth2] fetching new token for service='{}', clientId='{}'",
                    serviceName, client.getId());
            TokenCacheEntry refreshed = fetchToken(service, client);
            tokenCache.put(cacheKey, refreshed);
            log.info("FeignAuth [oauth2] token cached for service='{}', clientId='{}', expireAt={}",
                    serviceName, client.getId(), refreshed.getExpireAt());
            return refreshed.getAccessToken();
        }
    }

    /**
     * Fetches a new token from the configured token endpoint, delegating to either
     * {@link #fetchTokenByGet} or {@link #fetchTokenByPost} based on
     * {@link FeignAuthProperties.AuthConfig#getMethod()}.
     *
     * @param service the service configuration
     * @param client  the OAuth2 client credentials to use
     * @return a populated {@link TokenCacheEntry}
     * @throws IllegalStateException if the method is unsupported, or required fields
     *                               ({@code tokenUrl}, {@code id}, {@code secret}) are missing
     */
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

    /**
     * Fetches a token using an HTTP GET request, appending credentials as query parameters.
     *
     * @param auth   the authentication configuration providing the token URL and field names
     * @param client the OAuth2 client credentials
     * @return a populated {@link TokenCacheEntry}
     */
    private TokenCacheEntry fetchTokenByGet(FeignAuthProperties.AuthConfig auth, FeignAuthProperties.ClientConfig client) {
        FeignAuthProperties.RequestFields fields = auth.getRequestFields();
        String url = UriComponentsBuilder.fromHttpUrl(auth.getTokenUrl())
                .queryParam(fields.getClientId(), client.getId())
                .queryParam(fields.getClientSecret(), client.getSecret())
                .queryParam(fields.getGrantType(), client.getGrantType())
                .toUriString();

        ResponseEntity<String> response = restTemplate.getForEntity(url, String.class);
        return parseTokenResponse(response, auth.getExpireAheadSeconds(), auth.getTokenField());
    }

    /**
     * Fetches a token using an HTTP POST request with a JSON body containing credentials.
     *
     * @param auth   the authentication configuration providing the token URL and field names
     * @param client the OAuth2 client credentials
     * @return a populated {@link TokenCacheEntry}
     */
    private TokenCacheEntry fetchTokenByPost(FeignAuthProperties.AuthConfig auth, FeignAuthProperties.ClientConfig client) {
        FeignAuthProperties.RequestFields fields = auth.getRequestFields();
        Map<String, String> body = new HashMap<>();
        body.put(fields.getClientId(), client.getId());
        body.put(fields.getClientSecret(), client.getSecret());
        body.put(fields.getGrantType(), client.getGrantType());

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);

        ResponseEntity<String> response = restTemplate.postForEntity(auth.getTokenUrl(), new HttpEntity<>(body, headers), String.class);
        return parseTokenResponse(response, auth.getExpireAheadSeconds(), auth.getTokenField());
    }

    /**
     * Parses the HTTP response from a token endpoint and builds a {@link TokenCacheEntry}.
     *
     * <p>Token extraction strategy (in priority order):
     * <ol>
     *   <li>If {@code tokenField} is non-blank, navigate the dot-separated path
     *       (e.g. {@code data.accessToken}) and read the value at that node.</li>
     *   <li>Otherwise, try the built-in field names {@code access_token},
     *       {@code accessToken}, and {@code token} in that order.</li>
     * </ol>
     *
     * <p>The expiry duration is looked up under {@code expires_in}, {@code expiresIn},
     * {@code expire_in}, and {@code expireIn}. If none is present, the default of
     * {@value DEFAULT_EXPIRES_IN_SECONDS} seconds is used. The effective cache TTL is
     * {@code (expiresIn - expireAheadSeconds)} seconds, clamped to a minimum of zero.
     *
     * @param response           the raw HTTP response from the token endpoint
     * @param expireAheadSeconds number of seconds before actual expiry at which the
     *                           cache entry should be considered stale
     * @param tokenField         dot-separated JSON path for the token field, or {@code null}/blank
     *                           to use built-in auto-detection
     * @return a populated {@link TokenCacheEntry}
     * @throws IllegalStateException if the response is not 2xx, the response body cannot
     *                               be parsed, or no token field is found
     */
    private TokenCacheEntry parseTokenResponse(ResponseEntity<String> response, long expireAheadSeconds, String tokenField) {
        if (response == null || !response.getStatusCode().is2xxSuccessful()) {
            int status = response == null ? -1 : response.getStatusCodeValue();
            throw new IllegalStateException("Token request failed: " + status);
        }
        try {
            JsonNode root = objectMapper.readTree(response.getBody() == null ? "" : response.getBody());

            String token;
            if (StringUtils.isNotBlank(tokenField)) {
                token = resolveTokenByPath(root, tokenField.trim());
                if (StringUtils.isBlank(token)) {
                    throw new IllegalStateException("Token field '" + tokenField + "' not found or blank in response");
                }
            } else {
                token = firstText(root, "access_token", "accessToken", "token");
                if (StringUtils.isBlank(token)) {
                    throw new IllegalStateException("Token field not found in response");
                }
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

    /**
     * Navigates a dot-separated JSON path (e.g. {@code data.accessToken}) starting from
     * {@code root} and returns the text value of the target node.
     *
     * <p>Each segment of the path must be a plain field name. Array indexing is not
     * supported. If any intermediate node is missing or not an object, {@code null}
     * is returned.
     *
     * @param root      the root JSON node to start from
     * @param tokenField dot-separated field path, e.g. {@code data.accessToken}
     * @return the text value at the resolved node, or {@code null} if the path cannot
     *         be resolved or the final node is not a non-blank text value
     */
    private static String resolveTokenByPath(JsonNode root, String tokenField) {
        String[] segments = tokenField.split("\\.");
        JsonNode node = root;
        for (String segment : segments) {
            if (node == null || !node.isObject()) {
                return null;
            }
            node = node.get(segment);
        }
        if (node == null || !node.isValueNode()) {
            return null;
        }
        String value = node.asText();
        return StringUtils.isBlank(value) ? null : value;
    }

    /**
     * Returns the length of the longest prefix in {@code prefixes} that matches the
     * beginning of {@code requestPath}, or {@code 0} if no prefix matches.
     *
     * @param prefixes    an iterable of candidate path prefixes; may be {@code null}
     * @param requestPath the normalized request path to test; may be blank
     * @return the length of the best matching prefix, or {@code 0} if none matches
     */
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

    /**
     * Normalizes a request path by ensuring it starts with {@code /} and stripping
     * any query string.
     *
     * @param path the raw path; may be {@code null} or blank
     * @return the normalized path, or an empty string if {@code path} is blank
     */
    private static String normalizePath(String path) {
        if (StringUtils.isBlank(path)) {
            return "";
        }
        return StringUtils.prependIfMissing(StringUtils.substringBefore(path, "?"), "/");
    }

    /**
     * Returns the text value of the first field in {@code root} whose name is in
     * {@code fieldNames} and whose value is a non-blank text node.
     *
     * @param root       the JSON object to search
     * @param fieldNames candidate field names to try in order
     * @return the first matching non-blank text value, or {@code null} if none is found
     */
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

    /**
     * Returns the first positive {@code long} value found in {@code root} under any of
     * the given {@code fieldNames}, or {@code defaultValue} if none is found.
     *
     * <p>Both numeric and textual JSON node types are supported.
     *
     * @param root         the JSON object to search
     * @param defaultValue the fallback value when no valid field is found
     * @param fieldNames   candidate field names to try in order
     * @return the first positive long value, or {@code defaultValue}
     */
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
