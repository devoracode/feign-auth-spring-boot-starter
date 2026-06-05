# feign-auth-spring-boot-starter

Spring Boot Starter，为 OpenFeign 提供多服务鉴权自动注入。将不同第三方 API 的 Token 获取、缓存、注入逻辑集中到配置文件中，业务 FeignClient 统一使用 `FeignClientConfig` 即可。

## 环境要求

- Java 8+
- Spring Boot 2.2.x
- 已启用 Spring Cloud OpenFeign

## 安装

```xml
<dependency>
    <groupId>io.github.devoracode</groupId>
    <artifactId>feign-auth-spring-boot-starter</artifactId>
    <version>1.8.0</version>
</dependency>
```

## 核心特性

- OAuth2 Token 自动获取、缓存与刷新，支持 `GET`/`POST` 两种请求方式
- API Key 鉴权，支持自定义 Header 名
- 同一服务可按请求路径前缀匹配不同 OAuth2 client 凭证
- 同域名下支持多个服务共享，按最长路径前缀优先匹配 + 兜底
- 自定义 Token 请求字段名（如 `appId`/`appSecret` 替代 `client_id`/`client_secret`）
- 从 Token 响应中自定义提取字段路径（如 `data.accessToken`），自动解析过期时间
- Token 过期自动重试（HTTP 421/423）

## 快速开始

**配置：**

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
            grant-type: client_credentials
```

**FeignClient：**

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

## OAuth2 配置

```yaml
feign:
  services:
    measure:
      base-url: https://api.service-a.com
      auth:
        type: oauth2
        token-url: https://api.service-a.com/oauth/token
        method: post                       # 默认 post，可选 get
        token-header: x-token              # 默认 x-token
        token-field: data.accessToken      # Token 提取路径，不填则自动识别
        token-expires-in-seconds: 1800     # 显式指定有效期，不填则从响应中解析（最多三层嵌套）
        expire-ahead-seconds: 60           # 默认 60，过期前多少秒刷新
        request-fields:                    # 自定义 Token 请求字段名（均为可选）
          client-id: clientId              # 默认 client_id
          client-secret: clientSecret      # 默认 client_secret
          grant-type: grantType            # 配置后 + client 也配置 grant-type 时才发送该参数
        clients:
          - id: event-client
            secret: event-secret
            path-prefixes: [/api/measure/event, /api/measure/event2]

          - id: default-client             # 未配置 path-prefixes = 默认 client
            secret: default-secret
```

### Token 响应解析

- **Token 字段**：默认依次尝试 `access_token` → `accessToken` → `token`；可通过 `token-field` 指定路径（如 `data.accessToken`）
- **过期时间**：默认从响应 JSON 中逐层查找（最多 3 层）`expires_in` → `expiresIn` → `expire_in` → `expireIn`；可通过 `token-expires-in-seconds` 显式指定；未找到时默认 7200 秒

## API Key 配置

```yaml
feign:
  services:
    console:
      base-url: https://api.service-d.com
      auth:
        type: api-key
        value: sk-xxxxxxxx
        header-name: Authorization         # 默认 Authorization
        path-prefixes: [/api/orders]
```

## 路由匹配规则

1. 按请求 `base-url` 匹配候选服务
2. 在候选服务中按最长 `path-prefixes` 命中优先
3. 无命中时使用兜底配置（同 `base-url` 下只能有一个兜底）
4. OAuth2 服务内部同样按 `path-prefixes` 匹配 client，无命中时使用默认 client

## 完整示例

```yaml
feign:
  services:
    # OAuth2 服务 — 多 client 按路径匹配
    measure:
      base-url: https://api.service-a.com
      auth:
        type: oauth2
        token-url: https://api.service-a.com/oauth/token
        request-fields:
          client-id: clientId
          client-secret: clientSecret
          grant-type: grantType
        clients:
          - id: event-client
            secret: event-secret
            path-prefixes: [/api/measure/event]
          - id: telemetry-client
            secret: telemetry-secret
            path-prefixes: [/api/measure/telemetry]
          - id: default-client
            secret: default-secret

    # OAuth2 服务 — GET 方式获取 Token
    psr:
      base-url: https://api.service-p.com
      auth:
        type: oauth2
        token-url: https://api.service-p.com/oauth/accessToken
        method: get
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
        value: sk-orders
        path-prefixes: [/api/orders]

    console-default:
      base-url: https://api.service-d.com
      auth:
        type: api-key
        header-name: Authorization
        value: sk-default
```

## 最佳实践

- 同一个 `base-url` 下只保留一个兜底配置
- 当 Token 响应不含过期时间或者过期时间不是默认的字段获取时，配置 `token-expires-in-seconds` 避免缓存问题

## License

Apache License 2.0
