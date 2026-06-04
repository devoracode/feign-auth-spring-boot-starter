# feign-auth-spring-boot-starter

`feign-auth-spring-boot-starter` 是一个用于 OpenFeign 鉴权自动注入的 Spring Boot Starter。

它适用于需要同时对接多个第三方 API 的业务系统。不同第三方服务可能有不同的鉴权方式、Token 获取地址、Token 请求字段名、Header 名称，甚至同一个域名下不同接口路径也可能使用不同的 client 凭证。这个 starter 的目标是把这些差异集中到配置中，让业务层 FeignClient 不再感知 Token 获取、缓存和注入逻辑。

业务 FeignClient 只需要声明目标 `url`，并统一使用 `FeignClientConfig`。starter 会根据 Feign 请求的 `base-url` 和请求路径自动匹配服务配置，然后注入正确的鉴权 Header。

## 功能特性

- 支持 Spring Boot 2.6.x、Spring Cloud OpenFeign 2021.x、Java 8。
- 支持在 `feign.services` 下配置多个第三方服务。
- 支持 OAuth2 风格的 Token 获取，Token 请求方式支持 `GET` 和 `POST`。
- 支持自定义 Token 请求字段名，例如 `clientId/clientSecret`、`appId/appSecret`。
- 支持一个服务下配置多个 OAuth2 client，并按请求路径前缀自动选择 client。
- 支持 OAuth2 默认 client，当没有路径前缀命中时自动兜底。
- 支持固定 API Key 鉴权，可配置 Header 名称和 Header 值。
- 支持多个服务共用同一个域名，按最长 `path-prefixes` 优先匹配。
- 支持同域名下配置一个兜底服务，用于处理未命中特定路径的请求。
- 支持 OAuth2 Token 缓存，并在过期前自动刷新。
- 支持通过 `token-field` 配置从 Token 响应中提取 accessToken 的字段路径，支持 `data.accessToken` 这样的多级路径。
- 支持通过 `token-expires-in-seconds` 显式指定 Token 有效期；未配置时自动从响应中按层查找过期时间字段（最多三层嵌套）。
- 内置 Spring Boot 配置元数据，IDE 可提供配置提示。

## 环境要求

- Java 8+
- Spring Boot 2.6.x
- Spring Cloud 2021.x
- 使用方项目已启用 OpenFeign

## 安装依赖

在业务项目中引入依赖：

```xml
<dependency>
    <groupId>io.github.devoracode</groupId>
    <artifactId>feign-auth-spring-boot-starter</artifactId>
    <version>1.5.0</version>
</dependency>
```

如果业务项目还没有启用 FeignClient，需要在启动类上添加 `@EnableFeignClients`：

```java
@SpringBootApplication
@EnableFeignClients
public class DemoApplication {

    public static void main(String[] args) {
        SpringApplication.run(DemoApplication.class, args);
    }
}
```

## 快速开始

先在配置文件中定义第三方服务：

```yaml
feign:
  services:
    order:
      base-url: https://api.example.com
      auth:
        type: oauth2
        token-url: https://api.example.com/oauth/token
        method: post
        token-header: x-token
        request-fields:
          grant-type: grantType
        clients:
          - id: order-client
            secret: order-secret
            grant-type: client_credentials
```

`request-fields` 中只有 `grant-type` 需要显式配置，`client-id` 和 `client-secret` 不配置时会自动使用默认字段名 `client_id` 和 `client_secret`。如果目标服务不需要 `grant_type` 参数，可以完全省略 `request-fields` 和 `clients[].grant-type`。

然后在 FeignClient 上指定 `configuration = FeignClientConfig.class`：

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

当调用 `getOrder` 时，starter 会自动完成以下流程：

1. 读取当前 Feign 请求的目标 URL。
2. 根据 `https://api.example.com` 匹配 `feign.services.order.base-url`。
3. 如果缓存中没有有效 Token，则请求 `token-url` 获取 Token。
4. 将 Token 注入到配置的 `x-token` 请求 Header 中。

## OAuth2 配置说明

OAuth2 服务需要配置 `auth.type: oauth2`。

```yaml
feign:
  services:
    measure:
      base-url: https://api.service-a.com
      auth:
        type: oauth2
        token-url: https://api.service-a.com/oauth/token
        method: post
        token-header: x-token
        request-fields:
          client-id: clientId
          client-secret: clientSecret
          grant-type: grantType
        clients:
          - id: measure-event-client
            secret: measure-event-secret
            path-prefixes:
              - /api/measure/event
              - /api/measure/event2

          - id: measure-telemetry-client
            secret: measure-telemetry-secret
            path-prefixes:
              - /api/measure/telemetry

          - id: measure-default-client
            secret: measure-default-secret
```

### OAuth2 字段说明

| 配置项 | 是否必填 | 默认值 | 说明 |
| --- | --- | --- | --- |
| `auth.type` | 是 | 无 | 固定为 `oauth2`。 |
| `auth.token-url` | 是 | 无 | Token 获取地址。 |
| `auth.method` | 否 | `post` | Token 请求方式，支持 `post` 和 `get`。 |
| `auth.token-header` | 否 | `x-token` | 注入 Token 时使用的请求 Header 名称。 |
| `auth.token-field` | 否 | 空 | Token 响应中 accessToken 的字段路径，支持 `data.accessToken` 这样的多级路径。不填时自动识别 `access_token`、`accessToken`、`token`。 |
| `auth.token-expires-in-seconds` | 否 | 空 | Token 有效期（秒）。配置为正数时直接使用该值，不再从响应中解析过期时间。 |
| `auth.expire-ahead-seconds` | 否 | `60` | Token 过期前多少秒刷新缓存。 |
| `auth.request-fields.client-id` | 否 | `client_id` | Token 请求中 client id 的字段名。不配置时使用默认值 `client_id`。 |
| `auth.request-fields.client-secret` | 否 | `client_secret` | Token 请求中 client secret 的字段名。不配置时使用默认值 `client_secret`。 |
| `auth.request-fields.grant-type` | 否 | 空 | Token 请求中 grant type 的字段名。**不配置时 grant type 参数不会出现在请求中**，适用于不需要该参数的服务。 |
| `auth.clients[].id` | 是 | 无 | OAuth2 client id。 |
| `auth.clients[].secret` | 是 | 无 | OAuth2 client secret。 |
| `auth.clients[].grant-type` | 否 | 空 | OAuth2 grant type 字段值。**不配置时 grant type 参数不会出现在请求中**。仅在同时配置了 `auth.request-fields.grant-type` 时生效。 |
| `auth.clients[].path-prefixes` | 否 | 空 | 当前 client 匹配的请求路径前缀。不配置表示该 client 是默认 client。 |

### Token 请求行为

**请求参数构建**

starter 根据配置动态构建 Token 请求参数：

- `client-id`、`client-secret`：字段名不配置时默认使用 `client_id` 和 `client_secret`。
- `grant-type`：**只有同时配置了 `auth.request-fields.grant-type`（字段名）和 `auth.clients[].grant-type`（字段值）时才会出现在请求中**。不配置时该参数完全不发送，适用于不需要 grant type 参数的第三方服务。

当 `method: post` 时，starter 会发送 JSON 请求体。假设配置了完整的 request-fields 和 grant-type：

```json
{
  "client_id": "measure-event-client",
  "client_secret": "measure-event-secret",
  "grantType": "client_credentials"
}
```

如果 `auth.request-fields.grant-type` 或 `auth.clients[].grant-type` 任一未配置，则请求体中不会包含 grant type 参数。

当 `method: get` 时，starter 会将相同字段拼接为 query 参数：

```text
https://api.service.com/oauth/token?clientId=...&clientSecret=...&grantType=client_credentials
```

**自定义字段名示例**

对于使用 `appId`/`appSecret` 而非标准 `clientId`/`clientSecret` 的服务：

```yaml
auth:
  request-fields:
    client-id: appId
    client-secret: appSecret
    grant-type: grantType
  clients:
    - id: my-app-id
      secret: my-app-secret
      grant-type: client_credentials
```

对于不需要 `grant_type` 参数的服务，完全不配置 `request-fields.grant-type` 和 `clients[].grant-type`：

```yaml
auth:
  request-fields:
    client-id: appId
    client-secret: appSecret
  clients:
    - id: my-app-id
      secret: my-app-secret
```

### Token 响应解析

**Token 字段提取**

默认情况下，starter 会按以下顺序自动识别 Token 字段名，取第一个非空值：

- `access_token`
- `accessToken`
- `token`

如果第三方服务返回的 Token 字段名不在上述列表中，或 Token 嵌套在 JSON 对象内部，可以通过 `auth.token-field` 指定提取路径。

支持用 `.` 分隔的多级路径，例如响应结构为：

```json
{
  "code": 0,
  "data": {
    "accessToken": "eyJhbGci..."
  }
}
```

则配置：

```yaml
auth:
  token-field: data.accessToken
```

`token-field` 配置后，starter 只从该路径提取 Token，不再尝试内置字段名。

**过期时间**

解析优先级如下：

1. **配置了 `auth.token-expires-in-seconds` 且值为正数** — 直接使用该配置作为 Token 有效期（秒），忽略响应中的过期时间字段。
2. **未配置** — 在 Token 响应 JSON 中自动查找，按层向下搜索，最多三层（根对象 + 两层嵌套对象）。

每层依次尝试以下字段名，取第一个有效正数值：

- `expires_in`
- `expiresIn`
- `expire_in`
- `expireIn`

更浅层的命中优先于更深层。例如根对象与子对象同时存在 `expires_in` 时，使用根对象的值。

**显式指定有效期示例**

当第三方 Token 响应不包含过期时间，或有效期固定已知时：

```yaml
auth:
  type: oauth2
  token-url: https://api.example.com/oauth/token
  token-expires-in-seconds: 1800
  clients:
    - id: my-client
      secret: my-secret
```

**嵌套响应自动识别示例**

响应结构为：

```json
{
  "code": 0,
  "result": {
    "payload": {
      "expire_in": 1200,
      "accessToken": "eyJhbGci..."
    }
  }
}
```

未配置 `token-expires-in-seconds` 时，starter 会在第三层 `payload` 对象中找到 `expire_in: 1200`，并按 1200 秒缓存 Token（再减去 `expire-ahead-seconds`）。

如果响应中未找到任何过期时间字段，且未配置 `token-expires-in-seconds`，starter 默认按 **7200 秒** 缓存 Token。

## API Key 配置说明

API Key 服务需要配置 `auth.type: api-key`。

```yaml
feign:
  services:
    console:
      base-url: https://api.service-d.com
      auth:
        type: api-key
        header-name: Authorization
        value: sk-xxxxxxxxxxxxxxxx
        path-prefixes:
          - /api/orders
```

### API Key 字段说明

| 配置项 | 是否必填 | 默认值 | 说明 |
| --- | --- | --- | --- |
| `auth.type` | 是 | 无 | 固定为 `api-key`。 |
| `auth.header-name` | 否 | `Authorization` | 注入 API Key 时使用的请求 Header 名称。 |
| `auth.value` | 是 | 无 | 固定 API Key 值。 |
| `auth.path-prefixes` | 否 | 空 | 当前服务匹配的请求路径前缀。不配置表示该服务是同 `base-url` 下的兜底服务。 |

## 路由匹配规则

starter 会根据当前 Feign 请求的目标 URL 和请求路径选择鉴权配置。

### 1. 先匹配 base-url

只有 `base-url` 与 Feign 请求目标 URL 相同的服务才会进入候选列表。

末尾 `/` 会被忽略：

```text
https://api.example.com
https://api.example.com/
```

以上两个地址会被视为同一个 `base-url`。

### 2. 路径前缀按最长命中优先

当多个服务共用同一个域名时，starter 会优先选择 `path-prefixes` 命中长度最长的配置。

```yaml
feign:
  services:
    orders:
      base-url: https://api.example.com
      auth:
        type: api-key
        value: sk-orders
        path-prefixes:
          - /api/orders

    fallback:
      base-url: https://api.example.com
      auth:
        type: api-key
        value: sk-fallback
```

请求 `/api/orders/1001` 会使用 `orders` 的配置。

请求 `/api/products/2001` 会使用 `fallback` 的配置。

### 3. 同 base-url 只能有一个兜底配置

兜底配置指没有配置路径前缀的配置：

- API Key：`auth.path-prefixes` 未配置或为空。
- OAuth2：某个 `auth.clients[]` 未配置 `path-prefixes`。

同一个 `base-url` 下只能有一个兜底配置。多个兜底配置会导致路由歧义，starter 会直接抛出异常。

### 4. OAuth2 client 选择规则

在同一个 OAuth2 服务内部，starter 会按 `auth.clients[].path-prefixes` 的最长命中规则选择 client。

如果没有任何 client 的路径前缀命中，则使用唯一的默认 client。默认 client 指未配置 `path-prefixes` 的 client。

```yaml
feign:
  services:
    psr:
      base-url: https://api.service-p.com
      auth:
        type: oauth2
        token-url: https://api.service-p.com/oauth/accessToken
        method: get
        clients:
          - id: client-order
            secret: secret-order
            path-prefixes:
              - /api/orders

          - id: client-shipment
            secret: secret-shipment
            path-prefixes:
              - /api/shipments

          - id: client-default
            secret: secret-default
```

请求 `/api/orders/1` 会使用 `client-order`。

请求 `/api/shipments/1` 会使用 `client-shipment`。

请求 `/api/profile` 会使用 `client-default`。

## 完整配置示例

```yaml
feign:
  services:
    measure:
      base-url: https://api.service-a.com
      auth:
        type: oauth2
        token-url: https://api.service-a.com/oauth/token
        method: post
        token-header: x-token
        request-fields:
          client-id: clientId
          client-secret: clientSecret
          grant-type: grantType
        clients:
          - id: event-client
            secret: event-secret
            path-prefixes:
              - /api/measure/event
              - /api/measure/event2

          - id: telemetry-client
            secret: telemetry-secret
            path-prefixes:
              - /api/measure/telemetry

          - id: default-client
            secret: default-secret

    psr:
      base-url: https://api.service-p.com
      auth:
        type: oauth2
        token-url: https://api.service-p.com/oauth/accessToken
        method: get
        clients:
          - id: client-order
            secret: secret-order
            path-prefixes:
              - /api/orders

          - id: client-default
            secret: secret-default

    console-orders:
      base-url: https://api.service-d.com
      auth:
        type: api-key
        header-name: Authorization
        value: sk-orders
        path-prefixes:
          - /api/orders

    console-default:
      base-url: https://api.service-d.com
      auth:
        type: api-key
        header-name: Authorization
        value: sk-default
```

## FeignClient 使用示例

OAuth2 示例：

```java
@FeignClient(
    name = "measure-client",
    url = "${feign.services.measure.base-url}",
    configuration = FeignClientConfig.class
)
public interface MeasureFeignClient {

    @GetMapping("/api/measure/event/{eventId}")
    String getEvent(@PathVariable("eventId") String eventId);

    @GetMapping("/api/measure/telemetry/{deviceId}")
    String getTelemetry(@PathVariable("deviceId") String deviceId);
}
```

API Key 示例：

```java
@FeignClient(
    name = "console-client",
    url = "${feign.services.console-orders.base-url}",
    configuration = FeignClientConfig.class
)
public interface ConsoleFeignClient {

    @GetMapping("/api/orders/{orderId}")
    String getOrder(@PathVariable("orderId") String orderId);
}
```

## 自动配置

starter 会自动注册以下组件：

| Bean | 说明 |
| --- | --- |
| `FeignAuthAutoConfiguration` | 自动配置入口（`io.github.devoracode.feignauth.autoconfigure`） |
| `FeignAuthProperties` | 绑定 `feign.services.*` 配置 |
| `feignAuthRestTemplate` | starter 内部专用的 `RestTemplate`，与业务项目中的 `RestTemplate` 完全隔离 |
| `ServiceMatcher` | 按 base-url 与 path-prefix 匹配服务配置 |
| `OAuth2ClientMatcher` | 按 path-prefix 匹配 OAuth2 client |
| `TokenFetcher` | 获取并缓存 OAuth2 Token；业务项目可自定义同名 Bean 覆盖 |
| `ObjectMapper` | 当业务项目中不存在自定义 `ObjectMapper` Bean 时注册 |

业务 FeignClient 通过 `configuration = FeignClientConfig.class` 注册 `FeignAuthRequestInterceptor`，在每次请求发出前注入鉴权 Header。

为了兼容 Spring Boot 2.x，自动配置同时声明在以下文件中：

- `META-INF/spring.factories`
- `META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`

## 配置提示

starter 内置 Spring Boot Configuration Metadata。IDE 可以对以下配置提供补全提示：

- `feign.services`
- `feign.services.*.base-url`
- `feign.services.*.auth.type`
- `feign.services.*.auth.token-url`
- `feign.services.*.auth.token-field`
- `feign.services.*.auth.token-expires-in-seconds`
- `feign.services.*.auth.expire-ahead-seconds`
- `feign.services.*.auth.clients[].id`
- `feign.services.*.auth.clients[].path-prefixes`
- `feign.services.*.auth.header-name`
- `feign.services.*.auth.value`

## 异常处理

当配置存在歧义或缺少必要字段时，starter 会尽早抛出异常，避免请求带着错误鉴权信息发出。

常见异常场景包括：

- OAuth2 请求没有匹配到 client，且没有默认 client。
- 同一个 OAuth2 服务下配置了多个默认 client。
- 同一个 `base-url` 下配置了多个兜底服务。
- 多个服务命中了同一个 `base-url` 和相同长度的路径前缀。
- OAuth2 服务缺少 `token-url`。
- OAuth2 client 缺少 `id` 或 `secret`。
- API Key 服务缺少 `value`。
- Token 接口返回非 2xx 响应。
- Token 响应中不存在 `access_token`、`accessToken` 或 `token`。
- 配置了 `auth.token-field` 但该路径在 Token 响应中不存在或为空。

## 最佳实践

- 一个逻辑第三方服务建议对应 `feign.services` 下的一个配置项。
- 同一个域名承载多个鉴权上下文时，尽量显式配置 `path-prefixes`。
- 需要兜底时，每个 OAuth2 服务只配置一个默认 client。
- 同一个 `base-url` 下，每种兜底 API Key 服务只能配置一个。
- 除非第三方接口明确要求，否则建议使用常见 Header 名称，例如 `Authorization`。
- 第三方 Token 响应不含过期时间字段时，可配置 `token-expires-in-seconds` 显式指定缓存时长。
- 不要把真实密钥提交到代码仓库，建议使用环境变量、配置中心或密钥管理系统注入。

环境变量示例：

```yaml
feign:
  services:
    order:
      base-url: ${ORDER_API_BASE_URL}
      auth:
        type: oauth2
        token-url: ${ORDER_API_TOKEN_URL}
        clients:
          - id: ${ORDER_API_CLIENT_ID}
            secret: ${ORDER_API_CLIENT_SECRET}
```

## License

Apache License 2.0
