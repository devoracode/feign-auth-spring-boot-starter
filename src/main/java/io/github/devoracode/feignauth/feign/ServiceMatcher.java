package io.github.devoracode.feignauth.feign;

import io.github.devoracode.feignauth.autoconfigure.FeignAuthProperties;
import io.github.devoracode.feignauth.support.PathUtils;
import org.springframework.util.Assert;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * Resolves configured services for outgoing Feign requests based on base URL and path.
 *
 * @author Wenjie Liu
 * @since 1.0.0
 */
public class ServiceMatcher {

	private final FeignAuthProperties properties;

	public ServiceMatcher(FeignAuthProperties properties) {
		Assert.notNull(properties, "properties must not be null");
		this.properties = properties;
	}

	/**
	 * Resolves which configured service should handle the given request.
	 * @param baseUrl the base URL of the Feign target
	 * @param requestPath the normalized request path
	 * @return the resolved service, or {@code null} when no service matches the base URL
	 */
	public ResolvedService match(String baseUrl, String requestPath) {
		Map<String, FeignAuthProperties.Service> services = this.properties.getServices();
		if (services == null || services.isEmpty()) {
			return null;
		}

		List<ResolvedService> baseUrlCandidates = new ArrayList<>();
		for (Map.Entry<String, FeignAuthProperties.Service> entry : services.entrySet()) {
			FeignAuthProperties.Service service = entry.getValue();
			if (service != null && PathUtils.isSameBaseUrl(baseUrl, service.getBaseUrl())) {
				baseUrlCandidates.add(new ResolvedService(entry.getKey(), service));
			}
		}
		if (baseUrlCandidates.isEmpty()) {
			return null;
		}

		List<PrefixMatch> prefixMatches = new ArrayList<>();
		for (ResolvedService candidate : baseUrlCandidates) {
			int matchLength = bestPrefixMatchLength(candidate.getService(), requestPath);
			if (matchLength > 0) {
				prefixMatches.add(new PrefixMatch(candidate, matchLength));
			}
		}

		if (!prefixMatches.isEmpty()) {
			prefixMatches.sort(Comparator.comparingInt(PrefixMatch::getMatchLength).reversed());
			PrefixMatch best = prefixMatches.get(0);
			if (prefixMatches.size() > 1 && prefixMatches.get(1).getMatchLength() == best.getMatchLength()) {
				throw new IllegalStateException(
						"Multiple services matched requestPath=" + requestPath + " for baseUrl=" + baseUrl);
			}
			return best.getService();
		}

		List<ResolvedService> fallbackCandidates = new ArrayList<>();
		for (ResolvedService candidate : baseUrlCandidates) {
			FeignAuthProperties.Auth auth = candidate.getService().getAuth();
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

	private static int bestPrefixMatchLength(FeignAuthProperties.Service service, String requestPath) {
		if (!org.springframework.util.StringUtils.hasText(requestPath) || service.getAuth() == null) {
			return 0;
		}

		FeignAuthProperties.Auth auth = service.getAuth();
		if (auth.isApiKey()) {
			return PathUtils.bestPrefixMatchLength(auth.getPathPrefixes(), requestPath);
		}
		if (!auth.isOAuth2() || auth.getClients() == null) {
			return 0;
		}

		int best = 0;
		for (FeignAuthProperties.Client client : auth.getClients()) {
			if (client != null && !client.isDefaultClient()) {
				best = Math.max(best, PathUtils.bestPrefixMatchLength(client.getPathPrefixes(), requestPath));
			}
		}
		return best;
	}

	private static final class PrefixMatch {

		private final ResolvedService service;

		private final int matchLength;

		private PrefixMatch(ResolvedService service, int matchLength) {
			this.service = service;
			this.matchLength = matchLength;
		}

		private ResolvedService getService() {
			return this.service;
		}

		private int getMatchLength() {
			return this.matchLength;
		}

	}

}
