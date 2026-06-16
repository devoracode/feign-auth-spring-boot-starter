# feign-auth-spring-boot-starter

Spring Boot Starter，为 OpenFeign 提供多服务鉴权自动注入与自定义 Header 扩展。将不同第三方 API 的 Token 获取、缓存、注入逻辑集中到配置文件中，业务 FeignClient 统一使用 `FeignClientConfig` 即可；如需注入动态计算的自定义 Header，实现 `HeaderCustomizer` 接口并注册为 Spring Bean 即可。

## 环境要求

- Java 8+
- Spring Boot 2.2.x
- 已启用 Spring Cloud OpenFeign

## 安装

```xml
<dependency>
    <groupId>io.github.devoracode</groupId>
    <artifactId>feign-auth-spring-boot-starter</artifactId>
    <version>1.17.2</version>
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
- Token 过期自动重试（默认 HTTP 401 / 421 / 423，可通过配置自定义状态码）
- 支持从响应体中解析过期状态码（需配置 `response-status-field`，适用于不规范的第三方 API 返回 HTTP 200/400 但在响应体中包含过期状态）
- **静态固定 Header**：在 YAML 中配置 `request-headers`（业务请求）与 `token-request-headers`（Token 请求）
- **动态 Header 注入**：实现 `HeaderCustomizer` 接口，按服务维度注入任意动态计算的 Header
- **可插拔缓存机制**：支持本地缓存（ConcurrentHashMap）和 Redis 缓存，自动清理过期 Token
- **可插拔锁机制**：支持本地锁（synchronized）和 Redis 分布式锁，防止并发请求重复获取 Token

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
        expired-token-statuses:            # 触发 Token 过期重试的 HTTP 状态码，默认 [401, 421, 423]
          - 401
          - 421
          - 423
        response-status-field: code           # 响应体中状态码字段路径，支持点分隔如 data.code，不填则仅使用 HTTP 状态码判断
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

## 自定义 Header 注入

支持两种方式：YAML 配置固定 Header，或实现 `HeaderCustomizer` 接口动态注入。

### 静态固定 Header

直接在 `auth` 下配置 `request-headers`（业务请求）和 `token-request-headers`（Token 请求）：

```yaml
auth:
  type: oauth2
  request-headers:
    X-App-Id: my-app
    X-Version: v2
  token-request-headers:
    X-App-Id: my-app
```

### 动态 Header（HeaderCustomizer 接口）

实现 `HeaderCustomizer` 并注册为 Spring Bean，可获取请求上下文做签名、Trace 等动态注入。

### 接口定义

```java
public interface HeaderCustomizer {

    boolean supports(String serviceName, FeignAuthProperties.Service service);

    /** 业务请求：ctx 提供 requestPath、queryParams、method、body、template */
    default void customize(RequestContext ctx, HttpHeaders headers) {}

    /** Token 请求：ctx 提供 client、parameters */
    default void customize(TokenRequestContext ctx, HttpHeaders headers) {}
}
```

### 示例一：为指定服务注入 HMAC 签名

```java
@Component
public class OrderSignatureCustomizer implements HeaderCustomizer {

    private final HmacSigner signer;

    @Override
    public boolean supports(String name, FeignAuthProperties.Service svc) {
        return "order".equals(name);
    }

    @Override
    public void customize(RequestContext ctx, HttpHeaders headers) {
        String sign = signer.sign(ctx.getRequestPath(), ctx.getQueryParams(), ctx.getBody());
        headers.set("X-Timestamp", String.valueOf(System.currentTimeMillis()));
        headers.set("X-Signature", sign);
    }
}
```

### 示例二：为所有服务透传 Trace ID

```java
@Component
public class TracingCustomizer implements HeaderCustomizer {

    @Override
    public boolean supports(String name, FeignAuthProperties.Service svc) { return true; }

    @Override
    public void customize(RequestContext ctx, HttpHeaders headers) {
        String traceId = MDC.get("traceId");
        if (StringUtils.hasText(traceId)) headers.set("X-Trace-Id", traceId);
    }
}
```

### 示例三：Token 请求注入额外 Header

```java
@Component
public class TokenHeaderCustomizer implements HeaderCustomizer {

    @Override
    public boolean supports(String name, FeignAuthProperties.Service svc) {
        return "measure".equals(name);
    }

    @Override
    public void customize(TokenRequestContext ctx, HttpHeaders headers) {
        // 可用 ctx.getClient().getId() 获取原始 client id 做签名
        headers.set("X-App-Id", ctx.getClient().getId());
    }
}
```

### 多个 Customizer 的执行顺序

按 Spring Bean 自然顺序调用，用 `@Order` 控制：

```java
@Component @Order(1)
public class TracingCustomizer implements HeaderCustomizer { ... }

@Component @Order(2)
public class SignatureCustomizer implements HeaderCustomizer { ... }
```

> 静态 `request-headers` / `token-request-headers` 先写入，动态 HeaderCustomizer 后写入，同名 header 会被覆盖。

---

## 缓存与锁配置

### 缓存机制

支持两种缓存实现，通过配置切换：

```yaml
feign:
  auth:
    cache:
      provider: local  # local | redis，默认 local
      redis:
        key-prefix: feign-auth:  # Redis key 前缀，默认 feign-auth:token:
```

**Redis Key 格式说明**：

| 类型 | Key 格式 | 示例 |
|------|----------|------|
| Token 缓存 | `{key-prefix}token:{serviceName}:{clientId}` | `feign-auth:token:order-service:my-client` |
| 分布式锁 | `{key-prefix}lock:{serviceName}:{clientId}` | `feign-auth:lock:order-service:my-client` |

| Provider | 实现方式 | 适用场景 |
|----------|----------|----------|
| `local` | ConcurrentHashMap + 懒清理 | 单实例部署 |
| `redis` | Redis + TTL | 多实例部署，共享 Token |

**本地缓存特点**：
- 使用 `ConcurrentHashMap` 存储，线程安全
- 访问过期 Token 时自动清理（懒清理策略）
- 无需额外依赖

**Redis 缓存特点**：
- 利用 Redis TTL 自动过期机制
- 多实例共享 Token，避免重复获取
- 需要引入 Spring Data Redis 依赖

### 锁机制

锁机制与缓存 provider 联动，确保同一 Token 不会被并发请求重复获取：

| Provider | 锁实现 | 说明 |
|----------|--------|------|
| `local` | synchronized + 锁映射表 | 单实例内互斥，自动清理过期锁对象 |
| `redis` | Redis SETNX + Lua 脚本 | 分布式互斥，防止多实例重复请求 |

**本地锁清理策略**：
- 每 100 次操作触发一次清理检查
- 自动移除超过 60 秒未使用的锁对象
- 防止锁映射表无限增长

**Redis 锁参数**（内置默认值）：
- 锁超时时间：30 秒
- 重试间隔：100 毫秒
- 最大重试次数：300 次（约 30 秒）

---

## License

Apache License 2.0