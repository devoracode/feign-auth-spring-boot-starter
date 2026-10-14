# feign-auth-spring-boot-starter

Spring Boot Starter，为 OpenFeign 提供多服务鉴权自动注入与自定义 Header 扩展。将不同第三方 API 的 Token 获取、缓存、注入逻辑集中到配置文件中，业务 FeignClient 统一使用 `FeignClientConfig` 即可；如需注入动态计算的自定义 Header，实现 `HeaderCustomizer` 接口并注册为 Spring Bean 即可。

## 环境要求

- Java 8+（starter 以 `source/target 1.8` 编译）
- Spring Boot 2.2.x ~ 2.7.x：基线为 2.2.5（依赖管理与测试均以此为准），自动配置同时注册 `spring.factories`（≤2.6）与 `AutoConfiguration.imports`（2.7+），故 2.7 下同样可加载
- 使用方自行引入 OpenFeign（`spring-cloud-starter-openfeign`，对应 feign-core 10.x）：starter 对 `feign-core` 的依赖是 `optional`，不会传递进来
- 令牌请求所需的 Apache HttpClient 与 `provider: redis` 所需的 Spring Data Redis 目前由 starter 传递引入，无需单独声明

## 安装

```xml
<dependency>
    <groupId>io.github.devoracode</groupId>
    <artifactId>feign-auth-spring-boot-starter</artifactId>
    <version>1.19.0</version>
</dependency>
```

---

## 核心特性

- OAuth2 Token 自动获取、缓存与刷新，支持 `GET` / `POST` 两种请求方式
- API Key 鉴权，支持自定义 Header 名与前缀
- 同一服务可按请求路径前缀匹配不同 OAuth2 Client 凭证
- 同域名下支持多个服务配置，按最长路径前缀优先匹配 + 兜底
- `base-url` 按 `scheme://host:port` 归一化匹配（大小写与默认端口无关），配置的路径段自动作为外层前缀参与匹配
- 自定义 Token 请求字段名（如 `appId` / `appSecret` 替代 `client_id` / `client_secret`）
- 从 Token 响应中自定义提取字段路径（如 `data.accessToken`），自动解析过期时间
- Token 过期自动驱逐缓存并重试（默认 HTTP 401 / 421 / 423，可通过配置自定义状态码；同一 `{service}:{client}` 的驱逐有 30 秒冷却，冷却期内直接抛不可重试异常，详见「重试与请求重放」；默认 Retryer 可被自定义 Bean 覆盖）
- 支持从响应体中解析过期状态码（需配置 `response-status-field`，适用于不规范的第三方 API 返回 HTTP 200/400 但在响应体中包含过期状态）
- 静态固定 Header：在 YAML 中配置 `request-headers`（业务请求）与 `token-request-headers`（Token 请求）
- 动态 Header 注入：实现 `HeaderCustomizer` 接口，按服务维度注入任意动态计算的 Header
- 可插拔缓存机制：支持本地缓存（ConcurrentHashMap）和 Redis 缓存，自动清理过期 Token
- 可插拔锁机制：支持本地锁（synchronized）和 Redis 分布式锁，防止并发请求重复获取 Token
- 无匹配服务时默认快速失败（抛 `FeignAuthConfigurationException`），可配置 `feign.auth.fail-on-no-match: false` 回退为 WARN 后放行

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

### 令牌请求体编码

POST 取号的请求体编码由 `token-request-headers` 中的 `Content-Type` 决定（未配置默认 `application/json`）：

- `application/json`（或 `*/*+json`、`text/json` 等 json 类型）：参数以 JSON 对象发送；
- `application/x-www-form-urlencoded`：参数以表单键值对发送（UTF-8，符合 RFC 6749 的 token 端点惯例）；
- 其他值或无法解析的值：启动后首次取号即抛 `FeignAuthConfigurationException` 快速失败，不会发出头体错配的请求。

### Token 响应解析

**Token 字段**：默认依次尝试 `access_token` → `accessToken` → `token`；可通过 `token-field` 指定点分路径（如 `data.accessToken`）。响应体解码优先使用响应 `Content-Type` 声明的字符集，未声明时按 UTF-8。

**过期时间**：默认从响应 JSON 中逐层查找（最多 3 层） `expires_in` → `expiresIn` → `expire_in` → `expireIn`；可通过 `token-expires-in-seconds` 显式指定；未找到时默认 7200 秒。刷新窗口保证至少 1 秒：当 `expire-ahead-seconds` ≥ 实际有效期时自动收敛，避免缓存条目立即过期或 Redis TTL 非法。生效 TTL 上限为 1 天（86400 秒）：超长有效期（含误把毫秒当秒返回的异常值）一律按 1 天缓存并每日刷新，同时杜绝时间戳溢出导致的"每请求刷新"问题。

---

## 令牌请求 HTTP 客户端

OAuth2 取号走 starter 内置的 Apache HttpClient（连接池复用），参数通过 `feign.auth.http` 全局配置：

```yaml
feign:
  auth:
    http:
      connect-timeout: 5s                 # 连接超时
      connection-request-timeout: 5s      # 从连接池获取连接的等待超时
      socket-timeout: 10s                 # 读取超时
      max-total: 200                      # 连接池最大连接数
      default-max-per-route: 50           # 每个路由最大连接数
```

该客户端注册为 Spring 容器管理的 Bean，应用上下文关闭时自动 `close()` 释放连接池与空闲连接驱逐线程。如需替换实现（例如换用其他 HTTP 客户端），直接声明自己的 `OAuth2TokenRequestClient` Bean 即可，starter 默认实现会自动让位并被默认 `TokenFetcher` 采用。

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

1. 按请求 `base-url` 的 **authority**（`scheme://host:port`）过滤候选服务：忽略大小写，补全协议默认端口（`http`→80、`https`→443，因此 `https://api.x.com` 与 `https://api.x.com:443` 等价）；配置 `base-url` 携带的路径段（如 `https://api.x.com/v1` 中的 `/v1`）自动视为**外层前缀**——请求路径落在其下时先剥离该段，再进行 `path-prefixes` 匹配
2. 在候选服务中，按最长 `path-prefixes` 命中的服务优先；前缀按**路径段边界**匹配（`/api/measure` 命中 `/api/measure/1`，不会命中 `/api/measurement/1`），前缀末尾的 `/` 会被忽略
3. 无路径命中时，使用兜底配置（同 `base-url` 下只能有一个兜底）
4. OAuth2 服务内部：先按 Client 的 `path-prefixes` 匹配，无命中时使用默认 Client（无 `path-prefixes` 的 Client）
5. 路径命中长度相同时，抛出配置冲突异常，需消除歧义

**兜底条件：**

- API Key：`auth.path-prefixes` 为空
- OAuth2：`clients` 中存在未配置 `path-prefixes` 的 Client

### 无匹配服务时的处置

当请求未能匹配到任何服务配置（如走服务发现时负载均衡名与配置不一致，或 target URL 指向了未配置的 host/端口），默认**快速失败**：直接抛出 `FeignAuthConfigurationException`，请求不会发出，避免未鉴权请求打到远端接口。异常信息会列出当前已配置的服务清单（`名称 -> base-url`）与修正指引。

如需恢复旧版「记录 WARN 日志后不带鉴权头放行」的行为，可显式关闭：

```yaml
feign:
  auth:
    fail-on-no-match: false   # 默认 true
```

### auth 配置非法时的处置

若请求命中的服务存在但 `auth` 缺失、或 `auth.type` 拼写错误（如 `oauth`、`apikey`，合法取值只有 `oauth2` 与 `api-key`），**始终快速失败**并抛出 `FeignAuthConfigurationException`，不受 `fail-on-no-match` 开关影响——这类配置笔误不应静默放行未鉴权请求。

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

## 重试与请求重放

starter 默认注册一个 Feign `Retryer`（最多 2 次尝试，退避 100ms ~ 1s），以下两种情况会**重放整个请求**：

1. **Token 过期**：收到 401 / 421 / 423（或 `expired-token-statuses` 配置的状态码）时，starter 驱逐缓存 token 并抛出 `RetryableException`，Feign 携带新 token 重发一次。同一 `{service}:{client}` 的驱逐有 **30 秒冷却**（硬编码）：若远端对新 token 仍然拒绝，冷却期内的请求不再驱逐、不再重放，而是直接抛出不可重试的 `FeignAuthTokenException`，避免持续 401 把令牌刷新压力放大到 IdP。
2. **网络类错误**：连接/读取超时等 I/O 异常同样会被默认 Retryer 重试。

重放带来的两个约束需要使用方知晓：

- **非幂等接口**：POST 创建订单等请求若在服务端已实际处理、但响应未按预期返回（超时、401 歧义），重放会造成重复提交。建议为非幂等接口设计幂等键。
- **HeaderCustomizer 副作用**：每次重试都会重新执行 `RequestInterceptor`，因此 `HeaderCustomizer` 每次尝试都会再跑一遍——HMAC 时间戳、nonce、签名计数器会被消耗两次。定制器应保持无副作用（纯读取、纯计算）；若必须消费一次性凭据，请关闭自动重放或让凭据服务对同一请求幂等。

> Header 本身不会重复叠加：同名 header 在合并进 `RequestTemplate` 前会先清除旧值。

### 关闭或自定义重放

默认 Retryer 带 `@ConditionalOnMissingBean(Retryer.class)`，只要在该客户端的子上下文中先行注册自己的 `Retryer` Bean，starter 就自动让位：

```java
@Configuration
public class NoReplayConfig {
    @Bean
    public Retryer noReplayRetryer() {
        return Retryer.NEVER_RETRY;
    }
}

@FeignClient(name = "orders", url = "${feign.services.orders.base-url}",
    configuration = {NoReplayConfig.class, FeignClientConfig.class})
public interface OrdersClient { ... }
```

`Retryer.NEVER_RETRY` 会一并关掉 Token 过期后的那次重放：驱逐仍会发生（缓存被清、下次取号拿到新令牌），但当前请求直接把异常抛给业务方。若只想控制次数，用 `new Retryer.Default(100, TimeUnit.SECONDS.toMillis(1), n)`，其中 `n` 是含首次调用在内的总尝试数（starter 默认 `n = 2`；`n = 1` 即不重放）。注意无参的 `new Retryer.Default()` 是 `n = 5`，不能用来关闭重放。

注意 `configuration` 数组中自定义配置要排在 `FeignClientConfig` 之前；若顺序无法保证，可给自定义 Retryer 加 `@Primary`。

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
        key-prefix: feign-auth:  # Redis key 前缀，默认 feign-auth:
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
- Spring Data Redis 已随 starter 传递引入，无需单独声明依赖；使用方只需配置 Redis 连接（`spring.redis.*` 等）使容器中存在 `RedisConnectionFactory`

### 锁机制

锁机制与缓存 provider 联动，确保同一 Token 不会被并发请求重复获取：

| Provider | 锁实现 | 说明 |
|----------|--------|------|
| `local` | 定长分段锁（64 个 ReentrantLock，按 key 哈希取模） | 单实例内互斥，无锁对象清理需求 |
| `redis` | Redis SETNX + Lua 脚本 | 分布式互斥，防止多实例重复请求 |

**本地锁特点**：
- 固定 64 个锁分段，无动态锁映射表，不存在清理竞态
- 不同 key 哈希到同一分段时会共享锁，仅影响并发度，不影响正确性

**Redis 锁参数**（内置默认值）：
- 锁超时时间：30 秒
- 重试间隔：200 毫秒
- 最大重试次数：60 次（约 12 秒）
- 解锁采用 Lua 脚本原子操作（GET + DEL），避免并发竞态
- 仅在成功获取锁后执行解锁，异常获取失败时不解锁，防止误删他人锁

---

## 许可证

本项目基于 [Apache License 2.0](https://www.apache.org/licenses/LICENSE-2.0) 开源，完整条款见仓库根目录的 `LICENSE` 文件，`pom.xml` 中的 `<licenses>` 声明与之一致。


