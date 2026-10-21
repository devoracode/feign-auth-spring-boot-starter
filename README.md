# feign-auth-spring-boot-starter

Spring Boot Starter，为 OpenFeign 提供**多服务鉴权自动注入**与**自定义 Header 扩展**。将不同第三方 API 的 Token 获取、缓存、注入逻辑集中到配置文件中，业务 FeignClient 统一使用 `FeignClientConfig` 即可；如需注入动态计算的自定义 Header，实现 `HeaderCustomizer` 接口并注册为 Spring Bean 即可。

> 适用版本：**2.0.0**（配置根键 `feign-auth`）· Java 8+ · Spring Boot 2.2.x ~ 2.7.x

**本文是本项目的完整使用指南**，覆盖从安装到生产落地的全部内容：

- 每个配置项的类型、默认值、适用范围与**校验时机**（启动期 / 请求期）
- 内部工作机制：匹配算法、取号流程、缓存与锁、过期驱逐与重放
- 按任务组织的场景配方（每条可直接复制）
- 报错索引与分障路径
- 1.x → 2.0.0 升级对照

### 阅读约定

| 约定 | 含义 |
|---|---|
| `feign-auth.services.<name>.auth.token-header` | 完整配置键。`<name>` 是 `feign-auth.services` 下你自己起的逻辑服务名 |
| **常量** | 硬编码在源码里的取值（超时、TTL 上限、冷却窗口等），会随代码变化，以「常量」标注 |
| 报错文案 | 用等宽字体原样给出，可直接复制去日志里搜索 |
| 「启动期」/「请求期」 | 启动期失败 = 应用起不来；请求期失败 = 能启动，调用到才报错。两者的排查方式不同 |

---

## 目录

- [1. 概述](#1-概述)
- [2. 环境要求](#2-环境要求)
- [3. 安装](#3-安装)
- [4. 快速开始](#4-快速开始)
- [5. 配置参考](#5-配置参考)
- [6. 认证方式](#6-认证方式)
- [7. 路由与匹配](#7-路由与匹配)
- [8. Header 注入](#8-header-注入)
- [9. 令牌缓存、并发与过期重放](#9-令牌缓存并发与过期重放)
- [10. 完整示例配置](#10-完整示例配置)
- [11. 场景配方](#11-场景配方)
- [12. 扩展点](#12-扩展点)
- [13. 排障](#13-排障)
- [14. 生产就绪检查清单](#14-生产就绪检查清单)
- [15. 升级到 2.0.0](#15-升级到-200)
- [16. 已知限制](#16-已知限制)
- [附录 A：常量与默认值速查](#附录-a常量与默认值速查)
- [附录 B：术语表](#附录-b术语表)

第一次接入只需读完第 1 ~ 4 章；其余按需查。

---

## 1. 概述

### 1.1 核心特性

- OAuth2 Token 自动获取、缓存与刷新，支持 `GET` / `POST` 两种请求方式
- 客户端凭证发送方式可配置（`client-authentication: auto` / `basic` / `parameters`）：`GET` 默认按 RFC 6749 `client_secret_basic` 走 `Authorization` 头，凭据不落查询串
- API Key 鉴权，支持自定义 Header 名与前缀
- 同一服务可按请求路径前缀匹配不同 OAuth2 Client 凭证
- 同域名下支持多个服务配置，按最长路径前缀优先匹配 + 兜底
- `base-url` 按 `scheme://host:port` 归一化匹配（大小写与默认端口无关），配置的路径段自动作为外层前缀参与匹配
- 自定义 Token 请求字段名（如 `appId` / `appSecret` 替代 `client_id` / `client_secret`）
- 从 Token 响应中自定义提取字段路径（如 `data.accessToken`），自动解析过期时间
- Token 过期自动驱逐缓存并重试（默认 HTTP 401 / 421 / 423，可通过配置自定义状态码；同一 `{service}:{client}` 的驱逐有 30 秒冷却，冷却期内直接抛不可重试异常，详见「过期令牌的识别」；默认 Retryer 可被自定义 Bean 覆盖）
- 支持从响应体中解析业务状态码识别过期令牌（需配置 `response-status-field`，仅对非 2xx 响应生效，详见「过期令牌的识别」）
- 静态固定 Header：在 YAML 中配置 `request-headers`（业务请求）与 `token-request-headers`（Token 请求）
- 动态 Header 注入：实现 `HeaderCustomizer` 接口，按服务维度注入任意动态计算的 Header
- 可插拔缓存机制：支持本地缓存（ConcurrentHashMap）和 Redis 缓存，自动清理过期 Token
- 可插拔锁机制：支持本地锁（分段 ReentrantLock）和 Redis 分布式锁，防止并发请求重复获取 Token
- 无匹配服务时默认快速失败（抛 `FeignAuthConfigurationException`），可配置 `feign-auth.fail-on-no-match: false` 回退为 WARN 后放行

### 1.2 概念模型

理解五个对象之间的关系，配置项就不用死记了：

```
feign-auth
└── services                ← 逻辑服务名（你自己起），匹配的依据是 base-url + 路径，不是名字
    └── <name>
        ├── base-url        ← 决定「这个服务管哪些请求」：scheme://host:port 相同即同一组候选
        └── auth            ← 这个服务「怎么鉴权」
            ├── type        ← oauth2（取号）| api-key（固定值）
            ├── clients[]   ← 仅 oauth2：一组客户端凭证，按 path-prefixes 路由
            │   ├── id / secret / grant-type
            │   └── path-prefixes  ← 没有此项 = 该服务的默认凭证
            ├── path-prefixes  ← 仅 api-key：生效的路径前缀，没有此项 = 该 base-url 下的兜底
            └── value / header-name  ← 仅 api-key
```

| 对象 | 作用 | 出现在 |
|---|---|---|
| **服务（Service）** | 一个对外 API 的鉴权配置单元。由 `base-url` 的 authority + 路径前缀决定命中范围 | 缓存键前缀、日志、`HeaderCustomizer#supports` |
| **客户端凭证（Client）** | 一组 `id` / `secret`。一个服务下可有多组，按路径前缀路由 | 缓存键后半段、取号请求参数 |
| **兜底（Fallback）** | 路径前缀都不命中时使用的那一项。每个 base-url 只能有一个兜底 | 服务级（api-key 无前缀）/ 客户端级（oauth2 有默认凭证） |
| **缓存键** | `{服务名}:{clientId}`，令牌按它存取 | 本地 Map 与 Redis 共用 |
| **重放（Replay）** | 令牌过期时清缓存并把请求再发一次 | `ErrorDecoder` + Feign `Retryer` |

> **服务名与 `@FeignClient(name=...)` 没有任何绑定关系。** 服务名只用于配置引用、缓存键、日志与定制器。真正决定「哪个请求用哪套配置」的是 **base-url 的 authority + 路径前缀**。

### 1.3 一次请求的处理流程

```
Feign 业务请求
   │
   ├─▶ RequestInterceptor（正向）
   │     1. 从 target 拆出 base-url 与请求路径，两侧统一归一化口径
   │     2. ServiceMatcher：authority 过滤 → 剥 base-url 路径前缀 → 最长 path-prefixes → 兜底
   │     3. 选定服务后：OAuth2 选 Client / API Key 直接取 value
   │     4. OAuth2 → TokenFetcher：查缓存 → 未命中则加锁二次检查 → 取号 → 写缓存
   │     5. 写认证 Header（token-prefix + 值）
   │     6. HeaderManager：叠加静态 request-headers 与 HeaderCustomizer 动态 Header
   │
   ├─▶ 远端响应（非 2xx）
   │     ErrorDecoder → FeignAuthStatusHandler
   │     1. 从最终请求 URL 反推 base-url 与路径（与正向同一口径）
   │     2. 判定是否为「令牌过期」：HTTP 码命中 expired-token-statuses
   │        或（OAuth2 且配了 response-status-field）响应体字段值命中
   │     3. 命中 → 驱逐缓存：
   │          驱逐成功 → 抛 RetryableException → Feign Retryer 重放一次
   │          冷却期内/缓存已空 → 抛不可重试的 FeignAuthTokenException
   │     4. 未命中 → 抛 FeignAuthTokenException（cause 保留原始 FeignException）
   │
   └─▶ 远端响应（2xx）→ 正常返回
```

**关键点：正向与反向的路径归一化口径必须完全一致**，否则 4xx 时匹配不到同一个服务，表现为「注入了令牌却不驱逐、不重放」。这是 starter 内部统一走 `PathUtils.normalizeRequestPath` 的原因。

### 1.4 适用范围

**适合：**

- 第三方 API 的 OAuth2 `client_credentials` 取号（机器对机器）
- 固定 API Key 注入
- 同一网关下按路径区分多套凭证
- 需要「令牌过期自动换号并重试」的场景

**不适合：**

- 需要用户授权码、刷新令牌、密码模式等**交互式授权流程**——本 starter 只实现 `client_credentials`
- 需要按请求体内容、Header 值等非路径维度路由
- 服务发现（负载均衡名）场景：匹配依赖 target 的真实 `scheme://host:port`，用 `name` 而不给 `url` 时通常匹配不上（见 [7.5](#75-未匹配与非法配置)）
- 已经在用 Feign 拦截器且自建了完整令牌体系的项目——直接用现成方案更省事

---

## 2. 环境要求

| 项 | 要求 | 说明 |
|---|---|---|
| Java | 8+ | starter 以 `source/target 1.8` 编译 |
| Spring Boot | 2.2.x ~ 2.7.x | 依赖管理基线 2.2.5；自动配置同时注册 `spring.factories`（≤2.6）与 `AutoConfiguration.imports`（2.7+） |
| OpenFeign | 使用方自行引入 | 建议 `spring-cloud-starter-openfeign`（feign-core 10.x）。starter 对 `feign-core` 是 `optional`，**不传递** |
| Apache HttpClient 4.x | starter 传递引入 | 仅用于 OAuth2 取号，不需要你声明 |
| Spring Data Redis | 仅 `provider: redis` 时需要 | 对 `spring-boot-starter-data-redis` 是 `optional`，**不传递** |

只用默认的 `cache.provider: local` 时，classpath 上不需要任何 Redis 依赖。

---

## 3. 安装

### 3.1 Maven

```xml
<dependency>
    <groupId>io.github.devoracode</groupId>
    <artifactId>feign-auth-spring-boot-starter</artifactId>
    <version>2.0.0</version>
</dependency>

<!-- OpenFeign：使用方自行提供 -->
<dependency>
    <groupId>org.springframework.cloud</groupId>
    <artifactId>spring-cloud-starter-openfeign</artifactId>
</dependency>

<!-- 仅 cache.provider: redis 需要 -->
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-data-redis</artifactId>
</dependency>
```

### 3.2 Gradle

```groovy
implementation 'io.github.devoracode:feign-auth-spring-boot-starter:2.0.0'
implementation 'org.springframework.cloud:spring-cloud-starter-openfeign'

// 仅 provider: redis 需要
implementation 'org.springframework.boot:spring-boot-starter-data-redis'
```

`spring-cloud-starter-openfeign` 不写 `<version>` 的前提是工程已 import Spring Cloud BOM。若未 import，按下表填一个具体版本（表的来源见 [Spring Cloud](https://spring.io/projects/spring-cloud/) 官方版本说明）：

| Spring Boot | Spring Cloud release train | `spring-cloud-starter-openfeign` |
|---|---|---|
| 2.2.x、2.3.x | `Hoxton` | 2.2.x |
| 2.4.x、2.5.x | `2020.0.x` | 3.0.x |
| 2.6.x、2.7.x | `2021.0.x` | 3.1.x |

> 本 starter 在 Spring Boot 2.2.5 + feign-core 10.7.4 上开发与验证。用更高版本时，先确认你自己的 Feign 接口能正常调通，再接本 starter。

### 3.3 确认依赖真的进来了

```bash
mvn dependency:tree -Dincludes=io.github.devoracode
```

应输出 `io.github.devoracode:feign-auth-spring-boot-starter:jar:2.0.0`，并带出 `org.apache.httpcomponents:httpclient`（取号用的，不需要你声明）。

### 3.4 安装后如何确认生效

项目启动后，容器中应存在以下 Bean（`provider: local` 时）：

| Bean | 类型 | 作用 |
|---|---|---|
| `feignAuthAutoConfiguration` | 配置类 | 自动配置入口 |
| `serviceMatcher` | `ServiceMatcher` | 按 base-url + 路径匹配服务 |
| `oAuth2ClientMatcher` | `OAuth2ClientMatcher` | 服务内选 OAuth2 Client |
| `objectMapper` | `ObjectMapper` | 令牌响应解析与 JSON 序列化 |
| `headerManager` | `HeaderManager` | Header 注入管线 |
| `tokenStore` | `LocalTokenStore` | 令牌缓存 |
| `lockProvider` | `LocalLockProvider` | 单实例并发控制 |
| `oAuth2TokenRequestClient` | `OAuth2TokenRequestClient` | 真正发取号请求的 HTTP 客户端 |
| `tokenFetcher` | `TokenFetcher` | 取号、缓存、驱逐 |

> `FeignClientConfig` 本身是逐个 `@FeignClient` 子上下文里的配置类，**不在主容器中**。它注册的 `RequestInterceptor` / `ErrorDecoder` / `Retryer` 只作用于挂了它的客户端。

---

## 4. 快速开始

### 4.1 最小配置

写到 `src/main/resources/application.yml`。`feign-auth` 是**根键**，必须顶格——它和 `spring:`、`server:` 同一层：

```yaml
feign-auth:
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

只要 `base-url`、`auth.type`、`token-url`、`clients[].id/secret` 四项配齐，其余全部走默认值。密钥请用占位符从环境变量注入，不要写进代码库：

```yaml
secret: ${ORDER_SECRET}
```

### 4.2 声明 FeignClient

```java
import io.github.devoracode.feignauth.feign.FeignClientConfig;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

@FeignClient(
    name = "order-client",
    url = "${feign-auth.services.order.base-url}",
    configuration = FeignClientConfig.class
)
public interface OrderFeignClient {

    @GetMapping("/api/orders/{orderId}")
    String getOrder(@PathVariable("orderId") String orderId);
}
```

四个要点：

1. **`configuration = FeignClientConfig.class` 不能漏。** 漏了以后请求照样发出去，但不带认证 Header、也不会做过期重放，典型现象是对端一直回 401 而日志里一条 `FeignAuth` 都没有。`FeignClientConfig` 由 starter 提供，不需要自己写。
2. **`url` 用 `${feign-auth.services.<name>.base-url}` 引用**，避免域名写两处对不上。直接写死也可以，但必须与 `base-url` 的协议、端口、路径完全一致。
3. **占位符键名拼错不会编译报错**，也不会在启动时报「占位符解析失败」：`@FeignClient` 注册发生在占位符解析器装上之前，`${...}` 会原样留在 target 上，第一次调用才以 `no service config matched for baseUrl=http://${...}` 暴露。
4. **启动类忘了 `@EnableFeignClients`**，否则注入客户端时启动报 `NoSuchBeanDefinitionException`（与 starter 无关）。接口不在启动类所在包时写 `@EnableFeignClients(basePackages = "com.acme.feign")`。

### 4.3 业务代码完全不感知 token

```java
@Service
public class OrderService {

    private final OrderFeignClient orderFeignClient;

    public OrderService(OrderFeignClient orderFeignClient) {
        this.orderFeignClient = orderFeignClient;
    }

    public String query(String orderId) {
        return this.orderFeignClient.getOrder(orderId);
    }
}
```

### 4.4 验证接入

打开 DEBUG 日志（与 `feign-auth` 顶格同级）：

```yaml
logging:
  level:
    io.github.devoracode.feignauth: DEBUG
```

第一次调用会看到两行：

```text
INFO  io.github.devoracode.feignauth.oauth2.TokenFetcher : FeignAuth [oauth2] fetching new token for service='order', clientId='my-client'
DEBUG io.github.devoracode.feignauth.feign.FeignAuthRequestInterceptor : FeignAuth [oauth2] service='order' header='x-token' path=/api/orders/1
```

**跑通的三条标准：**

1. 日志里出现过 `fetching new token`；
2. 没有任何 `FeignAuth` 开头的 WARN / ERROR；
3. 对端返回 2xx，业务方法拿到正常返回值。

第二次及以后的调用只剩 DEBUG 那行——令牌命中缓存，不再取号。

> starter 日志**不会打印令牌或密钥内容**。想核对发出去的 Header 值，需要本机抓包或让对端回显。

### 4.5 先绕开 starter，验证凭证本身能不能拿到

怀疑配置对不对之前，先用 curl 手工取一次号，把「凭证/端点问题」和「starter 写法问题」分开。默认写法（`method: post`，请求体是 JSON）对应：

```bash
curl -s -X POST "https://api.example.com/oauth/token" \
  -H "Content-Type: application/json" \
  -d '{"grant_type":"client_credentials","client_id":"my-client","client_secret":"my-secret"}'
```

对方要求表单编码时（配了 `token-request-headers.Content-Type: application/x-www-form-urlencoded`）：

```bash
curl -s -X POST "https://api.example.com/oauth/token" \
  -H "Content-Type: application/x-www-form-urlencoded" \
  --data-urlencode "grant_type=client_credentials" \
  --data-urlencode "client_id=my-client" \
  --data-urlencode "client_secret=my-secret"
```

对方要求 Basic（配了 `client-authentication: basic`）：

```bash
curl -s -X POST "https://api.example.com/oauth/token" \
  -u "my-client:my-secret" \
  -H "Content-Type: application/x-www-form-urlencoded" \
  -d "grant_type=client_credentials"
```

响应里出现 `access_token` 说明凭证、端点、认证方式都对，问题一定出在 YAML 写法上；出现 `error=invalid_client` 就先去核对凭证，别改 starter 配置。

---

## 5. 配置参考

### 5.1 键结构总览

```yaml
feign-auth:                          # 根键，顶格
  fail-on-no-match: true             # 全局
  cache:                             # 全局
    provider: local
    redis:
      key-prefix: "feign-auth:"
  http:                              # 全局
    connect-timeout: 5s
    connection-request-timeout: 5s
    socket-timeout: 10s
    max-total: 200
    default-max-per-route: 50
  services:                          # Map：逻辑服务名 → 服务配置
    <name>:
      base-url: https://api.example.com
      auth:
        type: oauth2                 # oauth2 | api-key
        # —— OAuth2 专用 ——
        token-url: https://api.example.com/oauth/token
        method: post                 # post | get
        client-authentication: auto  # auto | basic | parameters
        token-header: x-token
        token-field: data.accessToken
        token-expires-in-seconds: 1800
        expire-ahead-seconds: 60
        expired-token-statuses: [401, 421, 423]
        response-status-field: code
        request-fields:
          client-id: client_id
          client-secret: client_secret
          grant-type: grant_type
        clients:
          - id: my-client
            secret: my-secret
            grant-type: client_credentials
            path-prefixes: [/api/orders]
        # —— API Key 专用 ——
        # value: sk-xxxxxxxx
        # header-name: Authorization
        # path-prefixes: [/api/orders]
        # —— 通用 ——
        token-prefix: "Bearer "
        request-headers: {}
        token-request-headers: {}
```

### 5.2 全局键

| 配置键 | 类型 | 默认值 | 含义 |
|---|---|---|---|
| `feign-auth.fail-on-no-match` | boolean | `true` | 请求未匹配到任何服务时是否快速失败，见 [7.5](#75-未匹配与非法配置) |
| `feign-auth.cache` | 对象 | — | 令牌缓存与锁，见 [5.7](#57-cache) |
| `feign-auth.http` | 对象 | — | 取号 HTTP 客户端，见 [5.8](#58-http) |

`fail-on-no-match` 的两种行为：

- `true`（默认）：抛出 `FeignAuthConfigurationException`，请求**不会发出**。异常信息里列出当前已配置的服务清单（`名称 -> base-url`）与修正指引。
- `false`：记录 WARN 后**不带鉴权 Header 放行**。WARN 对同一个 baseUrl 只记一次（后续降级为 DEBUG），去重表最多记住 32 个 key（**常量**）。

无论取值如何，**命中了服务但 `auth` 缺失或 `auth.type` 非法时始终快速失败**——这类配置笔误不应静默放行未鉴权请求。

```yaml
feign-auth:
  fail-on-no-match: false   # 仅在迁移期/灰度期使用，未鉴权请求会被真实发出
```

### 5.3 服务键

| 配置键 | 类型 | 必填 | 含义 |
|---|---|---|---|
| `feign-auth.services.<name>.base-url` | String | 匹配所必需 | 服务基础 URL；Feign target 的 `scheme://host:port` 必须与之一致 |
| `feign-auth.services.<name>.auth` | 对象 | OAuth2 / API Key 均必需 | 鉴权配置，见 [5.4](#54-auth-键) |

**`base-url` 规则：**

- 比较的是 **authority**：`scheme` 与 `host` 忽略大小写，省略端口时补默认端口（`http`→80，`https`→443），因此 `https://api.x.com` 与 `HTTPS://API.X.COM:443` 等价。
- `base-url` 携带的路径（如 `https://api.x.com/v1`）作为**外层前缀**：请求路径落在其下时先剥离该段，再参与 `path-prefixes` 匹配。该路径段会做斜杠折叠与末尾斜杠清理。
- 支持的写法：`https://api.x.com`、`https://api.x.com/v1`、`https://api.x.com/v1/`、`http://localhost:8080`。
- 不可解析的写法（如缺少协议名的 `api.x.com`）**永远匹配不上**，请求期以 `no service config matched` 失败。
- 服务名建议只使用字母、数字、短横线（Map 键名）。

### 5.4 `auth` 键

这一层同时承载 OAuth2 与 API Key 的配置，未使用的字段会被忽略。

| 配置键 | 类型 | 默认值 | 适用 | 含义 |
|---|---|---|---|---|
| `type` | String | 无 | 通用 | `oauth2` 或 `api-key`，忽略大小写；缺失/拼错则请求期快速失败 |
| `token-url` | String | 无 | OAuth2 | 令牌端点 URL，**建议 HTTPS** |
| `method` | String | `post` | OAuth2 | 取号用的 HTTP 方法：`post` / `get`（大小写不敏感） |
| `client-authentication` | String | `auto` | OAuth2 | 凭证发送方式：`auto` / `basic` / `parameters` |
| `token-header` | String | `x-token` | OAuth2 | 业务请求中写入令牌的 Header 名 |
| `header-name` | String | `Authorization` | API Key | 业务请求中写入密钥的 Header 名 |
| `token-prefix` | String | 空 | 通用 | 拼接在 Header 值前面的前缀，如 `"Bearer "` |
| `value` | String | 无 | API Key | 固定密钥值 |
| `expire-ahead-seconds` | long | `60` | OAuth2 | 提前多少秒刷新令牌；必须 ≥ 0 |
| `token-field` | String | 无 | OAuth2 | 从令牌响应提取 access token 的点分路径 |
| `token-expires-in-seconds` | Long | 无 | OAuth2 | 显式指定令牌 TTL（秒）；设置时必须 > 0 |
| `expired-token-statuses` | List\<Integer\> | `[401, 421, 423]` | OAuth2 | 触发「令牌过期 → 驱逐 → 重放」的状态码 |
| `response-status-field` | String | 无 | OAuth2 | 当 HTTP 码不命中时，从响应体读取业务状态码的字段路径 |
| `path-prefixes` | List\<String\> | 空 | **仅 API Key** | 该 API Key 服务生效的路径前缀；为空即兜底 |
| `clients` | List | 空 | OAuth2 | 客户端凭证列表，至少一个 |
| `request-fields` | 对象 | 默认字段名 | OAuth2 | 自定义取号请求字段名，见 [5.6](#56-request-fields) |
| `request-headers` | Map | 空 | 通用 | 每个业务请求都会带上的静态 Header |
| `token-request-headers` | Map | 空 | OAuth2 | 每个取号请求都会带上的静态 Header；`Content-Type` 还决定请求体编码 |

**`type` 的取值**

- `oauth2`：取号并注入 token。
- `api-key`：直接注入 `value`。
- 其他值（`oauth`、`apikey`）或空白：请求命中该服务时抛 `FeignAuthConfigurationException`，**不受 `fail-on-no-match` 影响**。

**`path-prefixes` 的归属**

`auth.path-prefixes` **只对 `api-key` 生效**。OAuth2 的路径路由配在 `auth.clients[].path-prefixes` 上；写在 OAuth2 的 `auth.path-prefixes` 下不会生效。

**Header 名如何决定**

| 认证类型 | 使用的键 | 默认值 |
|---|---|---|
| OAuth2 | `token-header` | `x-token` |
| API Key | `header-name` | `Authorization` |

即：API Key 取 `header-name`（空白则 `Authorization`），其余取 `token-header`（空白则 `x-token`）。OAuth2 服务配 `header-name` **无效**。

**`token-prefix`**

对两种认证类型都生效，直接字符串拼接，不做 trim：

```yaml
token-prefix: "Bearer "     # 结果为 Authorization: Bearer eyJ...
```

含空格时务必加引号——YAML 不带引号会去掉末尾空格，前缀变成 `Bearer`，最终 Header 是 `BearereyJhbGc...`。

### 5.5 `auth.clients[]`

| 配置键 | 类型 | 必填 | 默认值 | 含义 |
|---|---|---|---|---|
| `id` | String | OAuth2 取号必需 | 无 | OAuth2 client id |
| `secret` | String | OAuth2 取号必需 | 无 | OAuth2 client secret |
| `grant-type` | String | 否 | `client_credentials` | 授权类型；是否真的发送见 [6.4](#64-取号参数与-grant-type) |
| `path-prefixes` | List\<String\> | 否 | 空 | 该 Client 生效的路径前缀；为空即默认 Client |

规则：

- 一个服务至少要有一个 Client，否则请求期抛 `No OAuth2 clients configured for service: <name>`。
- 有 `path-prefixes` 的 Client 按**最长前缀**竞争；长度相同且都命中时抛 `Multiple OAuth2 clients matched requestPath=...`。
- **没有 `path-prefixes` 的 Client 是默认 Client**，只在所有带前缀的 Client 都没命中时使用。
- 默认 Client 最多一个，配两个会抛 `Multiple default OAuth2 clients configured for service: <name>`。
- 每个 Client 独立缓存，缓存键是 `{服务名}:{clientId}`。

### 5.6 `request-fields`

用于对接使用非标准字段名的 IdP（如 `appId` / `appSecret`）。

| 配置键 | 类型 | 默认值 | 含义 |
|---|---|---|---|
| `client-id` | String | `client_id` | 请求中 client id 的字段名 |
| `client-secret` | String | `client_secret` | 请求中 client secret 的字段名 |
| `grant-type` | String | `grant_type` | 请求中 grant type 的字段名（仅在自定义字段名场景生效） |

字段名同时用于**剥离凭据**：走 Basic 认证时，starter 用同一份字段名口径从参数表里移除凭据，因此改名后两侧依然对齐。

### 5.7 `cache`

| 配置键 | 类型 | 默认值 | 含义 |
|---|---|---|---|
| `provider` | String | `local` | 缓存与锁的实现：`local` 或 `redis`（忽略大小写） |
| `redis.key-prefix` | String | `feign-auth:` | Redis 键前缀；不能为 `null` |

`feign-auth.cache.provider` 只接受 `local` 与 `redis`（**不区分大小写**，与 Spring `@ConditionalOnProperty` 的匹配口径一致）；拼错或配成空值时启动即抛 `FeignAuthConfigurationException` 并给出实际值。否则两组 Bean 的装配条件都不成立，报错只会指向「没有 RedisConnectionFactory」这一与根因无关的方向。

**Redis Key 格式**：

| 类型 | Key 格式 | 示例 |
|---|---|---|
| Token 缓存 | `{key-prefix}token:{serviceName}:{clientId}` | `feign-auth:token:order-service:my-client` |
| 分布式锁 | `{key-prefix}lock:{serviceName}:{clientId}` | `feign-auth:lock:order-service:my-client` |

存储与锁的实现细节见 [9.2](#92-缓存机制) 与 [9.3](#93-锁机制)。

### 5.8 `http`

只影响 starter 内置的**取号** HTTP 客户端，不影响业务 Feign 请求。

| 配置键 | 类型 | 默认值 | 含义 |
|---|---|---|---|
| `connect-timeout` | Duration | `5s` | 建立 TCP 连接的超时 |
| `connection-request-timeout` | Duration | `5s` | 从连接池借用连接的等待超时 |
| `socket-timeout` | Duration | `10s` | 读取响应的超时 |
| `max-total` | int | `200` | 连接池最大连接数 |
| `default-max-per-route` | int | `50` | 单路由最大连接数 |

- 三个超时都必须**严格大于 0**，且不能小于 1 毫秒（`0` / 负数 / 不足 1ms 都非法）。Apache HttpClient 把 `0` 解释为「无限等待」，starter 不做静默钳制，而是在启动期抛 `FeignAuthConfigurationException` 并点名具体配置键。
- `max-total` 与 `default-max-per-route` 必须 > 0，否则启动期失败。
- 内置客户端已关闭**自动重试**与 **3xx 重定向跟随**：连接中断/超时直接失败；IdP 返回 3xx 按非 2xx 抛异常，凭证不会随重定向发送到 `Location`。
- 该客户端是容器 Bean，上下文关闭时自动 `close()` 释放连接池。

### 5.9 校验时机矩阵

**启动期校验**（`FeignAuthProperties.afterPropertiesSet()`）：失败即抛 `FeignAuthConfigurationException`，消息以 `FeignAuth: invalid configuration <key>=<value>, ...` 开头。

| 校验项 | 规则 |
|---|---|
| `feign-auth.http.connect-timeout` | 非 null 且 ≥ 1ms |
| `feign-auth.http.connection-request-timeout` | 非 null 且 ≥ 1ms |
| `feign-auth.http.socket-timeout` | 非 null 且 ≥ 1ms |
| `feign-auth.http.max-total` | > 0 |
| `feign-auth.http.default-max-per-route` | > 0 |
| `feign-auth.cache.provider` | `local` / `redis`（忽略大小写） |
| `feign-auth.cache.redis.key-prefix` | 非 null |
| `feign-auth.services.<name>.auth.expire-ahead-seconds` | ≥ 0 |
| `feign-auth.services.<name>.auth.token-expires-in-seconds` | 若设置则 > 0 |
| `feign-auth.services.<name>.auth.expired-token-statuses` | 不含 null；未配 `response-status-field` 时每项必须在 100–599 |
| `feign-auth.services.<name>.auth.client-authentication` | `auto` / `basic` / `parameters`（忽略大小写） |

> `client-authentication` **不能为空**：写成 `client-authentication:`（无值）会绑定成 null，启动期按非法值拒绝。

**请求期才失败**（应用能启动，调用到才暴露）：`auth.type` 缺失/非法、`token-url` 为空、`clients` 为空、`clients[].id`/`secret` 为空、API Key 的 `value` 为空、`method` 非 get/post、`Content-Type` 不支持。

### 5.10 绑定约定

- 推荐 kebab-case（`token-url`、`expire-ahead-seconds`）；Spring Boot 的宽松绑定同时接受 `tokenUrl`、`TOKEN_URL` 等写法。
- `Duration` 支持 `5s`、`500ms`、`1m` 等写法。
- 环境变量：`feign-auth.cache.provider` 对应 `FEIGNAUTH_CACHE_PROVIDER`（Spring 映射时丢掉属性名中的短横线、把 `.` 换成 `_`）。写成 `FEIGN_AUTH_...` **不会**生效。
- IDE 中可自动补全——starter 打包了 `META-INF/additional-spring-configuration-metadata.json`。

---

## 6. 认证方式

### 6.1 OAuth2 取号流程

1. 业务请求进入拦截器，匹配到服务与 Client（缓存键 `{服务名}:{clientId}`）。
2. 查缓存：命中且未过期 → 直接注入。
3. 未命中 → 在锁内二次检查 → 仍未命中则发取号请求。
4. 解析响应，写入缓存（TTL 见 [6.5](#65-token-响应解析)），注入 Header。

取号请求由内置 Apache HttpClient 发出，路径为 `token-url`：

- `method: post`：参数放请求体，编码由 `token-request-headers` 的 `Content-Type` 决定。
- `method: get`：参数放查询串（`URIBuilder`）。

### 6.2 客户端认证方式（`client-authentication`）

`client_id` / `client_secret` 可以用两种方式发给 IdP：作为取号请求参数（GET 进查询串、POST 进请求体），或按 RFC 6749 §2.3.1 的 `client_secret_basic` 放进 `Authorization: Basic` 头。

| 取值 | `method: get` | `method: post` |
|---|---|---|
| `auto`（默认） | `Authorization: Basic base64(id:secret)` | 凭证作为请求参数（请求体） |
| `basic` | `Authorization: Basic ...` | `Authorization: Basic ...` |
| `parameters` | 凭证进查询串 | 凭证进请求体 |

**为什么默认是 `auto` 而不是无条件 Basic**：POST 用户占多数，且不少老 IdP 只认请求体里的 `client_secret`——`auto` 下 POST 的请求体与 1.18 完全一致（不发 `Authorization` 头）。真正会泄密的是 GET：查询串比请求体更容易留痕，IdP 与中间网关/负载均衡的访问日志、APM 与错误采集（不少会记录完整 URL）、Apache HttpClient 的 `httpclient.wire` / `headers` DEBUG 日志都会带上明文密钥，所以 GET 默认改走 Basic。

**实际请求样例**

```text
# method: get + auto（默认）
GET /oauth/token?grant_type=client_credentials
Authorization: Basic base64(<client_id>:<client_secret>)

# method: get + client-authentication: parameters
GET /oauth/token?grant_type=client_credentials&client_id=<客户端ID>&client_secret=<明文密钥>

# method: post + auto（默认）
POST /oauth/token
Content-Type: application/json
{"grant_type":"client_credentials","client_id":"<客户端ID>","client_secret":"<明文密钥>"}
```

**配套规则：**

- 走 Basic 时只摘掉凭据两个字段（字段名取自 `request-fields`，改名后同样生效），`grant_type` 等其他参数照常发送。
- 已经在 `token-request-headers` 或 `HeaderCustomizer` 里配置了 `Authorization` 时**以你的配置为准**：starter 不代发凭据（此时凭据也不会出现在参数里），只在 DEBUG 记一条说明。`post` + `auto` 时凭证仍按参数发送，你设置的 `Authorization` 会原样带到取号请求上。
- Basic 用 UTF-8 编码 `id:secret`（与请求体同口径）。`client_id` 含冒号导致无法构造 Basic 时，抛 `FeignAuthConfigurationException` 并点名该服务，提示改 `client_id` 或退回 `client-authentication: parameters`。
- 显式选择 `parameters` 且 `method: get` 时，凭据会重新回到 URL 中：starter 不拦阻，但按服务记一次 WARN 说明泄露面，便于定位配置来源。

### 6.3 令牌请求体编码

POST 取号的请求体编码由 `token-request-headers` 中的 `Content-Type` 决定（未配置默认 `application/json`）：

| `Content-Type` | 请求体 |
|---|---|
| `application/json`、`text/json`、`*/*+json` 等 JSON 族 | JSON 对象（用容器的 `ObjectMapper`） |
| `application/x-www-form-urlencoded` | UTF-8 表单键值对（RFC 6749 的 token 端点惯例） |
| `application/json;charset=GBK` | JSON，按声明字符集编码 |
| 无法解析的值 | 首次取号时抛 `FeignAuthConfigurationException: Invalid Content-Type in token-request-headers: '<值>'` |
| 既非 JSON 族也非表单 | 首次取号时抛 `FeignAuthConfigurationException: Unsupported OAuth2 token request Content-Type: '<值>'` |

后两种**不会发出头体错配的请求**——请求根本不会发出。

> `Content-Type` 可以来自 YAML，也可以由 `HeaderCustomizer` 的取号请求分支设置；两者最后都会被读取。

### 6.4 取号参数与 `grant-type`

默认字段名：`client_id`、`client_secret`、`grant_type`（值为 `clients[].grant-type`，缺省 `client_credentials`）。

`grant_type` 是否发送由**是否自定义了字段名**与**是否两个值都配了**共同决定：

| `request-fields.client-id` / `client-secret` | `request-fields.grant-type` | `clients[].grant-type` | 实际行为 |
|---|---|---|---|
| 均未配置 | 任意 | 任意（不填即 `client_credentials`） | 用固定字段名 `grant_type` 发送 |
| 至少配置一个 | 已配置 | 已配置 | 用 `request-fields.grant-type` 这个字段名发送其值 |
| 至少配置一个 | 已配置 | 未配置 | **不发送** grant-type 参数 |
| 至少配置一个 | 未配置 | 任意 | **不发送** grant-type 参数 |

即：**一旦自定义了 client-id / client-secret 字段名，`grant-type` 就完全由你显式给出，不会自动补 `client_credentials`，也不会回落到 `grant_type` 这个名字。**

参数值来自 `clients[].id` / `clients[].secret`，均为配置原文。

### 6.5 Token 响应解析

**Token 字段**

- 未配置 `token-field`：依次尝试 `access_token` → `accessToken` → `token`，都取不到则报 `Token field not found in response`。
- 配置了 `token-field`：按点分路径逐段下钻，如 `data.accessToken`。
  - 路径**没有**层数上限（与 `expires_in` 的三层搜索不同）。
  - 中间段是标量/数组、叶节点缺失/是对象/是空白串时，统一报 `Token field '<配置原值>' not found or blank in response`。
  - `data[0].token` 这类数组下标写法不支持。
  - 查找时会 `trim()`，但异常文案保留配置原值。
  - 显式配置了 `token-field` 却没命中时**不会**回退到默认字段，避免静默拿到另一个令牌。
  - 叶节点是数字时按文本取用；叶节点是 `null` 或布尔值时的行为未做约定。

**过期时间**

- 优先使用 `token-expires-in-seconds`（> 0）。
- 否则从响应 JSON 中逐层查找（最多 3 层）`expires_in` → `expiresIn` → `expire_in` → `expireIn`。
- 都找不到时默认 **7200 秒**（**常量**）。
- 生效 TTL 计算：`refreshIn = max(1, expiresIn - min(expireAheadSeconds, expiresIn - 1))`，保证至少 1 秒，避免 Redis `SETEX` 非法与缓存立即过期。
- TTL 上限 **86400 秒（1 天）**（**常量**）：超长有效期（含把毫秒当秒返回的错误值）一律按 1 天缓存并每日刷新，避免时间戳溢出导致每请求刷新。

**响应体大小与字符集**

- 读取上限 **64 KiB**（**常量**）：只按实际读到的字节累积，**不按 `Content-Length` 预分配**，因此超大错误页或被伪装的超长长度头都无法把堆占用推高到该值以上。
- 2xx 却超过上限：抛 `FeignAuthTokenException: Token response exceeds 65536 bytes, cannot be a valid token response`。
- 非 2xx 超过上限：截断后照常解析，异常信息里带正文头部片段。
- 字符集优先取响应 `Content-Type` 声明的 charset，未声明按 UTF-8。

**非 2xx 响应**

抛 `FeignAuthTokenException`，消息形如：

```text
Token request failed: 401, error=invalid_client, error_description=client_secret is expired
Token request failed: 502, body=<html>gateway blew up</html>
Token request failed: 403
```

正文超过 512 字符会被截断后再拼进消息（**常量**）。

### 6.6 凭据与传输安全

**`token-url` 请使用 HTTPS**。配成明文 `http://` 时，`client_secret` 与返回的 access token 都明文过网，同网段中间人可直接取走；客户端凭证一旦泄露，等价于该 Client 的全部权限。starter 会在首次取号时按服务记一次 WARN（同一服务的多个 client 共用一个 `token-url`，因此每服务实际只有一条），回环地址（`localhost`、`127.*`、`::1`）视为本地开发用法不提示。生产环境仍须自行确认 `token-url` 为 HTTPS，且未放宽证书校验——WARN 只是提醒，starter 不会拒发请求。

> **已知边界**：`token-url` 缺少协议名时，错误形态取决于写法（首段是否含冒号）——
> - 写成 `127.0.0.1:8080/oauth/token`（首段含冒号）：URI 解析直接失败，GET/POST 都在请求期抛 `FeignAuthConfigurationException: Invalid token-url: <原值>`；
> - 写成 `api.example.com/oauth/token`（首段不含冒号）或 `ftp://api.example.com/...`：URI 能解析但不是可用的 HTTP 地址，失败发生在连接层，抛 `FeignAuthTokenException: Token request failed: null`，消息里不含配置键。
>
> 排障时先核对 URL 写法。

### 6.7 API Key

```yaml
feign-auth:
  services:
    console:
      base-url: https://api.console.com
      auth:
        type: api-key
        value: sk-xxxxxxxx
        header-name: Authorization
        token-prefix: "Bearer "
        path-prefixes:
          - /api/orders
```

- 请求命中时直接注入 `header-name: token-prefix + value`。
- `value` 为空白时请求期抛 `FeignAuthConfigurationException: API key value is required for service: <name>`。
- `path-prefixes` 为空 → 该服务是其 base-url 下的**兜底**；非空 → 只在这些前缀下生效。
- API Key 不涉及取号、缓存、锁、过期判定；相关配置对它无意义。

---

## 7. 路由与匹配

### 7.1 匹配算法

匹配分四步，在**每个业务请求**上执行一次：

1. **按 authority 过滤**——把 Feign target 的 URL 与每个服务的 `base-url` 各自归一化成 `scheme://host:port`：
   - scheme / host 忽略大小写；
   - 省略端口补默认端口（`http`→80，`https`→443）；
   - 路径不参与这一步比较。
2. **剥掉 `base-url` 的路径前缀**——若配置的 `base-url` 带路径（`https://api.x.com/v1`），请求路径以 `/v1` 开头时先剥掉它，再用剩余路径匹配 `path-prefixes`。请求路径恰好等于该前缀时，剩余路径按 `/` 处理。
3. **最长前缀优先**——在 authority 相同的候选中，计算每个服务/Client 的 `path-prefixes` 命中长度，取最长者。
4. **兜底**——没有任何前缀命中时使用兜底配置：
   - API Key：`auth.path-prefixes` 为空；
   - OAuth2：`clients` 中存在未配置 `path-prefixes` 的 Client。

**前缀按路径段边界匹配**：`/api/measure` 命中 `/api/measure/1`，**不**命中 `/api/measurement/1`。前缀末尾的 `/` 会被忽略。

**歧义处理**

| 情况 | 行为 |
|---|---|
| 命中长度相同且来自不同服务 | 抛 `Multiple services matched requestPath=... for baseUrl=...` |
| 同一服务内命中长度相同且来自不同 Client | 抛 `Multiple OAuth2 clients matched requestPath=... for service=...` |
| 同一 base-url 下有多个兜底 | 抛 `Multiple fallback services configured for baseUrl=...` |

### 7.2 服务级路由

同一 host 下按路径前缀区分多个服务配置：

```yaml
feign-auth:
  services:
    order-api:
      base-url: https://gateway.example.com/v1/orders
      auth:
        type: oauth2
        token-url: https://gateway.example.com/oauth/token
        clients: [{ id: order-client, secret: order-secret }]

    user-api:
      base-url: https://gateway.example.com/v1/users
      auth:
        type: oauth2
        token-url: https://gateway.example.com/oauth/token
        token-header: Authorization
        token-prefix: "Bearer "
        clients: [{ id: user-client, secret: user-secret }]
```

target 分别是 `.../v1/orders` 与 `.../v1/users`；匹配时先剥掉配置的路径段，再做前缀/兜底判定。

> **拆服务还是拆 Client？** 同一个服务下不同请求路径需要不同 token 时，用**客户端级路由**（[7.3](#73-客户端级路由)）更合适；只有需要不同 `token-url`、不同 `token-header` 等整体配置差异时才拆成多个服务。注意同一 base-url 只能有一个兜底。

### 7.3 客户端级路由

同一服务内按路径前缀路由到不同 OAuth2 凭证：

```yaml
feign-auth:
  services:
    measure:
      base-url: https://api.service-a.com
      auth:
        type: oauth2
        token-url: https://api.service-a.com/oauth/token
        token-prefix: "Bearer "
        clients:
          - id: event-client
            secret: event-secret
            path-prefixes: [/api/measure/event, /api/measure/event2]
          - id: telemetry-client
            secret: telemetry-secret
            path-prefixes: [/api/measure/telemetry]
          - id: default-client          # 没有 path-prefixes → 默认凭证
            secret: default-secret
```

| 请求路径 | 命中的 Client |
|---|---|
| `/api/measure/event/1` | `event-client` |
| `/api/measure/telemetry/x` | `telemetry-client` |
| `/api/measure/other` | `default-client` |

每个 Client 独立缓存，缓存键 `{服务名}:{clientId}`。

### 7.4 路径归一化口径

参与匹配的请求路径统一由 `PathUtils.normalizeRequestPath` 归一化：

- 补全开头的 `/`；
- 去掉查询串与 `#` 片段；
- 折叠连续斜杠（`//` → `/`）；
- 去掉末尾斜杠（根路径 `/` 保留）。

鉴权头注入、过期令牌驱逐、Client 的 `path-prefixes` 匹配，以及配置 `base-url` 中作为外层前缀的那段路径，都走这一口径。因此 `base-url: https://api.x.com/api//`、`https://api.x.com/api//v1` 这类写法，或 `{}` 变量展开出的多余斜杠，不会出现「头注入了 A Client 的令牌、驱逐时却去找另一个 Client」的错配。

**例外：**`auth.path-prefixes` 与 `clients[].path-prefixes` 里的前缀本身只做 `normalizePath`（补 `/`、去查询串），**不折叠 `//`**——把前缀写成 `/api//measure` 匹配不上已折叠的请求路径。

### 7.5 未匹配与非法配置

| 情况 | 行为 |
|---|---|
| 没有任何服务的 authority 匹配 | `fail-on-no-match=true` 抛异常；`false` 记 WARN 后放行 |
| authority 匹配但**所有**候选的 `auth` 缺失或 `auth.type` 非法 | 始终抛异常，并在消息里列出所有非法服务 |
| authority 匹配但**部分**候选 `auth` 非法 | 非法项被跳过，其余正常参与匹配（不报错） |
| authority 匹配但没有任何前缀/兜底命中 | 视同「未匹配」，按 `fail-on-no-match` 处理 |

> **服务发现场景**：`@FeignClient` 用 `name` 走负载均衡、没有 `url` 时，target 可能是 `http://服务名`，与配置的 `https://真实域名` authority 不同，通常匹配不上。需要显式配置 `url = "${feign-auth.services.<name>.base-url}"`；或在迁移期临时放开 `fail-on-no-match`（但那样请求不带鉴权）。

---

## 8. Header 注入

支持两种方式：YAML 配置固定 Header，或实现 `HeaderCustomizer` 接口动态注入。

> **本节是可选能力。** 只要认证本身配好了（OAuth2 的 `token-url` + `clients`，或 API Key 的 `value`），下面这些一项都不配，请求照样带上认证 Header 正常发出：
>
> - `request-headers` / `token-request-headers`：默认都是空 Map。
> - `HeaderCustomizer`：容器里没有该 Bean 时不写入任何 Header。
> - 认证 Header 的名字也有默认值（OAuth2 取 `token-header`，空白则 `x-token`；API Key 取 `header-name`，空白则 `Authorization`），只有对端要求别的名字时才需要显式配置。

### 8.1 注入管线与顺序

**业务请求**（`HeaderManager.applyRequestHeaders`）：

1. **静态** `auth.request-headers`：按 Map 写入；名称为空白或值为 `null` 的条目会被跳过。
2. **动态** `HeaderCustomizer`：按 Bean 顺序依次调用 `supports` 为 `true` 的定制器。

**取号请求**（`HeaderManager.buildTokenHeaders`）：

1. 静态 `auth.token-request-headers`；
2. 动态 `HeaderCustomizer#customize(TokenRequestContext, HttpHeaders)`。

> **⚠️ 写入次序：认证 Header 先写，`request-headers` 与定制器后写。** 最终合并进 `RequestTemplate` 时采用「先清除同名 Header 再写入」，所以如果静态 `request-headers` 的键与 `token-header`（OAuth2）或 `header-name`（API Key）同名，**静态值会覆盖认证值**，请求将以静态值发出。这两处不要使用同名键。

`request-headers` 与 `token-request-headers` 各管各的，**不会**自动互相带过去，需要两边都带就分别写两份。

> Header 本身不会重复叠加：同名 header 在合并进 `RequestTemplate` 前会先清除旧值。

### 8.2 静态固定 Header

直接在 `auth` 下配置 `request-headers`（业务请求）和 `token-request-headers`（Token 请求）：

```yaml
feign-auth:
  services:
    order:
      base-url: https://api.example.com
      auth:
        type: oauth2
        token-url: https://api.example.com/oauth/token
        request-headers:
          X-App-Id: order-portal
          X-Version: v2
        token-request-headers:
          X-App-Id: order-portal
          Content-Type: application/x-www-form-urlencoded
        clients:
          - id: my-client
            secret: my-secret
```

- `request-headers`：只作用于业务请求。
- `token-request-headers`：只作用于取号请求；其中 `Content-Type` 决定 POST 请求体编码（见 [6.3](#63-令牌请求体编码)）。

### 8.3 动态 Header（`HeaderCustomizer` 接口）

实现 `HeaderCustomizer` 并注册为 Spring Bean，可获取请求上下文做签名、Trace 等动态注入。

**接口定义**

```java
public interface HeaderCustomizer {

    boolean supports(String serviceName, FeignAuthProperties.Service service);

    /** 业务请求：ctx 提供 requestPath、queryParams、method、body、template */
    default void customize(RequestContext ctx, HttpHeaders headers) {}

    /** Token 请求：ctx 提供 client、parameters */
    default void customize(TokenRequestContext ctx, HttpHeaders headers) {}
}
```

**`RequestContext` 提供：**

| 方法 | 含义 |
|---|---|
| `getServiceName()` | 逻辑服务名 |
| `getService()` | 该服务的完整配置对象 |
| `getRequestPath()` | 归一化后的请求路径 |
| `getMethod()` | HTTP 方法 |
| `getBody()` | 请求体字节（可能为 null） |
| `getQueryParams()` | 查询参数 Map |
| `getTemplate()` | 完整 `RequestTemplate`，可读取任意内容 |

**`TokenRequestContext` 提供：**

| 方法 | 含义 |
|---|---|
| `getServiceName()` | 逻辑服务名 |
| `getService()` | 该服务的完整配置对象 |
| `getClient()` | 正在为其申请令牌的 Client |
| `getParameters()` | 取号参数（只读） |

**行为约定：**

- 多个定制器按 Spring Bean 自然顺序调用，用 `@Order` 控制：

  ```java
  @Component @Order(1)
  public class TracingCustomizer implements HeaderCustomizer { ... }

  @Component @Order(2)
  public class SignatureCustomizer implements HeaderCustomizer { ... }
  ```

- 同一个定制器可以只覆写需要的那一个重载：业务请求走 `customize(RequestContext, HttpHeaders)`，取号请求走 `customize(TokenRequestContext, HttpHeaders)`。
- `supports` 在取号请求前也会被调用。
- 定制器抛异常时会先记一条 ERROR（含定制器类名与服务名），再把异常原样抛出，请求失败。
- **重试会重复执行定制器**：Feign 重试时拦截器会再跑一遍，HMAC 时间戳 / nonce 会被消耗两次。定制器应保持无副作用。

### 8.4 示例一：为指定服务注入 HMAC 签名

```java
@Component
public class OrderSignatureCustomizer implements HeaderCustomizer {

    private final HmacSigner signer;

    public OrderSignatureCustomizer(HmacSigner signer) {
        this.signer = signer;
    }

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

### 8.5 示例二：为所有服务透传 Trace ID

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

### 8.6 示例三：Token 请求注入额外 Header

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

---

## 9. 令牌缓存、并发与过期重放

### 9.1 缓存键

`{服务名}:{clientId}`。本地缓存与 Redis 都以此为基础；Redis 额外加前缀与 `token:`：

```text
本地：order:my-client
Redis：feign-auth:token:order:my-client
```

因此：

- 同一服务不同 Client 互不干扰；
- 不同服务即使 clientId 相同也互不干扰；
- 改了服务名或 clientId 等于换了一份缓存，旧条目会按 TTL 自然过期。

### 9.2 缓存机制

支持两种缓存实现，通过配置切换：

```yaml
feign-auth:
  cache:
    provider: local  # local | redis，默认 local
    redis:
      key-prefix: feign-auth:
```

| Provider | 实现方式 | 适用场景 |
|---|---|---|
| `local` | ConcurrentHashMap + 懒清理 | 单实例部署 |
| `redis` | Redis + TTL | 多实例部署，共享 Token |

**本地缓存特点：**

- 使用 `ConcurrentHashMap` 存储，线程安全
- 访问过期 Token 时自动清理（懒清理策略），无后台线程
- 无需额外依赖

**Redis 缓存特点：**

- 利用 Redis TTL 自动过期机制，多实例共享 Token，避免重复获取
- 使用 `provider: redis` 需自行声明 `org.springframework.boot:spring-boot-starter-data-redis`（starter 对它的依赖是 `optional`，不会传递进来），再配置 Redis 连接（`spring.redis.*` 等）使容器中存在 `RedisConnectionFactory`；只用 `provider: local` 时无需任何 Redis 依赖，classpath 上也不会出现 Spring Data Redis
- 容器内**没有** `RedisConnectionFactory` 时（依赖被排除、`RedisAutoConfiguration` 被 exclude 等），启动即抛 `FeignAuthConfigurationException` 并在信息里点名 `feign-auth.cache.provider=redis` 与两种修法（补 Redis 配置或改回 `local`），不再是一句看不出根因的 `NoSuchBeanDefinitionException: TokenStore`
- 存储格式：键为纯 String（见 [5.7](#57-cache) 的 Key 格式表），值为**固定类型的 JSON**（只含 `accessToken` / `ttl` / `expireAt` 三个字段，不带任何类型元数据），由 starter 自建的 `RedisTemplate`（`StringRedisSerializer` 键 + 令牌专用 JSON 序列化器值）读写，**不借用**容器中已有的 `RedisTemplate` / `StringRedisTemplate` 及其序列化器，因此使用方或其他组件的序列化配置不会破坏令牌读写
- 令牌条目不写 `@class` 之类的类型信息，读取时也**不以值里的类名决定实例化哪个类**：即使 Redis 被第三方写入（共享实例、弱口令、与其他业务共库等），恶意载荷也只会被拒绝并清理，不会被实例化；早期版本写入的带 `@class` 条目仍可正常读出，无需清缓存
- 读取到无法反序列化的值或异常类型（旧格式残留、人为改写等）会 WARN 后删除该键，并在下次取号时按当前格式重建
- 排障时可直接 `GET feign-auth:token:{serviceName}:{clientId}` 查看内容。注意该 JSON 内含**令牌明文**，Redis 实例需按敏感数据要求做访问控制与持久化策略
- 若使用方同时自备 `TokenStore` 与 `LockProvider`，Redis 连接是否存在都不受影响；只自备其一时，另一侧仍按上述规则快速失败。使用方自备 `TokenFetcher` 时本节的两条校验都不参与——此时 starter 的缓存装配整体让位，容器里没有任何组件消费 `TokenStore` / `LockProvider`

### 9.3 锁机制

锁机制与缓存 provider 联动，确保同一 Token 不会被并发请求重复获取：

| Provider | 锁实现 | 说明 |
|---|---|---|
| `local` | 定长分段锁（64 个 ReentrantLock，按 key 哈希取模） | 单实例内互斥，无锁对象清理需求 |
| `redis` | Redis SETNX + Lua 脚本 | 分布式互斥，防止多实例重复请求 |

**本地锁特点：**

- 固定 64 个锁分段（**常量**），无动态锁映射表，不存在清理竞态
- 不同 key 哈希到同一分段时会共享锁，仅影响并发度，不影响正确性

**Redis 锁参数**（内置默认值）：

- 锁超时时间：30 秒
- 重试间隔：200 毫秒
- 最大重试次数：60 次（约 12 秒），仍失败抛 `Failed to acquire lock for key: <key>`
- 解锁采用 Lua 脚本原子操作（比对持有者标识后再 `DEL`），避免并发竞态、不会误删他人的锁
- 仅在成功获取锁后执行解锁；解锁失败只记 WARN，锁会随 TTL 过期

### 9.4 取号的「双重检查」

`TokenFetcher.getCachedOrFetch` 的流程：

1. 锁外读一次缓存（大多数请求走这里，命中即返回）；
2. 未命中 → 获取锁 → 再读一次（避免并发重复取号）；
3. 仍未命中 → 取号 → 写缓存。

这保证同一 `{服务名}:{clientId}` 在单实例内最多一个线程真正取号（Redis 模式下多实例也互斥）。

### 9.5 过期令牌的识别

远端响应满足以下任一条件时，starter 认定缓存的令牌已过期：

1. **HTTP 状态码**命中 `expired-token-statuses`（默认 `[401, 421, 423]`）；
2. HTTP 状态码**不**命中，但服务是 OAuth2 且配置了 `response-status-field`，响应体该字段的值命中列表。

第 2 条用于「HTTP 码不规范、真实原因写在响应体里」的接口，例如：

```http
HTTP/1.1 400 Bad Request
Content-Type: application/json

{"code":401,"msg":"token expired"}
```

```yaml
auth:
  response-status-field: code
  expired-token-statuses: [401, 10001, -1]
```

规则：

- `response-status-field` 支持 `data.code` 这样的点分路径；只支持对象字段，不支持数组下标。
- 值必须是数字或数字字符串，才能与 `expired-token-statuses` 比较。
- 它只在**非 2xx** 响应上生效——`ErrorDecoder` 本来就不会处理 2xx。因此「返回 HTTP 200、却在响应体里报 token 过期」的接口无法通过它识别。
- `expired-token-statuses` 一旦配置就**完全替换**默认值，不会与 `[401, 421, 423]` 合并。留空（`[]`）时默认仍然生效。
- 因为该列表同时用于匹配 HTTP 码与业务码，配置了 `response-status-field` 时允许填写业务状态码（如 `10001`、`-1`）；未配置时启动校验要求取值落在 100–599 之间，以拦截 `4010` 这类笔误。

### 9.6 驱逐、冷却与重放

判定过期后：

1. 调用 `invalidateToken` 删除缓存；
2. 删除成功 → 抛 `RetryableException`，Feign 的 `Retryer` 重放请求，重新取号并注入新令牌；
3. 删除失败（冷却期内或缓存已空）→ 抛不可重试的 `FeignAuthTokenException`，消息含 `token eviction skipped (cooldown or cache empty), request not retried`。

同一 `{服务名}:{clientId}` 的驱逐有 **30 秒冷却**（**常量**，硬编码）：

- 冷却自「确实删掉了缓存令牌」起算；缓存早已被自身 TTL 淘汰干净、本次什么都没删的空转**不**写入冷却时间戳（否则会把随后真正能删掉的令牌一并锁死 30 秒）。
- 冷却期内的重复过期响应不再清缓存、不再重放，直接抛 `FeignAuthTokenException`。
- 目的：远端持续 401 时不形成「401 → 刷新 → 401」风暴轰击 IdP。
- 冷却期内的 WARN：`FeignAuth [oauth2] token eviction suppressed within cooldown for service='...', clientId='...'`。

### 9.7 重试与请求重放

starter 默认注册一个 Feign `Retryer`（最多 2 次尝试，退避 100ms ~ 1s），以下两种情况会**重放整个请求**：

1. **Token 过期**：收到 401 / 421 / 423（或 `expired-token-statuses` 配置的状态码）时，starter 驱逐缓存 token 并抛出 `RetryableException`，Feign 携带新 token 重发一次。
2. **网络类错误**：连接/读取超时等 I/O 异常同样会被默认 Retryer 重试。

重放带来的两个约束需要使用方知晓：

- **非幂等接口**：POST 创建订单等请求若在服务端已实际处理、但响应未按预期返回（超时、401 歧义），重放会造成重复提交。建议为非幂等接口设计幂等键。
- **HeaderCustomizer 副作用**：每次重试都会重新执行 `RequestInterceptor`，因此 `HeaderCustomizer` 每次尝试都会再跑一遍——HMAC 时间戳、nonce、签名计数器会被消耗两次。定制器应保持无副作用（纯读取、纯计算）；若必须消费一次性凭据，请关闭自动重放或让凭据服务对同一请求幂等。

### 9.8 关闭或自定义重放

默认 Retryer 带 `@ConditionalOnMissingBean(Retryer.class)`，只要在该客户端的子上下文中先行注册自己的 `Retryer` Bean，starter 就自动让位：

```java
@Configuration
public class NoReplayConfig {
    @Bean
    public Retryer noReplayRetryer() {
        return Retryer.NEVER_RETRY;
    }
}

@FeignClient(name = "orders", url = "${feign-auth.services.orders.base-url}",
    configuration = {NoReplayConfig.class, FeignClientConfig.class})
public interface OrdersClient { ... }
```

`Retryer.NEVER_RETRY` 会一并关掉 Token 过期后的那次重放：驱逐仍会发生（缓存被清、下次取号拿到新令牌），但当前请求直接把异常抛给业务方。

若只想控制次数，用 `new Retryer.Default(100, TimeUnit.SECONDS.toMillis(1), n)`，其中 `n` 是含首次调用在内的总尝试数（starter 默认 `n = 2`；`n = 1` 即不重放）。注意无参的 `new Retryer.Default()` 是 `n = 5`，不能用来关闭重放。

`configuration` 数组中自定义配置要排在 `FeignClientConfig` 之前；若顺序无法保证，可给自定义 Retryer 加 `@Primary`。

---

## 10. 完整示例配置

一个同时包含多服务、多客户端凭证、多认证方式、多域名的完整配置：

```yaml
feign-auth:
  # —— 全局 ——
  fail-on-no-match: true
  cache:
    provider: local
  http:
    connect-timeout: 5s
    connection-request-timeout: 5s
    socket-timeout: 10s
    max-total: 200
    default-max-per-route: 50

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
            grant-type: client_credentials
            path-prefixes: [/api/measure/event, /api/measure/event2]
          - id: telemetry-client
            secret: telemetry-secret
            grant-type: client_credentials
            path-prefixes: [/api/measure/telemetry]
          - id: default-client             # 默认 Client（兜底）
            secret: default-secret
            grant-type: client_credentials

    # OAuth2 — GET 方式获取 Token，自定义字段名
    ast:
      base-url: https://api.service-b.com
      auth:
        type: oauth2
        token-url: https://api.service-b.com/oauth/token
        method: get                          # 凭据默认走 Authorization: Basic，不进查询串
        # client-authentication: parameters  # IdP 只读 query 参数时才需要（会 WARN 提示凭据在 URL 中）
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
          - id: client-default
            secret: secret-default
            path-prefixes: [/api/orders]

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

## 11. 场景配方

> 以下均使用 2.0.0 的 `feign-auth` 根键。示例省略了 `@FeignClient` 的 `name` 与方法，只保留关键部分。

### 11.1 IdP 协议适配

**老 IdP 只认查询参数**（IdP 不支持 Basic）：

```yaml
auth:
  type: oauth2
  token-url: http://legacy-idp.internal/oauth/token
  method: get
  client-authentication: parameters    # starter 会记一条 WARN 提示凭据在 URL 中
  clients:
    - id: legacy-id
      secret: legacy-secret
```

**表单编码的 token 端点**（RFC 6749 惯例）：

```yaml
auth:
  type: oauth2
  token-url: https://api.example.com/oauth/token
  token-request-headers:
    Content-Type: application/x-www-form-urlencoded
  clients:
    - id: my-client
      secret: my-secret
```

**令牌在嵌套字段 + 显式 TTL**（响应形如 `{"code":0,"data":{"accessToken":"...","expires":7200}}`）：

```yaml
auth:
  type: oauth2
  token-url: https://api.example.com/auth/token
  token-field: data.accessToken
  token-expires-in-seconds: 7200    # 不依赖响应里的 expires
  expire-ahead-seconds: 300         # 提前 5 分钟刷新
  clients:
    - id: psr-client
      secret: psr-secret
```

**自定义取号字段名**（`appId` / `appSecret`）：

```yaml
auth:
  type: oauth2
  token-url: https://api.example.com/oauth/token
  request-fields:
    client-id: appId
    client-secret: appSecret
    grant-type: grantType
  clients:
    - id: my-app-id
      secret: my-app-secret
      grant-type: client_credentials
```

请求体变为 `{"appId":"...","appSecret":"...","grantType":"client_credentials"}`。若省略 `request-fields.grant-type` 或 `clients[].grant-type`，则**不发送**该参数（见 [6.4](#64-取号参数与-grant-type)）。

**用业务状态码识别过期令牌**：

```yaml
auth:
  type: oauth2
  token-url: https://api.example.com/oauth/token
  response-status-field: code
  expired-token-statuses: [401, 10001, -1]
  clients: [{ id: c, secret: s }]
```

响应 `400 {"code":10001,"msg":"token expired"}` → 驱逐并重放。

**自定义过期状态码**（某些网关用 `419` 或 `403` 表示令牌失效）：

```yaml
auth:
  expired-token-statuses: [401, 403]
```

一旦配置了列表，默认的 `[401, 421, 423]` 不再生效，需自行补全；未配置 `response-status-field` 时列表元素必须在 100–599 之间。

### 11.2 路由

**同 host 多个 API Key + 兜底**：

```yaml
feign-auth:
  services:
    console-orders:
      base-url: https://api.console.com
      auth:
        type: api-key
        value: sk-orders
        path-prefixes: [/api/orders]

    console-default:
      base-url: https://api.console.com
      auth:
        type: api-key
        value: sk-default        # 没有 path-prefixes → 兜底
```

**同一个 host 混合认证方式**（`/public/**` 用 API Key，其余用 OAuth2）：

```yaml
feign-auth:
  services:
    partner-public:
      base-url: https://api.partner.com
      auth:
        type: api-key
        value: sk-public
        path-prefixes: [/public]

    partner-api:
      base-url: https://api.partner.com
      auth:
        type: oauth2
        token-url: https://api.partner.com/oauth/token
        clients:
          - id: partner-client
            secret: partner-secret
```

两种认证类型的服务是**同一组候选**，一起按「最长前缀 → 兜底」竞争。上面 OAuth2 服务靠「无前缀的默认凭证」成为兜底项，API Key 服务靠 `/public` 前缀参与竞争，两者不冲突。但如果 API Key 服务也没有 `path-prefixes`，同一 host 就会出现两个兜底，直接抛 `Multiple fallback services configured for baseUrl=...`。因此**同一 host 只能有一个兜底**。

**同一服务不同接口用不同 Header 名**：`token-header` 是服务级配置，同一服务下所有请求共用。若确实需要同一 token 写入不同 Header：

- 用 `HeaderCustomizer` 在业务请求上补一个副本头（例如同时写 `x-token` 与 `X-Auth-Token`）；
- 或拆成两个服务配置：同一 base-url、**各自带路径前缀的 Client 且都不设默认 Client**，两组前缀互不重叠。注意不要给两边都配默认 Client（同一 host 只能有一个兜底）。

### 11.3 部署形态

**Redis 多实例共享令牌**：

```yaml
feign-auth:
  cache:
    provider: redis
    redis:
      key-prefix: "myapp:auth:"
  services: ...
```

```yaml
spring:
  redis:
    host: redis.internal
    port: 6379
    password: ${REDIS_PASSWORD}
```

并确保 POM 中显式声明了 `spring-boot-starter-data-redis`。多实例下同一个 `{服务名}:{clientId}` 只会有一个实例真正取号（Redis 锁），其余实例读到同一份令牌。

**本地开发使用 HTTP 回环地址**：

```yaml
auth:
  token-url: http://localhost:8080/oauth/token
```

回环地址（`localhost`、`127.*`、`::1`）不会触发非 HTTPS 的 WARN；仍建议生产使用 HTTPS。

**迁移期允许未匹配请求放行**：

```yaml
feign-auth:
  fail-on-no-match: false
```

仅建议在灰度/迁移期使用：未匹配的请求会**不带任何鉴权头**发出，日志每个 baseUrl 只提醒一次。

### 11.4 定制

**替换缓存实现**（自定义 `TokenStore` + `LockProvider`）：

```java
@Configuration
public class AuthStorageConfig {

    @Bean
    public TokenStore tokenStore() {
        return new MySharedTokenStore();       // 例如公司统一缓存中间件
    }

    @Bean
    public LockProvider lockProvider() {
        return new MyLockProvider();
    }
}
```

两个 Bean 同时提供时，`cache.provider=redis` 也不再需要 `RedisConnectionFactory`。

**完全接管取号**：

```java
@Bean
public TokenFetcher tokenFetcher(FeignAuthProperties properties,
                                 OAuth2ClientMatcher clientMatcher,
                                 TokenStore tokenStore,
                                 LockProvider lockProvider) {
    return new MyTokenFetcher(properties, clientMatcher, new MyTokenClient(), tokenStore, lockProvider);
}
```

**只调整重试次数**（总共 3 次尝试：首次 + 2 次重试）：

```java
@Bean
public Retryer threeAttemptsRetryer() {
    return new Retryer.Default(100, TimeUnit.SECONDS.toMillis(1), 3);
}
```

---

## 12. 扩展点

所有默认 Bean 都带 `@ConditionalOnMissingBean`，可以用同类型 Bean 替换。

| 扩展点 | 类型 | 替换后 |
|---|---|---|
| `HeaderCustomizer` | 接口 | 追加到内置管线，不替换（多个定制器按 `@Order` 依次执行） |
| `TokenStore` | 接口 | 本地/Redis 实现让位 |
| `LockProvider` | 接口 | 本地/Redis 实现让位 |
| `TokenFetcher` | 类 | 整个取号装配让位：不再注册内置取号客户端，也不触发 Redis 连接校验 |
| `ObjectMapper` | 类 | 应用里已有的（Spring Boot 的 Jackson 自动配置）优先，用于令牌响应解析与 JSON 请求体序列化 |
| `OAuth2TokenRequestClient` | 类 | 内置 Apache HttpClient 让位 |
| `ServiceMatcher` / `OAuth2ClientMatcher` / `HeaderManager` | 类 | 可替换，用于自定义匹配策略或完全接管 Header 管线 |

**替换 `TokenStore` 与 `LockProvider` 时建议两者一起替换**：只替换其一时，另一侧仍按 `cache.provider` 装配，`provider: redis` 下仍需要 `RedisConnectionFactory`。

**替换取号 HTTP 客户端**（该类不是接口，需要继承并覆写 `requestToken`）：

```java
@Bean
public OAuth2TokenRequestClient myClient(ObjectMapper objectMapper, HeaderManager headerManager,
                                         FeignAuthProperties properties) {
    return new OAuth2TokenRequestClient(
        new OAuth2TokenResponseParser(objectMapper), headerManager, objectMapper, properties.getHttp()) {
        @Override
        public OAuth2AccessToken requestToken(String serviceName,
                                              FeignAuthProperties.Service service,
                                              FeignAuthProperties.Client client) {
            // 走你自己的 HTTP 实现
            return super.requestToken(serviceName, service, client);
        }
    };
}
```

若你的实现持有资源，实现 `AutoCloseable` 以便容器关闭时释放。

---

## 13. 排障

### 13.1 分诊流程

**第 1 步：打开 DEBUG 日志**

```yaml
logging:
  level:
    io.github.devoracode.feignauth: DEBUG
```

**第 2 步：按现象二分**

| 现象 | 方向 |
|---|---|
| 一条 `FeignAuth` 日志都没有，对端直接 401 | 没挂 `configuration = FeignClientConfig.class` → 见 [4.2](#42-声明-feignclient) |
| 抛 `no service config matched` | target 与 `base-url` 对不齐 → 见 [13.2](#132-报错索引) |
| 有 `fetching new token` 但对端仍 401 | 令牌取到了但 Header 写法不对 → 检查 `token-header` / `token-prefix` |
| 日志里反复 `fetching new token` + `token eviction suppressed` | 每次调用都被 401 → 令牌被拒，不是 IdP 挂了 |
| 启动期就失败 | 配置校验类错误 → 看异常里的配置键名 |

**第 3 步：核对 target**

确认 `@FeignClient(url = ...)` 展开后的实际 URL 是否为预期的协议、域名、端口。

**第 4 步：取号问题看异常 cause**

`FeignAuthTokenException` 的 cause 链里有 IdP 的状态码与正文片段。

**第 5 步：Redis 模式直接查键**

```bash
GET feign-auth:token:<服务名>:<clientId>
```

### 13.2 报错索引

| 消息 | 原因 | 处理 |
|---|---|---|
| `FeignAuth: no service config matched for baseUrl=..., path=...` | target 与所有 `base-url` 的 scheme/host/port 不一致；或请求路径没命中任何前缀且无兜底 | 核对 `base-url` 与 `@FeignClient(url)`；确认前缀/兜底配置 |
| 消息后半段 `configured services: [name -> url]` | 同上 | 用清单快速对比拼写、端口、协议 |
| `... have invalid auth config: <name> (unsupported auth.type '...')` | `auth.type` 拼错或未配置 | 改为 `oauth2` / `api-key` |
| `... (auth config is missing, expected auth.type 'oauth2' or 'api-key')` | 服务下缺 `auth` | 补 `auth` 块 |
| `FeignAuth: service '<name>' has no auth config` | 同上（拦截器侧防御） | 同上 |
| `FeignAuth: service '<name>' returned <状态码>, <说明>` | 远端返回 4xx/5xx，**且不是**被判定为令牌过期 | 按状态码定位：401 凭证无权、403 被拒、404 路径不存在、429 限流、5xx 远端不可用。cause 里是原始 `FeignException`，正文可从 cause 读出 |
| `FeignAuth [oauth2] failed to obtain token for service='...', path=...` | 取号链路失败，cause 里有真实原因 | 看 cause 的 `Token request failed: ...` |
| `token-url is required for OAuth2 service baseUrl=...` | 没配 `token-url` | 补配置 |
| `OAuth2 client id and secret are required for service baseUrl=...` | `clients[].id` / `secret` 有空值 | 补全凭证 |
| `No OAuth2 clients configured for service: <name>` | 没配 `clients` | 至少配一个 |
| `No OAuth2 client matched requestPath=... for service=...` | 所有 Client 都带前缀且都没命中 | 加默认 Client 或补前缀 |
| `Multiple OAuth2 clients matched requestPath=...` | 两个 Client 前缀长度相同且都命中 | 消除歧义 |
| `Multiple default OAuth2 clients configured for service: <name>` | 两个 Client 都没配前缀 | 只留一个默认 Client |
| `Multiple services matched requestPath=... for baseUrl=...` | 同 host 下两个服务前缀等长命中 | 调整前缀 |
| `Multiple fallback services configured for baseUrl=...` | 同 host 下多个兜底服务 | 只留一个 |
| `API key value is required for service: <name>` | `type: api-key` 但 `value` 为空 | 补 `value` |
| `FeignAuth: invalid configuration feign-auth.cache.provider=..., must be either 'local' or 'redis'` | provider 拼错/空 | 改为合法值 |
| `FeignAuth: invalid configuration feign-auth.http.*` | 超时/池大小越界 | 见 [5.8](#58-http) |
| `FeignAuth: invalid configuration feign-auth.services.*.auth.expired-token-statuses=...` | 未配 `response-status-field` 时值不在 100–599 | 改正状态码，或改用 `response-status-field` + 业务码 |
| `FeignAuth: feign-auth.cache.provider=redis but no RedisConnectionFactory...` | 未引入 data-redis 或排除了 RedisAutoConfiguration | 补依赖并配置 `spring.redis.*`，或改 `local` |
| `Token request failed: 401, error=invalid_client...` | IdP 拒绝凭证 | 核对 id/secret、grant-type、认证方式 |
| `Token request failed: 400, error=invalid_request...` | 请求形态不对 | 确认 Content-Type 与请求体编码一致 |
| `Token request failed: null` | `token-url` 不是可用的 HTTP(S) 绝对地址（缺协议名或用了 `ftp://` 等非 HTTP 协议） | 补 `https://` |
| `Invalid token-url: <原值>` | `token-url` 首段含冒号但缺协议名（如 `127.0.0.1:8080/token`），URI 解析失败 | 补 `https://` |
| `Token field '...' not found or blank in response` | `token-field` 路径不匹配 | 对照实际响应体 |
| `Token field not found in response` | 响应里既没有 `access_token` 也没有 `accessToken` / `token` | 对照实际响应体 |
| `Token response exceeds 65536 bytes...` | 2xx 响应体超过 64 KiB | 检查 IdP 是否返回异常内容 |
| `Serialize token request failed` | 取号请求体序列化失败 | 检查 `ObjectMapper` 与字段值 |
| `Invalid Content-Type in token-request-headers: '...'` | Content-Type 无法解析 | 修正值 |
| `Unsupported OAuth2 token request Content-Type: '...'` | 不是 JSON 族或表单 | 只支持这两类 |
| `Unsupported OAuth2 token method: ...` | `method` 不是 get/post | 修正 |
| `FeignAuth: client id of service '...' cannot be used with client-authentication=basic` | client id 含冒号 | 改 id 或改 `parameters` |
| `Failed to acquire lock for key: ...` | Redis 锁 60 次重试仍失败 | 检查是否有长事务占锁/Redis 故障 |
| `FeignAuth: token eviction failed for service='...'` | Redis 驱逐异常 | 看 cause |
| `Service config not found: <name>` | 内部服务名与配置对不上 | 检查服务名拼写 |

> **`Token request failed: null` 与 `Invalid token-url` 的区别**：`token-url` 首段含冒号（如 `127.0.0.1:8080/token`）时 URI 解析直接失败，starter 快速失败并点名配置键；首段不含冒号（如 `api.example.com/token`）或使用非 HTTP 协议（如 `ftp://`）时 URI 能解析但不是可用的 HTTP 地址，失败发生在连接层，异常消息因此不含具体原因。

### 13.3 日志与可观测性

| Logger | 级别 | 场景 |
|---|---|---|
| `...feign.FeignAuthRequestInterceptor` | DEBUG | 每个请求注入了哪个 Header（不含令牌值） |
| `...feign.FeignAuthRequestInterceptor` | ERROR | 取号失败 |
| `...feign.FeignAuthRequestInterceptor` | WARN | `fail-on-no-match=false` 时未匹配服务（每 baseUrl 一次） |
| `...feign.FeignAuthStatusHandler` | WARN | 令牌过期驱逐/冷却、读取响应体失败、URL 解析失败（每 URL 一次） |
| `...oauth2.TokenFetcher` | INFO | `fetching new token`、`token evicted` |
| `...oauth2.TokenFetcher` | WARN | 冷却期内驱逐被抑制 |
| `...oauth2.TokenEndpointWarnings` | WARN / DEBUG | 凭证进查询串（每服务一次）、非 HTTPS `token-url`（每服务一次）、Authorization 已配置所以不代发（DEBUG） |
| `...oauth2.OAuth2TokenRequestClient` | WARN | `Failed to close token response: ...` |
| `...oauth2.lock.RedisLockProvider` | WARN | 解锁失败，锁会随 TTL 过期 |
| `...oauth2.store.RedisTokenStore` | WARN | 反序列化失败/值类型异常，已删除坏键 |

**这些日志不会打印令牌或密钥本身**；但 Redis 中存的是令牌明文，访问控制需自行保证。

---

## 14. 生产就绪检查清单

- [ ] `token-url` 使用 HTTPS；生产环境不放宽证书校验。
- [ ] 客户端密钥不写在代码库中，用环境变量/配置中心注入（`${ORDER_SECRET}`）。
- [ ] `fail-on-no-match` 保持默认 `true`；确需放行时记录到期时间与原因。
- [ ] 非幂等接口已设计幂等键，或已关闭自动重放。
- [ ] `HeaderCustomizer` 无副作用（重试会重复执行）。
- [ ] 多实例部署使用 `cache.provider: redis`，并确认 Redis 访问控制（令牌为明文）。
- [ ] `cache.redis.key-prefix` 按应用隔离，避免与其他系统共用键空间。
- [ ] 已配置 `expired-token-statuses` 与（必要时）`response-status-field`，并覆盖真实网关行为。
- [ ] 生产日志级别不会把 URL/查询串打全（GET + `parameters` 时密钥在 query 中）。
- [ ] 已核对 `@FeignClient(url)` 与 `base-url` 的协议、端口、路径前缀。
- [ ] 对 `TokenFetcher` 的 INFO 日志配置告警：短时间内大量 `fetching new token` 通常意味着 IdP 拒绝或 TTL 配置异常。
- [ ] 为 Redis 模式准备降级方案（Redis 不可用时取号与驱逐会失败）。

---

## 15. 升级到 2.0.0

2.0.0 有四项破坏性变更，升级时逐项检查。

### 15.1 配置根键 `feign` → `feign-auth`

```yaml
# 1.x
feign:
  services: ...

# 2.0.0
feign-auth:
  services: ...
```

旧键**静默失效**：应用能正常启动，`services` 为空，每个请求运行期抛 `FeignAuthConfigurationException`。必须整片改名，不能只改一部分。

### 15.2 取消全局 `auth` 层

| 1.x | 2.0.0 |
|---|---|
| `feign.auth.cache.*` | `feign-auth.cache.*` |
| `feign.auth.http.*` | `feign-auth.http.*` |
| `feign.auth.fail-on-no-match` | `feign-auth.fail-on-no-match` |

各服务下的 `feign.services.<name>.auth.*` 层级不变，只是根键改为 `feign-auth.services.<name>.auth.*`。

### 15.3 `@FeignClient` 占位符同步改名

```java
// 1.x
url = "${feign.services.order.base-url}"
// 2.0.0
url = "${feign-auth.services.order.base-url}"
```

这类写法**不会编译报错**，漏改会让 Feign 拿到解析不出的字面量。

### 15.4 环境变量名不变

`SystemEnvironmentPropertyMapper` 映射时会丢掉短横线、把 `.` 换成 `_`，因此：

```text
feign-auth.cache.provider     → FEIGNAUTH_CACHE_PROVIDER
feign.services.x.base-url     → FEIGN_SERVICES_X_BASEURL  （旧）
feign-auth.services.x.base-url → FEIGNAUTH_SERVICES_X_BASEURL（新）
```

注意：`FEIGN_AUTH_...`（带下划线分段）在改名前后都不会生效，不必为其调整。

### 15.5 自定义 `@Bean FeignAuthProperties` 的代码

`FeignAuthProperties.GlobalAuth`、`getAuth()`、`setAuth()` 已删除，改为：

- `getCache()` / `setCache(...)`
- `getHttp()` / `setHttp(...)`
- `isFailOnNoMatch()` / `setFailOnNoMatch(...)`
- `getServices()`

显式 `setCache(null)` / `setHttp(null)` 会回退默认对象，不会 NPE。

### 15.6 `cache.provider: redis` 的项目补依赖

`spring-boot-starter-data-redis` 不再传递引入，需在自身 POM 显式声明。漏声明时启动失败并点名 `feign-auth.cache.provider=redis` 与两种修法。

### 15.7 其它可观察变化

- 两条取号安全告警的 logger 名改为 `io.github.devoracode.feignauth.oauth2.TokenEndpointWarnings`；按 logger 名调级或路由的配置需同步。
- 非 HTTPS `token-url` 的 WARN 去重键加入服务名（同服务多 Client 仍只一条）。
- `PathUtils` 新增公共 API `parseBaseUrl` / `BaseUrlParts`；既有方法签名与语义不变。

---

## 16. 已知限制

1. **`response-status-field` 只在非 2xx 响应上生效**：HTTP 200 但响应体里报 token 过期的接口无法自动识别。
2. **`response-status-field` 只支持对象路径**，不支持数组下标；值必须能转成整数（数字或数字字符串）。
3. **取号 HTTP 客户端不自动重试、不跟随 3xx**：连接失败/超时直接抛 `FeignAuthTokenException`；重试交给 Feign `Retryer`。
4. **`token-url` 缺少协议名或使用非 HTTP 协议**时请求期失败，错误文案不总带配置键（见 [6.6](#66-凭据与传输安全)）。
5. **自定义 `RequestInterceptor` 改写了 `template.target(...)`** 时，路径归一化无法消除 host/前缀改写差异，可能出现「注入了令牌但不驱逐、不重放」。
6. **`path-prefixes` 中的 `//` 不会被折叠**，而请求路径会被折叠；前缀里不要写连续斜杠。
7. **同一 host 只能有一个兜底**（API Key 无前缀 / OAuth2 有默认凭证），否则报冲突。
8. **`expired-token-statuses` 一旦配置就完全替换默认值**，不会与 `[401, 421, 423]` 合并。
9. **本地缓存是懒清理**：过期条目在被访问时才删除；不访问就不会释放（无后台线程）。
10. **Redis 缓存存令牌明文**，需按敏感数据管理。
11. **服务发现（负载均衡名）通常无法匹配**：匹配依赖 target 的真实 scheme/host/port，请显式给 `url` 或接受 `fail-on-no-match` 的放行语义。
12. **`token-field` 对数组下标、`null`/布尔叶节点没有约定**：不建议依赖这类响应结构。
13. **`@FeignClient` 的 `name` 与配置中的服务名无绑定**：服务名只用于配置引用、缓存键、日志与定制器。
14. **2.0.0 的兼容范围**：Spring Boot 2.2.x ~ 2.7.x；更高版本不在当前验证范围内。

---

## 附录 A：常量与默认值速查

**配置默认值**

| 键 | 默认值 |
|---|---|
| `feign-auth.fail-on-no-match` | `true` |
| `feign-auth.cache.provider` | `local` |
| `feign-auth.cache.redis.key-prefix` | `feign-auth:` |
| `feign-auth.http.connect-timeout` | `5s` |
| `feign-auth.http.connection-request-timeout` | `5s` |
| `feign-auth.http.socket-timeout` | `10s` |
| `feign-auth.http.max-total` | `200` |
| `feign-auth.http.default-max-per-route` | `50` |
| `auth.type` | 无默认，**必须写** |
| `auth.method` | `post` |
| `auth.client-authentication` | `auto` |
| `auth.token-header` | `x-token` |
| `auth.header-name` | `Authorization` |
| `auth.token-prefix` | 空 |
| `auth.expire-ahead-seconds` | `60` |
| `auth.expired-token-statuses` | `[401, 421, 423]` |
| `auth.request-fields.client-id` | `client_id` |
| `auth.request-fields.client-secret` | `client_secret` |
| `auth.request-fields.grant-type` | `grant_type` |
| `clients[].grant-type` | `client_credentials` |

**代码常量**（改动需同步本文）

| 常量 | 值 | 位置 |
|---|---|---|
| 令牌 TTL 兜底 | `7200` 秒 | `OAuth2TokenResponseParser` |
| 令牌 TTL 上限 | `86400` 秒（1 天） | `OAuth2TokenResponseParser` |
| 取号响应体上限 | `65536` 字节（64 KiB） | `TokenResponseBodyReader` |
| 错误正文字段截断 | `512` 字符 | `OAuth2TokenResponseParser` |
| 令牌驱逐冷却 | `30` 秒 | `TokenFetcher` |
| `expires_in` 搜索深度 | `3` 层 | `OAuth2TokenResponseParser` |
| 本地锁分段数 | `64` | `LocalLockProvider` |
| Redis 锁 TTL / 重试 | `30` 秒 / `200ms` × `60` | `RedisLockProvider` |
| 默认 Retryer | `2` 次尝试，初始间隔 `100ms`，上限 `1s` | `FeignClientConfig` |
| WARN 去重表上限 | `32` 个 key | `WarnThrottle` |
| URL 派生值缓存上限 | `256` 条 | `ServiceMatcher` |
| Token 字段查找顺序 | `access_token` → `accessToken` → `token` | `OAuth2TokenResponseParser` |
| 过期时间字段查找顺序 | `expires_in` → `expiresIn` → `expire_in` → `expireIn` | `OAuth2TokenResponseParser` |
| 端口归一化 | `http` → `80`，`https` → `443` | `PathUtils` |

---

## 附录 B：术语表

按本文用法解释，不是通用定义的完整版本。

| 术语 | 在本文里的意思 | 主要出现在 |
|---|---|---|
| starter | Spring Boot 的「自动装配依赖包」：引进来就自动生效，不需要你写 `@Bean` | 全文 |
| Bean / 容器 | 交给 Spring 管理的对象。starter 的取号器、匹配器、缓存都是 Bean | [3.4](#34-安装后如何确认生效)、[12](#12-扩展点) |
| OpenFeign / Feign | 把 HTTP 调用写成 Java 接口的框架；`@FeignClient` 标注的那个接口就是你要用的客户端 | [4](#4-快速开始) |
| 拦截器（`RequestInterceptor`） | 请求发出前必过的一段代码，starter 在这里把令牌塞进 Header | [1.3](#13-一次请求的处理流程) |
| 错误解码器（`ErrorDecoder`） | 收到非 2xx 响应时决定「抛什么异常」的一段代码，starter 在这里判断令牌是否过期 | [1.3](#13-一次请求的处理流程) |
| 重试器（`Retryer`） | 决定失败的请求是否再发一次、间隔多久 | [9.7](#97-重试与请求重放) |
| OAuth2 | 一种「先拿令牌、再带令牌访问」的授权协议；本文只用 `client_credentials`（机器对机器）这一种模式 | [6](#6-认证方式) |
| IdP | Identity Provider，发令牌的那台服务器（`token-url` 指向它） | [6](#6-认证方式) |
| `access_token` / 令牌 | IdP 发给你的访问凭证，有效期内可重复使用，过期后要重新取 | [6.5](#65-token-响应解析) |
| `client_id` / `client_secret` | 应用的身份与密码，用来换令牌；配在 `auth.clients[]` 下 | [5.5](#55-authclients) |
| Basic 认证 | 把 `client_id:client_secret` 做 Base64 后放进 `Authorization` 头，而不是放进请求体 | [6.2](#62-客户端认证方式client-authentication) |
| TTL | 令牌还能活多少秒；starter 用它决定缓存过期时间 | [6.5](#65-token-响应解析) |
| Header | HTTP 请求头，键值对；对端靠它认令牌 | [8](#8-header-注入) |
| authority / `baseUrl` | URL 里的 `scheme://host:port` 部分。starter 用它认服务，`base-url` 上的路径另作前缀用 | [7.1](#71-匹配算法) |
| 路径前缀 | `clients[].path-prefixes` / `auth.path-prefixes` 里写的路径开头，用来区分同一 host 下的不同服务或凭证 | [7](#7-路由与匹配) |
| 兜底 / 默认凭证 | 没配路径前缀的那一项，前缀都不命中时用它 | [7.1](#71-匹配算法) |
| 缓存键 | 令牌在缓存里的名字，格式 `{服务名}:{clientId}`；Redis 下还会带 `key-prefix` | [9.1](#91-缓存键) |
| 驱逐（evict） | 把缓存里的令牌扔掉，下次请求重新取号 | [9.6](#96-驱逐冷却与重放) |
| 重放（replay） | 驱逐后用新令牌把刚才那个请求再发一次 | [9.6](#96-驱逐冷却与重放) |
| 幂等 | 同一请求发两次不会造成两次后果；不幂等的接口不能随便重放 | [9.7](#97-重试与请求重放) |
| 锁 / 分布式锁 | 防止多个线程或多个实例同时去取号，只让一个去拿 | [9.3](#93-锁机制) |
| 双重检查 | 拿到锁后再看一遍缓存，避免白取一次号 | [9.4](#94-取号的双重检查) |
| kebab-case | 全小写、用短横线分词的写法，如 `token-url` | [5.10](#510-绑定约定) |
| 宽松绑定 | Spring Boot 允许 `token-url`、`tokenUrl`、`TOKEN_URL` 都绑到同一项；推荐统一写 kebab-case | [5.10](#510-绑定约定) |
| `Duration` | 时长写法：`5s`、`500ms`、`1m` | [5.8](#58-http) |
| 占位符 `${...}` | YAML/注解里引用别处的值，如 `${ORDER_SECRET}` 引用环境变量 | [4.1](#41-最小配置) |
| BOM | 一份「版本清单」依赖，import 之后其它依赖不用逐个写版本号 | [3.2](#32-gradle) |
| optional 依赖 | 不向下传递的依赖：Redis 与 feign-core 都是 optional，需要你自己声明 | [2](#2-环境要求) |
| 启动期 / 请求期 | 启动期失败 = 应用起不来；请求期失败 = 能启动，用到才报错。两者的排查方式不同 | [5.9](#59-校验时机矩阵) |
| 明文 / HTTPS | `http://` 下密钥与令牌在网络上是可读的；`token-url` 非 HTTPS 时 starter 会告警 | [6.6](#66-凭据与传输安全)、[14](#14-生产就绪检查清单) |
| nonce / HMAC 签名 | 某些对端要求每次请求带一次性随机数与签名，只能自己实现 `HeaderCustomizer` | [8.3](#83-动态-headerheadercustomizer-接口) |

---

## 许可证

本项目基于 [Apache License 2.0](https://www.apache.org/licenses/LICENSE-2.0) 开源，完整条款见仓库根目录的 `LICENSE` 文件，`pom.xml` 中的 `<licenses>` 声明与之一致。
