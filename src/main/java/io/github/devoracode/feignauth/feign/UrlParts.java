package io.github.devoracode.feignauth.feign;

import lombok.AccessLevel;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.apache.commons.lang3.StringUtils;

import java.net.URI;

/**
 * Feign 请求 URL 拆出的 {@code scheme://authority} 与原始路径两段。
 *
 * <p>拦截器（正向：{@code feignTarget().url()} + 方法路径）与错误解码器（反向：从 Feign 实际发出的
 * 最终 URL 反推）必须用同一份拆解口径，否则同一个请求在两侧得到不同的路径字符串，4xx 时匹配不到
 * 同一服务，既不驱逐缓存也不重放。这里只统一「怎么拆」，拆解之后各自的归一化仍由调用点决定。
 *
 * <p>解析失败时原样抛出 {@link URI#get(String)} 的运行时异常：两侧的兜底不同——正向退化为原始串
 * 继续匹配，反向放弃驱逐但仍抛可读的错误异常，且 WARN 需要带上原始异常信息。
 *
 * @author Wenjie Liu
 * @since 2.0.0
 */
@Getter
@RequiredArgsConstructor(access = AccessLevel.PRIVATE)
final class UrlParts {

	private final String baseUrl;

	private final String rawPath;

	/**
	 * 拆出 URL 的 authority 与原始路径。
	 * @param url 待拆解的 URL
	 * @return 拆解结果；{@code url} 不含路径段时 {@code rawPath} 为空串
	 * @throws RuntimeException URL 无法解析时原样抛出，由调用方决定兜底
	 */
	static UrlParts parse(String url) {
		URI uri = URI.create(url);
		return new UrlParts(uri.getScheme() + "://" + uri.getAuthority(), StringUtils.defaultString(uri.getRawPath()));
	}

}
