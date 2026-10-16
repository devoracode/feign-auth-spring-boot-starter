package io.github.devoracode.feignauth.feign;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * WARN 日志去重器：首次遇到某个 key 时返回 {@code true}（调用方记一条 WARN），
 * 之后同一 key 返回 {@code false}（调用方降级为 DEBUG）。
 *
 * <p>用于每个请求都会命中的热路径——未匹配服务的请求、无法解析的请求 URL 都是同一个原因
 * 反复出现，逐条 WARN 会把业务日志刷满。记下的 key 数量有上限
 * {@link #MAX_TRACKED_KEYS}，超限后不再记录新 key，避免长时间运行的进程无界增长。</p>
 *
 * <p>线程安全：内部使用并发 Set，多个 Feign 客户端并发调用时同一 key 最多放行一条 WARN。</p>
 *
 * @author Wenjie Liu
 * @since 1.19.0
 */
final class WarnThrottle {

	static final int MAX_TRACKED_KEYS = 32;

	private final Set<String> warnedKeys = ConcurrentHashMap.newKeySet();

	/**
	 * 判断给定 key 是否应当记一条 WARN。
	 *
	 * @param key 去重键，通常是 baseUrl 或请求 URL
	 * @return 首次遇到该 key 时返回 {@code true}；已提醒过、key 为空或已达记录上限时返回 {@code false}
	 */
	boolean shouldWarn(String key) {
		if (key == null || this.warnedKeys.size() >= MAX_TRACKED_KEYS) {
			return false;
		}
		return this.warnedKeys.add(key);
	}

}
