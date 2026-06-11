# feign-auth-spring-boot-starter

Spring Boot Starter，为 OpenFeign 提供多服务鉴权自动注入与自定义 Header 扩展。将不同第三方 API 的 Token 获取、缓存、注入逻辑集中到配置文件中，业务 FeignClient 统一使用 `FeignClientConfig` 即可；如需注入动态计算的自定义 Header，实现 `FeignHeaderInjector` 接口并注册为 Spring Bean 即可。

## 环境要求

- Java 8+
- Spring Boot 2.2.x
- 已启用 Spring Cloud OpenFeign

## 安装

```xml
<dependency>
    <groupId>io.github.devoracode</groupId>
    <artifactId>feign-auth-spring-boot-starter</artifactId>
    <version>1.12.0</version>
</dependency>
```

---

## 核心特性

- OAuth2 Token 自动获取、缓存与刷新，支持 `GET` / `POST` 两种请求方式
- API Key 鉴权，支持自定义 Header 名与前缀
- 同一服务可按请求路径前缀匹配不同 OAuth2 Client 凭证
- 同域名下支持多个服务配置，按最长路径前缀优先匹配 + 兜底
- 自定义 Token 请求字段名（如 `appId` / `appSecret` 替代 `client_id` / `client_secret`）
- 从 Token 响应中自定义提取字段路径（如 `data.accessToken`），自动解析过期时间
- Token 过期自动重试（HTTP 421 / 423）
- **自定义动态 Header 注入**：实现 `FeignHeaderInjector` 接口，按服务维度注入任意动态计算的 Header

---

## 快速开始

### 1. 添加配置

```yaml
feign:
  services:
    order:
      base-url: https://api.example.com
      auth:
        type: oauth2
        token-url: https://api.example.com/oauth/token
        clients:
          - id: my-client
            secret: my-secret
```

### 2. 声明 FeignClient

```java
@FeignClient(
    name = "order-client",
    url = "${feign.services.order.base-url}",
    configuration = FeignClientConfig.class
)
public interface OrderFeignClient {

    @GetMapping("/api/orders/{orderId}")
    String getOrder(@PathVariable("orderId") String orderId);
}
```

框架会自动为每次请求获取并缓存 Token，将其注入到指定 Header，无需业务代码干预。

---

## OAuth2 配置详解

```yaml
feign:
  services:
    measure:
      base-url: https://api.service-a.com
      auth:
        type: oauth2
        token-url: https://api.service-a.com/oauth/token
        method: post                        # 默认 post，可选 get
        token-header: x-token               # Token 写入的 Header 名，默认 x-token
        token-prefix: "Bearer "             # 拼接在 Header 值前面，默认空；含空格时需加引号
        token-field: data.accessToken       # Token 提取路径，不填则自动识别
        token-expires-in-seconds: 1800      # 显式指定有效期（秒），不填则从响应中解析
        expire-ahead-seconds: 60            # 过期前提前多少秒刷新，默认 60
        request-fields:                     # 自定义 Token 请求字段名（均可选）
          client-id: clientId               # 默认 client_id
          client-secret: clientSecret       # 默认 client_secret
          grant-type: grantType             # 见下方说明
        clients:
          - id: event-client
            secret: event-secret
            grant-type: client_credentials
            path-prefixes:
              - /api/measure/event
              - /api/measure/event2

          - id: default-client              # 未配置 path-prefixes = 默认 Client
            secret: default-secret
            grant-type: client_credentials
```

### grant-type 发送规则

| request-fields.client-id / client-secret | client.grant-type | 行为 |
|---|---|---|
| 均未配置 | 任意（可不填，默认 `client_credentials`） | 使用默认字段名 `grant_type` 发送 |
| 至少配置一个 | 已配置 | 使用 `request-fields.grant-type` 字段名发送 |
| 至少配置一个 | 未配置 | 不发送 grant-type 参数 |

### Token 响应解析

**Token 字段**：默认依次尝试 `access_token` → `accessToken` → `token`；可通过 `token-field` 指定点分路径（如 `data.accessToken`）。

**过期时间**：默认从响应 JSON 中逐层查找（最多 3 层） `expires_in` → `expiresIn` → `expire_in` → `expireIn`；可通过 `token-expires-in-seconds` 显式指定；未找到时默认 7200 秒。

---

## API Key 配置

```yaml
feign:
  services:
    console:
      base-url: https://api.service-d.com
      auth:
        type: api-key
        value: sk-xxxxxxxx
        header-name: Authorization          # 默认 Authorization
        token-prefix: "Bearer "             # 默认空
        path-prefixes:                      # 指定生效的路径前缀；不填则作为兜底
          - /api/orders
```

---

## 路由匹配规则

所有匹配均采用**最长前缀优先 + 兜底**策略：

1. 按请求 `base-url`（忽略大小写和末尾斜杠）过滤候选服务
2. 在候选服务中，按最长 `path-prefixes` 命中的服务优先
3. 无路径命中时，使用兜底配置（同 `base-url` 下只能有一个兜底）
4. OAuth2 服务内部：先按 Client 的 `path-prefixes` 匹配，无命中时使用默认 Client（无 `path-prefixes` 的 Client）
5. 路径命中长度相同时，抛出配置冲突异常，需消除歧义

**兜底条件：**

- API Key：`auth.path-prefixes` 为空
- OAuth2：`clients` 中存在未配置 `path-prefixes` 的 Client

---

## 完整示例配置

```yaml
feign:
  services:

    # OAuth2 — 多 Client，按路径匹配
    measure:
      base-url: https://api.service-a.com
      auth:
        type: oauth2
        token-url: https://api.service-a.com/oauth/token
        token-prefix: "Bearer "
        request-fields:
          client-id: clientId
          client-secret: clientSecret
          grant-type: grantType
        clients:
          - id: event-client
            secret: event-secret
            grant-type: credentials
            path-prefixes: [/api/measure/event, /api/measure/event2]
          - id: telemetry-client
            secret: telemetry-secret
            grant-type: credentials
            path-prefixes: [/api/measure/telemetry]
          - id: default-client             # 默认 Client（兜底）
            secret: default-secret
            grant-type: credentials

    # OAuth2 — GET 方式获取 Token，自定义字段名
    ast:
      base-url: https://api.service-b.com
      auth:
        type: oauth2
        token-url: https://api.service-b.com/oauth/token
        method: get
        request-fields:
          client-id: appId
          client-secret: appSecret
        clients:
          - id: my-app-id
            secret: my-app-secret

    # OAuth2 — 自定义 Token 字段提取路径与显式过期时间
    psr:
      base-url: https://api.service-p.com
      auth:
        type: oauth2
        token-url: https://api.service-p.com/oauth/accessToken
        token-field: data.accessToken
        token-expires-in-seconds: 3600
        clients:
          - id: client-order
            secret: secret-order
            path-prefixes: [/api/orders]
          - id: client-default
            secret: secret-default

    # API Key — 同域名按路径匹配 + 兜底
    console-orders:
      base-url: https://api.service-d.com
      auth:
        type: api-key
        header-name: Authorization
        value: sk-orders-key
        path-prefixes: [/api/orders]

    console-default:
      base-url: https://api.service-d.com
      auth:
        type: api-key
        header-name: Authorization
        value: sk-default-key             # 兜底：命中 api.service-d.com 的其他所有路径
```

---

## 自定义动态 Header 注入

当需要在认证 Header 之外额外注入动态计算的 Header（如请求签名、时间戳、Trace ID 等），实现 `FeignHeaderInjector` 接口并注册为 Spring Bean 即可，无需修改任何配置文件。

### 接口定义

```java
public interface FeignHeaderInjector {

    /**
     * 判断本 Injector 是否适用于指定服务。
     *
     * @param serviceName 服务名（feign.services 下的 key）
     * @param service     服务配置
     * @return true 时框架会调用 inject()
     */
    boolean supports(String serviceName, FeignAuthProperties.Service service);

    /**
     * 向请求中注入 Header（同时应用于业务请求和 OAuth2 Token 请求）。
     * 通过 {@code header.accept(name, value)} 添加 Header，
     * 无需关心底层是 RequestTemplate 还是 HttpHeaders。
     *
     * @param serviceName 服务名
     * @param requestPath 规范化后的请求路径
     * @param header      Header 消费者，调用 {@code header.accept(key, value)} 即可
     */
    void inject(String serviceName, String requestPath, BiConsumer<String, String> header);
}
```

> 同一个 `inject` 方法会被同时用于 **Feign 业务请求** 和 **OAuth2 Token 请求**，
> 框架内部自动适配 `RequestTemplate` 和 `HttpHeaders`，实现者无需感知差异。

### 示例一：为指定服务注入时间戳与 HMAC 签名

```java
@Component
public class OrderSignatureInjector implements FeignHeaderInjector {

    private final HmacSigner signer;

    public OrderSignatureInjector(HmacSigner signer) {
        this.signer = signer;
    }

    @Override
    public boolean supports(String serviceName, FeignAuthProperties.Service service) {
        return "order".equals(serviceName);
    }

    @Override
    public void inject(String serviceName, String requestPath, BiConsumer<String, String> header) {
        long ts = System.currentTimeMillis();
        String sign = signer.sign(requestPath, ts);
        header.accept("X-Timestamp", String.valueOf(ts));
        header.accept("X-Signature", sign);
    }
}
```

### 示例二：为所有服务透传分布式 Trace ID

```java
@Component
public class TracingHeaderInjector implements FeignHeaderInjector {

    @Override
    public boolean supports(String serviceName, FeignAuthProperties.Service service) {
        return true; // 适用所有服务
    }

    @Override
    public void inject(String serviceName, String requestPath, BiConsumer<String, String> header) {
        String traceId = MDC.get("traceId");
        if (StringUtils.hasText(traceId)) {
            header.accept("X-Trace-Id", traceId);
        }
    }
}
```

### 示例三：仅对特定 base-url 注入租户 Header

```java
@Component
public class TenantHeaderInjector implements FeignHeaderInjector {

    private final TenantContext tenantContext;

    public TenantHeaderInjector(TenantContext tenantContext) {
        this.tenantContext = tenantContext;
    }

    @Override
    public boolean supports(String serviceName, FeignAuthProperties.Service service) {
        return "https://api.service-a.com".equalsIgnoreCase(service.getBaseUrl());
    }

    @Override
    public void inject(String serviceName, String requestPath, BiConsumer<String, String> header) {
        header.accept("X-Tenant-Id", tenantContext.currentTenantId());
    }
}
```

### 多个 Injector 的执行顺序

框架按 Spring Bean 的自然顺序依次调用所有 `supports()` 返回 `true` 的 Injector。如需控制顺序，在实现类上加 `@Order` 注解：

```java
@Component
@Order(1)
public class TracingHeaderInjector implements FeignHeaderInjector { ... }

@Component
@Order(2)
public class SignatureHeaderInjector implements FeignHeaderInjector { ... }
```

---

## 最佳实践

同一个 `base-url` 下只保留一个兜底配置，避免匹配歧义。

当 Token 响应不含标准过期时间字段（或字段名非标准）时，配置 `token-expires-in-seconds` 以避免缓存失效问题。

`FeignHeaderInjector` 中应避免抛出受检异常；如果注入失败，框架会记录错误日志并将异常向上抛出，导致请求终止，请根据业务需要决定是否需要在 Injector 内部捕获并降级处理。

不要在 `FeignHeaderInjector.inject()` 中重复设置认证相关 Header（如 `Authorization` / `x-token`），框架已由 `FeignAuthRequestInterceptor` 统一处理，重复设置会覆盖认证值。

---

## License

Apache License 2.0