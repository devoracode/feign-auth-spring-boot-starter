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

@Configuration
@Slf4j
@RequiredArgsConstructor
public class FeignClientConfig {

    private final FeignAuthProperties feignAuthProperties;
    private final TokenFetcher tokenFetcher;

    @Bean
    public RequestInterceptor feignAuthRequestInterceptor() {
        return new FeignAuthRequestInterceptor(feignAuthProperties, tokenFetcher);
    }

    @RequiredArgsConstructor
    public static class FeignAuthRequestInterceptor implements RequestInterceptor {

        private final FeignAuthProperties feignAuthProperties;
        private final TokenFetcher tokenFetcher;

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

        private static String normalizePath(String path) {
            if (StringUtils.isBlank(path)) {
                return "";
            }
            return StringUtils.prependIfMissing(StringUtils.substringBefore(path, "?"), "/");
        }
    }

    private static boolean isSameBaseUrl(String left, String right) {
        String a = trimTrailingSlash(left);
        String b = trimTrailingSlash(right);
        return StringUtils.isNotBlank(a) && StringUtils.equalsIgnoreCase(a, b);
    }

    private static String trimTrailingSlash(String url) {
        if (StringUtils.isBlank(url)) {
            return url;
        }
        return StringUtils.stripEnd(url.trim(), "/");
    }

    @RequiredArgsConstructor
    public static class MatchedService {
        private final String serviceName;
        private final FeignAuthProperties.ServiceConfig service;

        public String getServiceName() {
            return serviceName;
        }

        public FeignAuthProperties.ServiceConfig getService() {
            return service;
        }
    }

    @RequiredArgsConstructor
    private static class PrefixHit {
        private final MatchedService service;
        private final int matchLength;

        public MatchedService getService() {
            return service;
        }

        public int getMatchLength() {
            return matchLength;
        }
    }
}
