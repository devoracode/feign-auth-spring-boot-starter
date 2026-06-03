package io.github.devoracode.feignauth.feign;

import feign.RequestInterceptor;
import feign.RequestTemplate;
import io.github.devoracode.feignauth.config.FeignAuthProperties;
import io.github.devoracode.feignauth.token.TokenFetcher;
import lombok.RequiredArgsConstructor;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * Feign {@link RequestInterceptor} that injects authentication headers based on
 * the per-service configuration in {@link FeignAuthProperties}.
 *
 * <p>For each outgoing Feign request the interceptor:
 * <ul>
 *   <li>Matches the request's base URL and path to a service configuration entry.</li>
 *   <li>Skips injection if no matching service is found.</li>
 *   <li>Injects an API key header for {@code api-key} services.</li>
 *   <li>Fetches (with caching) and injects a token for {@code oauth2} services.</li>
 * </ul>
 *
 * <p>One INFO log line is emitted per request describing the auth action taken.
 * WARN is emitted when no service matches. ERROR is emitted on token fetch failure.
 *
 * @author Wenjie Liu
 * @since 1.0.0
 * @see FeignClientConfig
 * @see FeignAuthProperties
 */
@RequiredArgsConstructor
public class FeignAuthRequestInterceptor implements RequestInterceptor {

    private static final Logger log = LoggerFactory.getLogger(FeignAuthRequestInterceptor.class);

    private final FeignAuthProperties feignAuthProperties;
    private final TokenFetcher tokenFetcher;

    /**
     * Intercepts the Feign request and injects the appropriate authentication header.
     *
     * <p>Emits one INFO line per request summarising the auth action taken, or a
     * WARN line when no service configuration matches the request.
     *
     * <p>When {@code @FeignClient} is declared with a {@code path} attribute, OpenFeign
     * folds that path prefix into {@code feignTarget().url()}. This method splits the
     * target URL into its base (scheme + host + port) and any path prefix so that
     * {@code base-url} in the configuration is always matched against the bare origin,
     * and the full request path (target path prefix + method path) is used for
     * {@code path-prefixes} matching.
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

        String targetUrl = template.feignTarget().url();

        // When @FeignClient has a path attribute, OpenFeign appends it to feignTarget().url().
        // Split into bare origin (scheme + host + port) and the embedded path prefix so that
        // base-url matching always works against the plain origin.
        String baseUrl;
        String targetPathPrefix;
        try {
            java.net.URI uri = java.net.URI.create(targetUrl);
            String path = StringUtils.defaultString(uri.getRawPath(), "");
            // Reconstruct the bare origin without any path component
            baseUrl = uri.getScheme() + "://" + uri.getAuthority();
            targetPathPrefix = StringUtils.stripEnd(path, "/");
        } catch (Exception e) {
            // Fallback: treat the whole targetUrl as baseUrl (original behaviour)
            baseUrl = targetUrl;
            targetPathPrefix = "";
        }

        // Full request path = path prefix embedded in target URL + the per-method path
        String methodPath   = normalizePath(template.path());
        String requestPath  = StringUtils.isNotBlank(targetPathPrefix)
                ? targetPathPrefix + methodPath
                : methodPath;

        MatchedService matched = matchService(baseUrl, requestPath);
        if (matched == null) {
            log.warn("FeignAuth: no service config matched for baseUrl={}, path={} — request will be sent without auth header",
                    baseUrl, requestPath);
            return;
        }

        FeignAuthProperties.AuthConfig auth = matched.getService().getAuth();
        if (auth == null) {
            log.warn("FeignAuth: service '{}' has no auth config — request will be sent without auth header",
                    matched.getServiceName());
            return;
        }

        if (auth.isApiKey()) {
            if (StringUtils.isBlank(auth.getValue())) {
                throw new IllegalStateException("API key value is required for service: " + matched.getServiceName());
            }
            String headerName = auth.resolvedHeaderName();
            template.header(headerName, auth.getValue());
            log.info("FeignAuth [api-key] service='{}' header='{}' path={}",
                    matched.getServiceName(), headerName, requestPath);
            return;
        }

        if (auth.isOAuth2()) {
            String token;
            try {
                token = tokenFetcher.getToken(matched.getServiceName(), requestPath);
            } catch (Exception e) {
                log.error("FeignAuth [oauth2] failed to obtain token for service='{}', path={}: {}",
                        matched.getServiceName(), requestPath, e.getMessage(), e);
                throw e;
            }
            String headerName = auth.resolvedHeaderName();
            template.header(headerName, token);
            log.info("FeignAuth [oauth2] service='{}' header='{}' path={}",
                    matched.getServiceName(), headerName, requestPath);
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
     *       fallback service for the base URL is selected. Multiple fallback services
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

    /**
     * Compares two base URLs for equality, ignoring trailing slashes and case differences.
     *
     * @param left  the first URL
     * @param right the second URL
     * @return {@code true} if both URLs refer to the same base URL
     */
    private static boolean isSameBaseUrl(String left, String right) {
        String a = StringUtils.stripEnd(StringUtils.trimToEmpty(left), "/");
        String b = StringUtils.stripEnd(StringUtils.trimToEmpty(right), "/");
        return StringUtils.isNotBlank(a) && StringUtils.equalsIgnoreCase(a, b);
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
