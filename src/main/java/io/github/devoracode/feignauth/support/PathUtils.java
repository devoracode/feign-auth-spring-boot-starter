package io.github.devoracode.feignauth.support;

import org.apache.commons.lang3.StringUtils;

import java.net.URI;
import java.util.List;
import java.util.Locale;

/**
 * 用于归一化请求路径并匹配已配置路径前缀的工具类。
 * <p>
 * 本类提供路径归一化、基础 URL 比较与前缀匹配方法，常用于 Feign 请求路由。
 *
 * <h3>示例</h3>
 * <pre>{@code
 * // 归一化原始路径
 * String path = PathUtils.normalizePath("/api/users?page=1");
 * // 结果："/api/users"
 *
 * // 比较基础 URL（忽略末尾斜杠与大小写）
 * boolean same = PathUtils.isSameBaseUrl("https://api.example.com/", "HTTPS://API.EXAMPLE.COM");
 * // 结果：true
 *
 * // 查找最长匹配前缀
 * List<String> prefixes = Arrays.asList("/api/v1", "/api/v2", "/api");
 * int len = PathUtils.bestPrefixMatchLength(prefixes, "/api/v1/users");
 * // 结果：7（"/api/v1" 的长度）
 * }</pre>
 *
 * @author Wenjie Liu
 * @since 1.0.0
 */
public final class PathUtils {

	private PathUtils() {
	}

	/**
	 * 归一化路径：确保以 {@code /} 开头，并去除查询字符串。
	 *
	 * <p><b>示例：</b></p>
	 * <pre>{@code
	 * PathUtils.normalizePath("/api/users")        // "/api/users"
	 * PathUtils.normalizePath("api/users")         // "/api/users"
	 * PathUtils.normalizePath("/api/users?x=1")    // "/api/users"
	 * PathUtils.normalizePath("api/users?x=1")     // "/api/users"
	 * PathUtils.normalizePath("")                  // ""
	 * PathUtils.normalizePath(null)                // ""
	 * }</pre>
	 *
	 * @param path 原始路径
	 * @return 归一化后的路径；{@code path} 为空白时返回空字符串
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
	 * 归一化用于匹配的请求路径：在 {@link #normalizePath(String)} 的基础上折叠重复的 {@code /}，
	 * 并去掉末尾斜杠（根路径 {@code /} 例外保留）。
	 *
	 * <p><b>为什么需要它：</b>拦截器侧用「Feign target 前缀 + 方法路径」拼出请求路径，错误解码器侧
	 * 用最终请求 URL 反推路径，两者受 Feign 自身拼接规则（只去掉一个末尾斜杠、不折叠 {@code //}）
	 * 影响会产生差异。两侧必须收敛到同一个字符串，否则 4xx 时匹配不到同一服务，既不驱逐缓存也不重放。
	 *
	 * <p><b>示例：</b></p>
	 * <pre>{@code
	 * PathUtils.normalizeRequestPath("/api//measure/1")  // "/api/measure/1"
	 * PathUtils.normalizeRequestPath("/api/measure/")     // "/api/measure"
	 * PathUtils.normalizeRequestPath("api/measure?x=1")   // "/api/measure"
	 * PathUtils.normalizeRequestPath("/")                 // "/"
	 * PathUtils.normalizeRequestPath("//")                // "/"
	 * PathUtils.normalizeRequestPath("")                  // ""
	 * PathUtils.normalizeRequestPath(null)                // ""
	 * }</pre>
	 *
	 * @param path 原始请求路径
	 * @return 归一化后的路径；{@code path} 为空白时返回空字符串
	 * @since 1.19.0
	 */
	public static String normalizeRequestPath(String path) {
		String normalized = normalizePath(path);
		if (normalized.isEmpty()) {
			return normalized;
		}
		int fragmentIndex = normalized.indexOf('#');
		if (fragmentIndex >= 0) {
			normalized = normalized.substring(0, fragmentIndex);
		}
		StringBuilder collapsed = new StringBuilder(normalized.length());
		boolean previousWasSlash = false;
		for (int i = 0; i < normalized.length(); i++) {
			char current = normalized.charAt(i);
			if (current == '/') {
				if (previousWasSlash) {
					continue;
				}
				previousWasSlash = true;
			}
			else {
				previousWasSlash = false;
			}
			collapsed.append(current);
		}
		if (collapsed.length() > 1 && collapsed.charAt(collapsed.length() - 1) == '/') {
			collapsed.setLength(collapsed.length() - 1);
		}
		return collapsed.toString();
	}

	/**
	 * 比较两个基础 URL 是否相等，忽略末尾斜杠与大小写差异。
	 *
	 * <p><b>示例：</b></p>
	 * <pre>{@code
	 * PathUtils.isSameBaseUrl("https://api.example.com", "https://api.example.com/") // true
	 * PathUtils.isSameBaseUrl("HTTP://HOST.COM", "https://host.com")                  // true
	 * PathUtils.isSameBaseUrl("https://a.com", "https://b.com")                       // false
	 * PathUtils.isSameBaseUrl("", "https://a.com")                                    // false
	 * PathUtils.isSameBaseUrl(null, "https://a.com")                                  // false
	 * }</pre>
	 *
	 * @param left 第一个 URL
	 * @param right 第二个 URL
	 * @return 当两个 URL 指向同一基础 URL 时返回 {@code true}
	 */
	public static boolean isSameBaseUrl(String left, String right) {
		String normalizedLeft = StringUtils.stripEnd(StringUtils.strip(left), "/");
		String normalizedRight = StringUtils.stripEnd(StringUtils.strip(right), "/");
		return StringUtils.isNotBlank(normalizedLeft)
				&& normalizedLeft.equalsIgnoreCase(normalizedRight);
	}

	/**
	 * 将 URL 归一化为 {@code scheme://host:port} 形式的 authority 键：
	 * scheme 与 host 转小写，省略端口时补全协议默认端口（http→80，https→443），
	 * 并丢弃路径部分。
	 *
	 * <p><b>示例：</b></p>
	 * <pre>{@code
	 * PathUtils.normalizeBaseUrlAuthority("https://API.example.com")      // "https://api.example.com:443"
	 * PathUtils.normalizeBaseUrlAuthority("https://api.example.com/v1")   // "https://api.example.com:443"
	 * PathUtils.normalizeBaseUrlAuthority("http://localhost:8080/x")      // "http://localhost:8080"
	 * PathUtils.normalizeBaseUrlAuthority("my-service")                   // null（无法解析）
	 * }</pre>
	 *
	 * @param url 待归一化的 URL
	 * @return 归一化后的 authority 键；{@code url} 空白或无法解析时返回 {@code null}
	 */
	public static String normalizeBaseUrlAuthority(String url) {
		if (StringUtils.isBlank(url)) {
			return null;
		}
		try {
			URI uri = URI.create(url.trim());
			String scheme = uri.getScheme();
			String host = uri.getHost();
			if (StringUtils.isBlank(scheme) || StringUtils.isBlank(host)) {
				return null;
			}
			int port = uri.getPort();
			if (port < 0) {
				port = defaultPort(scheme);
			}
			String authority = scheme.toLowerCase(Locale.ROOT) + "://" + host.toLowerCase(Locale.ROOT);
			return port > 0 ? authority + ":" + port : authority;
		} catch (IllegalArgumentException ex) {
			return null;
		}
	}

	/**
	 * 提取 URL 中的路径部分并归一化：确保以 {@code /} 开头、去掉末尾斜杠。
	 *
	 * <p><b>示例：</b></p>
	 * <pre>{@code
	 * PathUtils.baseUrlPath("https://api.example.com/v1/")  // "/v1"
	 * PathUtils.baseUrlPath("https://api.example.com")      // ""
	 * PathUtils.baseUrlPath(null)                           // ""
	 * }</pre>
	 *
	 * @param url 待提取的 URL
	 * @return 归一化后的路径；无路径、{@code url} 空白或无法解析时返回空字符串
	 */
	public static String baseUrlPath(String url) {
		if (StringUtils.isBlank(url)) {
			return "";
		}
		try {
			URI uri = URI.create(url.trim());
			return StringUtils.stripEnd(normalizePath(StringUtils.defaultString(uri.getRawPath())), "/");
		} catch (IllegalArgumentException ex) {
			return "";
		}
	}

	private static int defaultPort(String scheme) {
		if ("http".equalsIgnoreCase(scheme)) {
			return 80;
		}
		if ("https".equalsIgnoreCase(scheme)) {
			return 443;
		}
		return -1;
	}

	/**
	 * 返回 {@code prefixes} 中能在路径段边界上匹配 {@code requestPath} 开头的
	 * 最长前缀的长度；若均不匹配则返回 {@code 0}。
	 *
	 * <p>只有当请求路径与前缀完全相等或以 {@code /} 延续时才视为匹配，
	 * 因此 {@code /api/measure} 不会匹配 {@code /api/measurement/1}。
	 *
	 * <p><b>示例：</b></p>
	 * <pre>{@code
	 * List<String> prefixes = Arrays.asList("/api/v1", "/api/v2", "/api");
	 *
	 * PathUtils.bestPrefixMatchLength(prefixes, "/api/v1/users")   // 7 ("/api/v1")
	 * PathUtils.bestPrefixMatchLength(prefixes, "/api/v2/orders")  // 7 ("/api/v2")
	 * PathUtils.bestPrefixMatchLength(prefixes, "/api/other")      // 4 ("/api")
	 * PathUtils.bestPrefixMatchLength(prefixes, "/apix/other")     // 0（非段边界）
	 * PathUtils.bestPrefixMatchLength(prefixes, "/other/path")     // 0
	 * PathUtils.bestPrefixMatchLength(prefixes, "")                // 0
	 * PathUtils.bestPrefixMatchLength(null, "/api/v1")             // 0
	 * }</pre>
	 *
	 * @param prefixes 候选路径前缀
	 * @param requestPath 归一化后的请求路径
	 * @return 最佳匹配前缀的长度
	 */
	public static int bestPrefixMatchLength(Iterable<String> prefixes, String requestPath) {
		if (prefixes == null || StringUtils.isBlank(requestPath)) {
			return 0;
		}
		int best = 0;
		for (String prefix : prefixes) {
			String normalizedPrefix = StringUtils.stripEnd(normalizePath(prefix), "/");
			if (StringUtils.isBlank(normalizedPrefix)) {
				// 前缀 "/" 可匹配根路径下的全部请求；保留其长度为 1 的旧语义
				if (requestPath.startsWith("/")) {
					best = Math.max(best, 1);
				}
				continue;
			}
			boolean matches = requestPath.length() == normalizedPrefix.length()
					? requestPath.equals(normalizedPrefix)
					: requestPath.startsWith(normalizedPrefix)
							&& requestPath.charAt(normalizedPrefix.length()) == '/';
			if (matches) {
				best = Math.max(best, normalizedPrefix.length());
			}
		}
		return best;
	}

	/**
	 * 返回 {@code prefixes} 中匹配 {@code requestPath} 开头的最长前缀的长度；
	 * 若均不匹配则返回 {@code 0}。
	 * <p>
	 * 接受 {@link List} 参数的重载。
	 *
	 * @param prefixes 候选路径前缀
	 * @param requestPath 归一化后的请求路径
	 * @return 最佳匹配前缀的长度
	 * @see #bestPrefixMatchLength(Iterable, String)
	 */
	public static int bestPrefixMatchLength(List<String> prefixes, String requestPath) {
		return bestPrefixMatchLength((Iterable<String>) prefixes, requestPath);
	}

}
