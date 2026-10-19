package io.github.devoracode.feignauth.oauth2;

import org.apache.http.HttpEntity;
import org.apache.http.entity.ContentType;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;

/**
 * 取号响应正文的读取器：按固定上限读取字节，再按响应声明的字符集解码。
 *
 * <p>上限 {@value #MAX_RESPONSE_BODY_BYTES} 字节按实际读到的字节判定，绝不按对方声明的
 * Content-Length 预分配缓冲；读满上限即停并标记截断，未读完的正文留在流上，响应关闭时连接直接丢弃。</p>
 *
 * @author Wenjie Liu
 * @since 2.0.0
 */
final class TokenResponseBodyReader {

	// 取号响应体的读取上限：正常令牌响应远小于此，超出只可能是错误页或伪造的 Content-Length
	static final int MAX_RESPONSE_BODY_BYTES = 64 * 1024;

	private TokenResponseBodyReader() {
	}

	// 读满上限即停：未读完的正文留在流上，响应关闭时连接直接丢弃
	static ResponseBody readBody(InputStream in, Charset charset) throws IOException {
		// 与原先的 EntityUtils.toString 保持同样的容忍：null 内容流按空正文处理
		if (in == null) {
			return new ResponseBody(null, false);
		}
		ByteArrayOutputStream buffer = new ByteArrayOutputStream();
		byte[] chunk = new byte[8192];
		boolean truncated = false;
		int read;
		while ((read = in.read(chunk)) != -1) {
			int room = MAX_RESPONSE_BODY_BYTES - buffer.size();
			if (read > room) {
				if (room > 0) {
					buffer.write(chunk, 0, room);
				}
				truncated = true;
				break;
			}
			buffer.write(chunk, 0, read);
		}
		return new ResponseBody(new String(buffer.toByteArray(), charset), truncated);
	}

	static final class ResponseBody {

		private final String text;

		private final boolean truncated;

		ResponseBody(String text, boolean truncated) {
			this.text = text;
			this.truncated = truncated;
		}

		String getText() {
			return this.text;
		}

		boolean isTruncated() {
			return this.truncated;
		}

	}

	// 响应声明的字符集优先，未声明或无法解析按 UTF-8。必须用 lenient：strict 版对不认识的
	// charset 抛 UnsupportedCharsetException，属 RuntimeException，逃过读取处的 IOException 兜底
	static Charset resolveResponseCharset(HttpEntity entity) {
		ContentType contentType = ContentType.getLenient(entity);
		if (contentType != null && contentType.getCharset() != null) {
			return contentType.getCharset();
		}
		return StandardCharsets.UTF_8;
	}

}
