package io.github.devoracode.feignauth.support;

import org.springframework.util.StringUtils;

import java.util.List;

/**
 * Utilities for normalizing request paths and matching configured path prefixes.
 *
 * @author Wenjie Liu
 * @since 1.0.0
 */
public final class PathUtils {

	private PathUtils() {
	}

	/**
	 * Normalizes a path by ensuring it starts with {@code /} and stripping any query string.
	 * @param path the raw path
	 * @return the normalized path, or an empty string when {@code path} is blank
	 */
	public static String normalizePath(String path) {
		if (!StringUtils.hasText(path)) {
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
	 * @param left the first URL
	 * @param right the second URL
	 * @return {@code true} when both URLs refer to the same base URL
	 */
	public static boolean isSameBaseUrl(String left, String right) {
		String normalizedLeft = trimTrailingSlash(StringUtils.trimWhitespace(left));
		String normalizedRight = trimTrailingSlash(StringUtils.trimWhitespace(right));
		return StringUtils.hasText(normalizedLeft)
				&& normalizedLeft.equalsIgnoreCase(normalizedRight);
	}

	/**
	 * Returns the length of the longest prefix in {@code prefixes} that matches the start of
	 * {@code requestPath}, or {@code 0} when none matches.
	 * @param prefixes the candidate path prefixes
	 * @param requestPath the normalized request path
	 * @return the best matched prefix length
	 */
	public static int bestPrefixMatchLength(Iterable<String> prefixes, String requestPath) {
		if (prefixes == null || !StringUtils.hasText(requestPath)) {
			return 0;
		}
		int best = 0;
		for (String prefix : prefixes) {
			String normalizedPrefix = normalizePath(prefix);
			if (StringUtils.hasText(normalizedPrefix) && requestPath.startsWith(normalizedPrefix)) {
				best = Math.max(best, normalizedPrefix.length());
			}
		}
		return best;
	}

	/**
	 * Returns the length of the longest prefix in {@code prefixes} that matches the start of
	 * {@code requestPath}, or {@code 0} when none matches.
	 * @param prefixes the candidate path prefixes
	 * @param requestPath the normalized request path
	 * @return the best matched prefix length
	 */
	public static int bestPrefixMatchLength(List<String> prefixes, String requestPath) {
		return bestPrefixMatchLength((Iterable<String>) prefixes, requestPath);
	}

	private static String trimTrailingSlash(String value) {
		if (!StringUtils.hasText(value)) {
			return value;
		}
		while (value.endsWith("/")) {
			value = value.substring(0, value.length() - 1);
		}
		return value;
	}

}
