package io.github.devoracode.feignauth.feign;

import feign.RequestInterceptor;
import feign.RequestTemplate;
import io.github.devoracode.feignauth.config.FeignAuthProperties;
import io.github.devoracode.feignauth.token.TokenFetcher;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * Feign client configuration that registers the authentication request interceptor.
 *
 * <p>This class should be referenced on each {@code @FeignClient} via the
 * {@code configuration} attribute:
 *
 * <pre>
 * &#64;FeignClient(
 *     name = "order-client",
 *     url  = "${feign.services.order.base-url}",
 *     configuration = FeignClientConfig.class
 * )
 * public interface OrderFeignClient { ... }
 * </pre>
 *
 * <p>When the interceptor is invoked for a request, it:
 * <ol>
 *   <li>Extracts the target base URL and request path from the {@link RequestTemplate}.</li>
 *   <li>Looks up the matching service and auth configuration via
 *       {@link FeignAuthRequestInterceptor#matchService}.</li>
 *   <li>For {@code api-key} services, injects the static key directly.</li>
 *   <li>For {@code oauth2} services, retrieves (or caches) a token via
 *       {@link TokenFetcher#getToken} and injects it into the configured header.</li>
 * </ol>
 *
 * @author Wenjie Liu
 * @since 1.0.0
 * @see FeignAuthRequestInterceptor
 * @see FeignAuthProperties
 */
@Configuration
@Slf4j
@RequiredArgsConstructor
public class FeignClientConfig {

    private final FeignAuthProperties feignAuthProperties;
    private final TokenFetcher tokenFetcher;

    /**
     * Creates and registers the {@link FeignAuthRequestInterceptor} as a Spring bean.
     *
     * @return a new {@link RequestInterceptor} that handles auth header injection
     */
    @Bean
    public RequestInterceptor feignAuthRequestInterceptor() {
        return new FeignAuthRequestInterceptor(feignAuthProperties, tokenFetcher);
    }

    /**
     * Feign {@link RequestInterceptor} that injects authentication headers based on
     * the per-service configuration in {@link FeignAuthProperties}.
     *
     * <p>For each outgoing Feign request the interceptor:
     * <ul>
     *   <li>Matches the request's base URL and path to a service configuration entry.</li>
     *   <li>Skips injection if no matching service is found.</li>
     *   <li>Injects an API key header for {@code api-key} services.</li>
     *   <li>Fetches (with caching) and injects a Bearer token for {@code oauth2} services.</li>
     * </ul>
     */
    @RequiredArgsConstructor
    public static class FeignAuthRequestInterceptor implements RequestInterceptor {

        private final FeignAuthProperties feignAuthProperties;
        private final TokenFetcher tokenFetcher;

        /**
         * Intercepts the Feign request and injects the appropriate authentication header.
         *
         * <p>The method is a no-op when {@code template} or its {@code feignTarget} is
         * {@code null}, or when no matching service configuration is found.
         *
         * @param template the current Feign {@link RequestTemplate}; may be {@code null}
         * @throws IllegalStateException if an API key service is missing its {@code value},
         *                               or if an OAuth2 token cannot be acquired
         */
        @Override
        public void apply(RequestTemplate template) {
            if (template == null || template.feignTarget() == null) {
                return;
            }

            String baseUrl = template.feignTarget().url();
            String requestPath = normalizePath(template.path());
            MatchedService matched = matchService(baseUrl, requestPath);
            if (matched == null) {
                return;
            }

            FeignAuthProperties.AuthConfig auth = matched.getService().getAuth();
            if (auth == null) {
                return;
            }

            if (auth.isApiKey()) {
                if (StringUtils.isBlank(auth.getValue())) {
                    throw new IllegalStateException("API key value is required for service: " + matched.getServiceName());
                }
                template.header(auth.resolvedHeaderName(), auth.getValue());
                return;
            }

            if (auth.isOAuth2()) {
                String token = tokenFetcher.getToken(matched.getServiceName(), requestPath);
                template.header(auth.resolvedHeaderName(), token);
            }
        }

        /**
         * Resolves which configured service should handle the given request, based on
         * the request's base URL and path.
         *
         * <p>Matching is performed in two stages:
         * <ol>
         *   <li><strong>Prefix match</strong> – services whose {@code pathPrefixes} (for
         *       API key) or whose clients' {@code pathPrefixes} (for OAuth2) match the
         *       request path are ranked by matched-prefix length; the longest match wins.
         *       A tie at the same length is an error.</li>
         *   <li><strong>Fallback match</strong> – if no prefix matched, the unique
         *       fallback service for the base URL is selected.  Multiple fallback services
         *       for the same base URL is an error.</li>
         * </ol>
         *
         * @param baseUrl     the base URL of the Feign target (trailing slash ignored)
         * @param requestPath the normalized request path (starts with {@code /},
         *                    no query string)
         * @return the matched {@link MatchedService}, or {@code null} if no service
         *         configuration matches the base URL at all
         * @throws IllegalStateException if two services share an equally specific
         *                               path-prefix match, or if multiple fallback services
         *                               are configured for the same base URL
         */
        public MatchedService matchService(String baseUrl, String requestPath) {
            Map<String, FeignAuthProperties.ServiceConfig> services = feignAuthProperties.getServices();
            if (services == null || services.isEmpty()) {
                return null;
            }

            List<MatchedService> baseUrlCandidates = new ArrayList<>();
            for (Map.Entry<String, FeignAuthProperties.ServiceConfig> entry : services.entrySet()) {
                FeignAuthProperties.ServiceConfig service = entry.getValue();
                if (service != null && isSameBaseUrl(baseUrl, service.getBaseUrl())) {
                    baseUrlCandidates.add(new MatchedService(entry.getKey(), service));
                }
            }
            if (baseUrlCandidates.isEmpty()) {
                return null;
            }

            List<PrefixHit> prefixHits = new ArrayList<>();
            for (MatchedService candidate : baseUrlCandidates) {
                int matchLength = bestPrefixMatchLength(candidate.getService(), requestPath);
                if (matchLength > 0) {
                    prefixHits.add(new PrefixHit(candidate, matchLength));
                }
            }

            if (!prefixHits.isEmpty()) {
                prefixHits.sort(Comparator.comparingInt(PrefixHit::getMatchLength).reversed());
                PrefixHit best = prefixHits.get(0);
                if (prefixHits.size() > 1 && prefixHits.get(1).getMatchLength() == best.getMatchLength()) {
                    throw new IllegalStateException("Multiple services matched requestPath=" + requestPath + " for baseUrl=" + baseUrl);
                }
                return best.getService();
            }

            List<MatchedService> fallbackCandidates = new ArrayList<>();
            for (MatchedService candidate : baseUrlCandidates) {
                FeignAuthProperties.AuthConfig auth = candidate.getService().getAuth();
                if (auth != null && auth.isFallback()) {
                    fallbackCandidates.add(candidate);
                }
            }

            if (fallbackCandidates.isEmpty()) {
                return null;
            }
            if (fallbackCandidates.size() > 1) {
                throw new IllegalStateException("Multiple fallback services configured for baseUrl=" + baseUrl);
            }
            return fallbackCandidates.get(0);
        }

        /**
         * Returns the length of the longest path prefix configured on the given service
         * that matches {@code requestPath}, or {@code 0} if none matches.
         *
         * <p>For API key services the top-level {@code pathPrefixes} list is used.
         * For OAuth2 services, only non-default clients' {@code pathPrefixes} are
         * considered (default clients have no prefixes by definition).
         *
         * @param service     the service configuration to inspect
         * @param requestPath the normalized request path
         * @return the best matched prefix length, or {@code 0}
         */
        private static int bestPrefixMatchLength(FeignAuthProperties.ServiceConfig service, String requestPath) {
            if (StringUtils.isBlank(requestPath) || service.getAuth() == null) {
                return 0;
            }

            FeignAuthProperties.AuthConfig auth = service.getAuth();
            if (auth.isApiKey()) {
                return bestPrefixMatchLength(auth.getPathPrefixes(), requestPath);
            }
            if (!auth.isOAuth2() || auth.getClients() == null) {
                return 0;
            }

            int best = 0;
            for (FeignAuthProperties.ClientConfig client : auth.getClients()) {
                if (client != null && !client.isDefaultClient()) {
                    best = Math.max(best, bestPrefixMatchLength(client.getPathPrefixes(), requestPath));
                }
            }
            return best;
        }

        /**
         * Returns the length of the longest prefix in {@code prefixes} that matches the
         * start of {@code requestPath}, or {@code 0} if none matches.
         *
         * @param prefixes    the list of candidate path prefixes; may be {@code null} or empty
         * @param requestPath the normalized request path to match against
         * @return the best matched prefix length, or {@code 0}
         */
        private static int bestPrefixMatchLength(List<String> prefixes, String requestPath) {
            if (prefixes == null || prefixes.isEmpty()) {
                return 0;
            }

            return prefixes.stream()
                    .map(FeignAuthRequestInterceptor::normalizePath)
                    .filter(StringUtils::isNotBlank)
                    .filter(prefix -> StringUtils.startsWith(requestPath, prefix))
                    .mapToInt(String::length)
                    .max()
                    .orElse(0);
        }

        /**
         * Normalizes a path by ensuring it starts with {@code /} and stripping any
         * query string.
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
    }

    /**
     * Compares two base URLs for equality, ignoring trailing slashes and case differences.
     *
     * @param left  the first URL
     * @param right the second URL
     * @return {@code true} if both URLs refer to the same base URL
     */
    private static boolean isSameBaseUrl(String left, String right) {
        String a = trimTrailingSlash(left);
        String b = trimTrailingSlash(right);
        return StringUtils.isNotBlank(a) && StringUtils.equalsIgnoreCase(a, b);
    }

    /**
     * Trims trailing slash characters from a URL.
     *
     * @param url the URL to trim; may be {@code null} or blank
     * @return the trimmed URL, or the original value if it is blank
     */
    private static String trimTrailingSlash(String url) {
        if (StringUtils.isBlank(url)) {
            return url;
        }
        return StringUtils.stripEnd(url.trim(), "/");
    }

    /**
     * Holds a service name and its associated {@link FeignAuthProperties.ServiceConfig},
     * representing a candidate match during service resolution.
     */
    @RequiredArgsConstructor
    public static class MatchedService {

        /** The logical service name as configured in {@link FeignAuthProperties#services}. */
        private final String serviceName;

        /** The service configuration associated with {@link #serviceName}. */
        private final FeignAuthProperties.ServiceConfig service;

        /**
         * Returns the logical service name.
         *
         * @return the service name; never {@code null}
         */
        public String getServiceName() {
            return serviceName;
        }

        /**
         * Returns the service configuration.
         *
         * @return the {@link FeignAuthProperties.ServiceConfig}; never {@code null}
         */
        public FeignAuthProperties.ServiceConfig getService() {
            return service;
        }
    }

    /**
     * Internal helper that pairs a {@link MatchedService} with the length of the
     * longest matching path prefix, used for ranking candidates during service resolution.
     */
    @RequiredArgsConstructor
    private static class PrefixHit {

        /** The matched service candidate. */
        private final MatchedService service;

        /** The length of the best matching path prefix for this candidate. */
        private final int matchLength;

        /**
         * Returns the matched service candidate.
         *
         * @return the {@link MatchedService}; never {@code null}
         */
        public MatchedService getService() {
            return service;
        }

        /**
         * Returns the length of the best matching path prefix.
         *
         * @return a positive integer representing the prefix match length
         */
        public int getMatchLength() {
            return matchLength;
        }
    }
}
