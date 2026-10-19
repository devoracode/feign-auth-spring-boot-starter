package io.github.devoracode.feignauth.header;

import feign.RequestTemplate;
import io.github.devoracode.feignauth.autoconfigure.FeignAuthProperties;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.util.Assert;

import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * 三阶段 header 注入管线的中心协调器。
 *
 * <p>Feign 业务请求路径与 OAuth2 令牌请求路径共用此类，
 * 以确保行为一致并提供单一扩展点。
 *
 * <h3>管线阶段</h3>
 * <ol>
 *   <li><strong>静态阶段</strong> — 首先写入服务配置中声明的 header
 *       （{@code request-headers} 或 {@code token-request-headers}）。
 *       空白名称与 {@code null} 值会被静默跳过。</li>
 *   <li><strong>动态阶段（{@link HeaderCustomizer}）</strong> — 按列表顺序依次调用已注册且
 *       {@link HeaderCustomizer#supports} 返回 {@code true} 的 {@link HeaderCustomizer} Bean。
 *       当 header 名相同时，它们可以覆盖静态配置的值。</li>
 * </ol>
 *
 * <p>对于业务请求，最终的 {@link HttpHeaders} 由调用方合并进 Feign 的
 * {@link RequestTemplate}；对于令牌请求，{@link HttpHeaders} 对象会被直接传入 HTTP entity。
 *
 * @author Wenjie Liu
 * @since 1.14.0
 */
public class HeaderManager {

	private static final Log logger = LogFactory.getLog(HeaderManager.class);

	private final List<HeaderCustomizer> customizers;

	public HeaderManager() {
		this(Collections.emptyList());
	}

	public HeaderManager(List<HeaderCustomizer> customizers) {
		this.customizers = (customizers != null) ? customizers : Collections.emptyList();
	}

	// ── 业务请求 ───────────────────────────────────────────────────────

	/**
	 * 构建 Feign 业务请求的附加 header，并将其写入提供的 {@link RequestTemplate}。
	 *
	 * <p>阶段 1 读取 {@code FeignAuthProperties.Auth.getRequestHeaders()} 中的映射。
	 * 阶段 2 对每个匹配的定制器调用
	 * {@link HeaderCustomizer#customize(RequestContext, HttpHeaders)}。
	 *
	 * @param serviceName 逻辑服务名
	 * @param service 服务配置
	 * @param requestPath 归一化后的请求路径
	 * @param template 用于写入 header 的 Feign 请求模板
	 */
	public void applyRequestHeaders(String serviceName, FeignAuthProperties.Service service,
			String requestPath, RequestTemplate template) {
		Assert.notNull(template, "template must not be null");

		Map<String, String> staticHeaders = service.getAuth().getRequestHeaders();
		// 本方法每个业务请求都会执行一次，而「既没配 request-headers 也没注册定制器」就是默认配置：
		// 这种情况下没有任何 header 要写，直接返回，不分配 HttpHeaders、也不做空遍历
		if (this.customizers.isEmpty() && (staticHeaders == null || staticHeaders.isEmpty())) {
			return;
		}

		HttpHeaders headers = new HttpHeaders();

		// 阶段 1：来自 request-headers 配置的静态 header
		applyStaticHeaders(staticHeaders, headers);

		// 阶段 2：来自 HeaderCustomizer Bean 的动态 header
		if (!this.customizers.isEmpty()) {
			RequestContext context = new RequestContext(serviceName, service, requestPath, template);
			for (HeaderCustomizer customizer : this.customizers) {
				invokeCustomizer(customizer, serviceName, service,
						() -> customizer.customize(context, headers));
			}
		}

		// 合并进 RequestTemplate；header() 是追加语义，因此先清除同名 header（Feign 重试时会再次执行本逻辑）。
		headers.forEach((name, values) -> {
			if (!values.isEmpty()) {
				template.header(name);
				template.header(name, values);
			}
		});
	}

	// ── 令牌请求 ────────────────────────────────────────────────────────

	/**
	 * 构建 OAuth2 令牌 HTTP 请求的 {@link HttpHeaders}。
	 *
	 * <p>阶段 1 读取 {@code FeignAuthProperties.Auth.getTokenRequestHeaders()} 中的映射。
	 * 阶段 2 对每个匹配的定制器调用
	 * {@link HeaderCustomizer#customize(TokenRequestContext, HttpHeaders)}。
	 *
	 * @param serviceName 逻辑服务名
	 * @param service 服务配置
	 * @param client 正在为其申请令牌的 OAuth2 Client
	 * @param parameters 解析后的令牌请求参数（按字段名映射）
	 * @return 装配完成的 {@link HttpHeaders}（不包含 Content-Type；
	 *         调用方需要自行设置）
	 */
	public HttpHeaders buildTokenHeaders(String serviceName, FeignAuthProperties.Service service,
			FeignAuthProperties.Client client, Map<String, String> parameters) {
		HttpHeaders headers = new HttpHeaders();

		// 阶段 1：来自 token-request-headers 配置的静态 header
		applyStaticHeaders(service.getAuth().getTokenRequestHeaders(), headers);

		// 阶段 2：来自 HeaderCustomizer Bean 的动态 header
		if (!this.customizers.isEmpty()) {
			TokenRequestContext context = new TokenRequestContext(serviceName, service, client, parameters);
			for (HeaderCustomizer customizer : this.customizers) {
				invokeCustomizer(customizer, serviceName, service,
						() -> customizer.customize(context, headers));
			}
		}

		return headers;
	}

	// ── 内部辅助方法 ─────────────────────────────────────────────────────

	private static void applyStaticHeaders(Map<String, String> staticHeaders, HttpHeaders target) {
		if (staticHeaders == null || staticHeaders.isEmpty()) {
			return;
		}
		staticHeaders.forEach((name, value) -> {
			if (StringUtils.isNotBlank(name) && value != null) {
				target.set(name, value);
			}
		});
	}

	private void invokeCustomizer(HeaderCustomizer customizer, String serviceName,
			FeignAuthProperties.Service service, Runnable action) {
		try {
			if (customizer.supports(serviceName, service)) {
				action.run();
			}
		}
		catch (Exception ex) {
			if (logger.isErrorEnabled()) {
				logger.error("FeignAuth: HeaderCustomizer " + customizer.getClass().getSimpleName()
						+ " failed for service='" + serviceName + "': " + ex.getMessage(), ex);
			}
			throw ex;
		}
	}

}
