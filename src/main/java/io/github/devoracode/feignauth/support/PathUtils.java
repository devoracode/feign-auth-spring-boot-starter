package io.github.devoracode.feignauth.support;

import org.apache.commons.lang3.StringUtils;

import java.util.List;

/**
 * Utilities for normalizing request paths and matching configured path prefixes.
 * <p>
 * This class provides methods for path normalization, base URL comparison,
 * and prefix matching — commonly used in Feign request routing.
 *
 * <h3>Examples</h3>
 * <pre>{@code
 * // Normalize a raw path
 * String path = PathUtils.normalizePath("/api/users?page=1");
 * // result: "/api/users"
 *
 * // Compare base URLs (ignoring trailing slashes and case)
 * boolean same = PathUtils.isSameBaseUrl("https://api.example.com/", "HTTPS://API.EXAMPLE.COM");
 * // result: true
 *
 * // Find longest matching prefix
 * List<String> prefixes = Arrays.asList("/api/v1", "/api/v2", "/api");
 * int len = PathUtils.bestPrefixMatchLength(prefixes, "/api/v1/users");
 * // result: 7 (length of "/api/v1")
 * }</pre>
 *
 * @author Wenjie Liu
 * @since 1.0.0
 */
public final class PathUtils {

	private PathUtils() {
	}

	/**
	 * Normalizes a path by ensuring it starts with {@code /} and stripping any query string.
	 *
	 * <p><b>Examples:</b></p>
	 * <pre>{@code
	 * PathUtils.normalizePath("/api/users")        // "/api/users"
	 * PathUtils.normalizePath("api/users")         // "/api/users"
	 * PathUtils.normalizePath("/api/users?x=1")    // "/api/users"
	 * PathUtils.normalizePath("api/users?x=1")     // "/api/users"
	 * PathUtils.normalizePath("")                  // ""
	 * PathUtils.normalizePath(null)                // ""
	 * }</pre>
	 *
	 * @param path the raw path
	 * @return the normalized path, or an empty string when {@code path} is blank
	 */
	public static String normalizePath(String path) {
		if (StringUtils.isBlank(path)) {
			return "";
		}
		String withoutQuery = path;
		int queryIndex = path.indexOf('?');
		if (queryIndex >= 0) {
			withoutQuery = path.substring(0, queryIndex);
		}
		return withoutQuery.startsWith("/") ? withoutQuery : "/" + withoutQuery;
	}

	/**
	 * Compares two base URLs for equality, ignoring trailing slashes and case differences.
	 *
	 * <p><b>Examples:</b></p>
	 * <pre>{@code
	 * PathUtils.isSameBaseUrl("https://api.example.com", "https://api.example.com/") // true
	 * PathUtils.isSameBaseUrl("HTTP://HOST.COM", "https://host.com")                  // true
	 * PathUtils.isSameBaseUrl("https://a.com", "https://b.com")                       // false
	 * PathUtils.isSameBaseUrl("", "https://a.com")                                    // false
	 * PathUtils.isSameBaseUrl(null, "https://a.com")                                  // false
	 * }</pre>
	 *
	 * @param left the first URL
	 * @param right the second URL
	 * @return {@code true} when both URLs refer to the same base URL
	 */
	public static boolean isSameBaseUrl(String left, String right) {
		String normalizedLeft = StringUtils.stripEnd(StringUtils.strip(left), "/");
		String normalizedRight = StringUtils.stripEnd(StringUtils.strip(right), "/");
		return StringUtils.isNotBlank(normalizedLeft)
				&& normalizedLeft.equalsIgnoreCase(normalizedRight);
	}

	/**
	 * Returns the length of the longest prefix in {@code prefixes} that matches the start of
	 * {@code requestPath}, or {@code 0} when none matches.
	 *
	 * <p><b>Examples:</b></p>
	 * <pre>{@code
	 * List<String> prefixes = Arrays.asList("/api/v1", "/api/v2", "/api");
	 *
	 * PathUtils.bestPrefixMatchLength(prefixes, "/api/v1/users")   // 7 ("/api/v1")
	 * PathUtils.bestPrefixMatchLength(prefixes, "/api/v2/orders")  // 7 ("/api/v2")
	 * PathUtils.bestPrefixMatchLength(prefixes, "/api/other")      // 4 ("/api")
	 * PathUtils.bestPrefixMatchLength(prefixes, "/other/path")     // 0
	 * PathUtils.bestPrefixMatchLength(prefixes, "")                // 0
	 * PathUtils.bestPrefixMatchLength(null, "/api/v1")             // 0
	 * }</pre>
	 *
	 * @param prefixes the candidate path prefixes
	 * @param requestPath the normalized request path
	 * @return the best matched prefix length
	 */
	public static int bestPrefixMatchLength(Iterable<String> prefixes, String requestPath) {
		if (prefixes == null || StringUtils.isBlank(requestPath)) {
			return 0;
		}
		int best = 0;
		for (String prefix : prefixes) {
			String normalizedPrefix = normalizePath(prefix);
			if (StringUtils.isNotBlank(normalizedPrefix) && requestPath.startsWith(normalizedPrefix)) {
				best = Math.max(best, normalizedPrefix.length());
			}
		}
		return best;
	}

	/**
	 * Returns the length of the longest prefix in {@code prefixes} that matches the start of
	 * {@code requestPath}, or {@code 0} when none matches.
	 * <p>
	 * Overload accepting a {@link List}.
	 *
	 * @param prefixes the candidate path prefixes
	 * @param requestPath the normalized request path
	 * @return the best matched prefix length
	 * @see #bestPrefixMatchLength(Iterable, String)
	 */
	public static int bestPrefixMatchLength(List<String> prefixes, String requestPath) {
		return bestPrefixMatchLength((Iterable<String>) prefixes, requestPath);
	}

}
