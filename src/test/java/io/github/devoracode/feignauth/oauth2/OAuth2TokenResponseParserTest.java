package io.github.devoracode.feignauth.oauth2;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.devoracode.feignauth.exception.FeignAuthTokenException;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

class OAuth2TokenResponseParserTest {

	private final OAuth2TokenResponseParser parser = new OAuth2TokenResponseParser(new ObjectMapper());

	@Test
	void usesConfiguredTokenExpiresInSecondsWhenSet() {
		String body = "{\"access_token\":\"token-value\",\"expires_in\":3600}";
		OAuth2AccessToken accessToken = this.parser.parse(200, body, 60L, null, 1800L);

		long ttlMillis = accessToken.getExpireAt() - System.currentTimeMillis();
		assertThat(ttlMillis).isBetween(1730000L, 1800000L);
	}

	@Test
	void findsExpiresInAtRootWhenNotConfigured() {
		String body = "{\"access_token\":\"token-value\",\"expiresIn\":900}";
		OAuth2AccessToken accessToken = this.parser.parse(200, body, 60L, null, null);

		long ttlMillis = accessToken.getExpireAt() - System.currentTimeMillis();
		assertThat(ttlMillis).isBetween(840000L, 900000L);
	}

	@Test
	void findsExpiresInInNestedObjectWithinThreeLevels() {
		String body = "{\"result\":{\"payload\":{\"expire_in\":1200}},\"access_token\":\"token-value\"}";
		OAuth2AccessToken accessToken = this.parser.parse(200, body, 0L, null, null);

		long ttlMillis = accessToken.getExpireAt() - System.currentTimeMillis();
		assertThat(ttlMillis).isBetween(1190000L, 1200000L);
	}

	@Test
	void prefersShallowerExpiresInFieldWhenSearchingLayerByLayer() {
		String body = "{\"data\":{\"expires_in\":300},\"result\":{\"expires_in\":600},\"access_token\":\"token-value\"}";
		OAuth2AccessToken accessToken = this.parser.parse(200, body, 0L, null, null);

		long ttlMillis = accessToken.getExpireAt() - System.currentTimeMillis();
		assertThat(ttlMillis).isBetween(290000L, 300000L);
	}

	@Test
	void doesNotSearchBeyondThreeLevels() {
		String body = "{\"l1\":{\"l2\":{\"l3\":{\"l4\":{\"expires_in\":500}}}},\"access_token\":\"token-value\"}";
		OAuth2AccessToken accessToken = this.parser.parse(200, body, 0L, null, null);

		long ttlMillis = accessToken.getExpireAt() - System.currentTimeMillis();
		assertThat(ttlMillis).isBetween(7190000L, 7200000L);
	}

	@Test
	void clampsTtlToOneSecondWhenExpireAheadSecondsReachesExpiresIn() {
		String body = "{\"access_token\":\"token-value\",\"expires_in\":10}";
		OAuth2AccessToken accessToken = this.parser.parse(200, body, 60L, null, null);

		assertThat(accessToken.getTtl()).isEqualTo(1L);
		long ttlMillis = accessToken.getExpireAt() - System.currentTimeMillis();
		assertThat(ttlMillis).isBetween(0L, 1000L);
	}

	@Test
	void clampsTtlToOneSecondWhenExpireAheadSecondsEqualsExpiresIn() {
		String body = "{\"access_token\":\"token-value\",\"expires_in\":60}";
		OAuth2AccessToken accessToken = this.parser.parse(200, body, 60L, null, null);

		assertThat(accessToken.getTtl()).isEqualTo(1L);
	}

	@Test
	void handlesMinimumExpiresIn() {
		String body = "{\"access_token\":\"token-value\",\"expires_in\":1}";
		OAuth2AccessToken accessToken = this.parser.parse(200, body, 60L, null, null);

		assertThat(accessToken.getTtl()).isEqualTo(1L);
		long ttlMillis = accessToken.getExpireAt() - System.currentTimeMillis();
		assertThat(ttlMillis).isBetween(0L, 1000L);
	}

	@Test
	void clampsHugeExpiresInToOneDayCap() {
		String body = "{\"access_token\":\"token-value\",\"expires_in\":9223372036854775807}";
		OAuth2AccessToken accessToken = this.parser.parse(200, body, 60L, null, null);

		assertThat(accessToken.getTtl()).isEqualTo(86400L);
		assertThat(accessToken.getExpireAt()).isGreaterThan(System.currentTimeMillis());
	}

	@Test
	void clampsHugeConfiguredExpiresInSecondsToDailyCap() {
		String body = "{\"access_token\":\"token-value\",\"expires_in\":3600}";
		OAuth2AccessToken accessToken = this.parser.parse(200, body, 60L, null, Long.MAX_VALUE);

		assertThat(accessToken.getTtl()).isEqualTo(86400L);
		assertThat(accessToken.getExpireAt()).isGreaterThan(System.currentTimeMillis());
	}

	@Test
	void clampsMultiDayExpiresInToDailyCap() {
		String body = "{\"access_token\":\"token-value\",\"expires_in\":172800}";
		OAuth2AccessToken accessToken = this.parser.parse(200, body, 60L, null, null);

		assertThat(accessToken.getTtl()).isEqualTo(86400L);
	}

	@Test
	void keepsExpiresInAtExactlyOneDayUnclamped() {
		String body = "{\"access_token\":\"token-value\",\"expires_in\":86400}";
		OAuth2AccessToken accessToken = this.parser.parse(200, body, 0L, null, null);

		assertThat(accessToken.getTtl()).isEqualTo(86400L);
	}

	@Test
	void tokenFieldResolvesSingleSegmentAtRoot() {
		// 只写一层时点分路径等价于根字段查找，命中的是 token-field 而不是默认字段表
		String body = "{\"token\":\"token-value\"}";
		OAuth2AccessToken accessToken = this.parser.parse(200, body, 60L, "token", 3600L);

		assertThat(accessToken.getAccessToken()).isEqualTo("token-value");
	}

	@Test
	void tokenFieldResolvesNestedDottedPathWithoutDepthLimit() {
		// 点分路径逐段下钻，没有 expires_in 那样的三层搜索上限
		String body = "{\"a\":{\"b\":{\"c\":{\"d\":{\"token\":\"token-value\"}}}}}";
		OAuth2AccessToken accessToken = this.parser.parse(200, body, 60L, "a.b.c.d.token", 3600L);

		assertThat(accessToken.getAccessToken()).isEqualTo("token-value");
	}

	@Test
	void tokenFieldAcceptsNumericLeafAsText() {
		// 既有口径：叶节点是数字也按 asText() 取用，不视为「找不到」
		String body = "{\"data\":{\"token\":123456}}";
		OAuth2AccessToken accessToken = this.parser.parse(200, body, 60L, "data.token", 3600L);

		assertThat(accessToken.getAccessToken()).isEqualTo("123456");
	}

	@Test
	void tokenFieldIsTrimmedForLookupButReportedVerbatim() {
		// 查找用 trim 后的路径，异常文案保留配置原值，便于定位写错的那条 token-field
		String body = "{\"data\":{\"token\":\"token-value\"}}";
		OAuth2AccessToken accessToken = this.parser.parse(200, body, 60L, "  data.token  ", 3600L);
		assertThat(accessToken.getAccessToken()).isEqualTo("token-value");

		assertThatThrownBy(() -> this.parser.parse(200, "{\"data\":{}}", 60L, "  data.token  ", 3600L))
				.isInstanceOf(FeignAuthTokenException.class)
				.hasMessage("Token field '  data.token  ' not found or blank in response");
	}

	@Test
	void blankTokenFieldUsesDefaultTokenFieldSearch() {
		// 只填空白等于没填，仍走 access_token/accessToken/token 的默认顺序
		String body = "{\"accessToken\":\"token-value\"}";
		OAuth2AccessToken accessToken = this.parser.parse(200, body, 60L, "   ", 3600L);

		assertThat(accessToken.getAccessToken()).isEqualTo("token-value");
	}

	@Test
	void explicitTokenFieldMissNeverFallsBackToDefaultFields() {
		// 配了 token-field 却没命中就报错，退回默认字段会静默拿到另一个令牌
		String body = "{\"access_token\":\"other-token\",\"data\":{\"token\":\"   \"}}";

		assertThatThrownBy(() -> this.parser.parse(200, body, 60L, "data.token", 3600L))
				.isInstanceOf(FeignAuthTokenException.class)
				.hasMessage("Token field 'data.token' not found or blank in response");
	}

	@Test
	void tokenFieldThroughNonObjectSegmentIsRejected() {
		// 中间段是标量或数组都无从下钻；下标写法 data[0] 也只是个找不到的键名
		assertThatThrownBy(() -> this.parser.parse(200, "{\"data\":\"not-an-object\"}", 60L, "data.token", 3600L))
				.isInstanceOf(FeignAuthTokenException.class)
				.hasMessage("Token field 'data.token' not found or blank in response");
		assertThatThrownBy(() -> this.parser.parse(200, "{\"data\":[{\"token\":\"t\"}]}", 60L, "data.token", 3600L))
				.isInstanceOf(FeignAuthTokenException.class)
				.hasMessage("Token field 'data.token' not found or blank in response");
		assertThatThrownBy(() -> this.parser.parse(200, "{\"data\":[{\"token\":\"t\"}]}", 60L, "data[0].token", 3600L))
				.isInstanceOf(FeignAuthTokenException.class)
				.hasMessage("Token field 'data[0].token' not found or blank in response");
	}

	@Test
	void tokenFieldLeafMustBePresentValueNode() {
		// 缺叶与叶是对象都归为同一条文案；「叶是空白串」那一半在上一条用例里已覆盖
		assertThatThrownBy(() -> this.parser.parse(200, "{\"data\":{\"other\":\"t\"}}", 60L, "data.token", 3600L))
				.isInstanceOf(FeignAuthTokenException.class)
				.hasMessage("Token field 'data.token' not found or blank in response");
		assertThatThrownBy(() -> this.parser.parse(200, "{\"data\":{\"token\":{\"t\":\"v\"}}}", 60L, "data.token", 3600L))
				.isInstanceOf(FeignAuthTokenException.class)
				.hasMessage("Token field 'data.token' not found or blank in response");
	}

	@Test
	void trailingDotInTokenFieldResolvesToParentObject() {
		// String.split 会丢掉尾部空段，"data." 实际只按 "data" 解析，取到的是对象而非令牌
		String body = "{\"data\":{\"token\":\"token-value\"}}";

		assertThatThrownBy(() -> this.parser.parse(200, body, 60L, "data.", 3600L))
				.isInstanceOf(FeignAuthTokenException.class)
				.hasMessage("Token field 'data.' not found or blank in response");
	}

	@Test
	void tokenFieldOnArrayRootIsRejected() {
		// 响应体是数组根时点分路径第一步就下不去
		assertThatThrownBy(() -> this.parser.parse(200, "[{\"token\":\"t\"}]", 60L, "token", 3600L))
				.isInstanceOf(FeignAuthTokenException.class)
				.hasMessage("Token field 'token' not found or blank in response");
	}

	@Test
	void defaultTokenFieldFailureKeepsItsOwnMessage() {
		// 未配 token-field 时的失败文案不带字段名，与点分路径那条区分开
		assertThatThrownBy(() -> this.parser.parse(200, "{}", 60L, null, 3600L))
				.isInstanceOf(FeignAuthTokenException.class)
				.hasMessage("Token field not found in response");
	}

	@Test
	void non2xxReportsErrorAndErrorDescriptionFromJsonBody() {
		String body = "{\"error\":\"invalid_client\",\"error_description\":\"client_secret is expired\"}";

		assertThatThrownBy(() -> this.parser.parse(401, body, 60L, null, null))
				.isInstanceOf(FeignAuthTokenException.class)
				.hasMessageContaining("401")
				.hasMessageContaining("invalid_client")
				.hasMessageContaining("client_secret is expired");
	}

	@Test
	void non2xxReportsRawBodyWhenNotJson() {
		String body = "<html>gateway blew up</html>";

		assertThatThrownBy(() -> this.parser.parse(502, body, 60L, null, null))
				.isInstanceOf(FeignAuthTokenException.class)
				.hasMessageContaining("502")
				.hasMessageContaining("gateway blew up");
	}

	@Test
	void non2xxTruncatesOversizedBody() {
		StringBuilder builder = new StringBuilder("{\"error\":\"server_error\",\"error_description\":\"");
		for (int i = 0; i < 4000; i++) {
			builder.append('x');
		}
		builder.append("\"}");

		FeignAuthTokenException thrown = catchThrowableOfType(
				() -> this.parser.parse(500, builder.toString(), 60L, null, null), FeignAuthTokenException.class);

		assertThat(thrown).isNotNull();
		assertThat(thrown.getMessage()).contains("server_error");
		assertThat(thrown.getMessage().length()).isLessThan(2048);
	}

	@Test
	void non2xxWithoutBodyStillReportsStatus() {
		assertThatThrownBy(() -> this.parser.parse(403, null, 60L, null, null))
				.isInstanceOf(FeignAuthTokenException.class)
				.hasMessageContaining("403");
	}

}
