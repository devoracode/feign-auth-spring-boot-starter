# feign-auth-spring-boot-starter 使用指南

> 适用版本：**2.0.0**（配置根键为 `feign-auth`）。
> 1.x 的 `feign.services.*` / `feign.auth.*` 旧键在 2.0.0 中已失效，升级步骤见文末「升级到 2.0.0」。
>
> 本文按「装进来 → 跑起来 → 配全 → 覆盖常见场景 → 排障」的顺序组织。所有配置键、默认值与异常文案均以 2.0.0 源码为准。

---

## 目录

第一次接入只需要读完「入门」这 5 节；后面按用途分组，需要时再查。

### 入门

1. [它解决什么问题](#1-它解决什么问题)
2. [环境要求](#2-环境要求)
3. [安装](#3-安装)
4. [从零到第一次跑通](#4-从零到第一次跑通)
5. [新手常见踩坑](#5-新手常见踩坑)

### 参考

6. [配置项全量参考](#6-配置项全量参考)
7. [认证方式详解](#7-认证方式详解)
8. [服务与 Client 的匹配规则](#8-服务与-client-的匹配规则)
9. [Header 注入](#9-header-注入)（整节可选）

### 缓存与容错

10. [令牌缓存与并发控制](#10-令牌缓存与并发控制)
11. [过期令牌识别与自动重放](#11-过期令牌识别与自动重放)

### 排障

12. [异常与排障](#12-异常与排障)
13. [日志与可观测性](#13-日志与可观测性)

### 扩展与落地

14. [扩展点](#14-扩展点)
15. [场景合集](#15-场景合集)
16. [生产检查清单](#16-生产检查清单)

### 升级与边界

17. [升级到 2.0.0](#17-升级到-200)
18. [已知边界](#18-已知边界)

> 文末另有两张速查表：[附：默认值速查](#附默认值速查)、[附：术语表](#附术语表)。

---

## 1. 它解决什么问题

对接多个第三方 HTTP API 时，鉴权逻辑通常是重复的：取令牌、缓存、过期刷新、按路径选不同凭证、拼 Header、401 后清缓存重试。这些代码散落在各个 `FeignClient` 周围，配置写在代码里，换一个环境就要改代码。

本 starter 把这件事收敛成一组配置：

- **OAuth2**：自动取号、缓存、提前刷新、过期驱逐并重放；支持 GET / POST 两种取号方式、可配置凭证发送方式、可自定义请求字段名与响应字段路径。
- **API Key**：把固定密钥注入指定 Header，支持前缀与按路径区分多套 Key。
- **多服务、多客户端**：同一个 host 下按路径前缀路由到不同服务配置；同一个服务内按路径前缀路由到不同 OAuth2 Client。
- **Header 扩展**：YAML 配置静态 Header；实现 `HeaderCustomizer` 注入签名、Trace 等动态 Header。
- **缓存与锁**：本地 `ConcurrentHashMap` 或 Redis（多实例共享令牌 + 分布式锁）。
- **统一失败口径**：配置错误、取号失败、远程错误都有明确的异常类型与可搜索的文案。

你只需要在 `@FeignClient(configuration = FeignClientConfig.class)` 上挂一次配置类，业务代码不需要感知鉴权。

---

## 2. 环境要求

| 项 | 要求 | 说明 |
|---|---|---|
| Java | 8+ | starter 以 `source/target 1.8` 编译 |
| Spring Boot | 2.2.x ~ 2.7.x | 基线 2.2.5；自动配置同时注册 `spring.factories`（≤2.6）与 `AutoConfiguration.imports`（2.7+） |
| OpenFeign | 需使用方自行引入 | 建议 `spring-cloud-starter-openfeign`（对应 feign-core 10.x）；starter 对 `feign-core` 是 `optional`，不会传递 |
| Apache HttpClient 4.x | 由 starter 传递引入 | 只用于 OAuth2 取号，不需要你声明 |
| Spring Data Redis | 仅 `provider: redis` 时需要 | starter 对 `spring-boot-starter-data-redis` 是 `optional`，**不再传递引入** |

> 如果你的项目只配 `cache.provider: local`（默认），classpath 上不需要任何 Redis 依赖。

---

## 3. 安装

### 3.1 Maven

```xml
<dependency>
    <groupId>io.github.devoracode</groupId>
    <artifactId>feign-auth-spring-boot-starter</artifactId>
    <version>2.0.0</version>
</dependency>
```

使用 OAuth2 时还需要 OpenFeign 本身（版本由你的 Spring Cloud BOM 管理；Boot 与 Spring Cloud 的具体对应见 4.2）：

```xml
<dependency>
    <groupId>org.springframework.cloud</groupId>
    <artifactId>spring-cloud-starter-openfeign</artifactId>
</dependency>
```

使用 `cache.provider: redis` 时再补：

```xml
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

### 3.3 安装后如何确认生效

项目启动后，容器中应存在以下 Bean（`provider: local` 时）：

| Bean | 类型 | 作用 |
|---|---|---|
| `feignAuthAutoConfiguration` | 配置类 | 自动配置入口 |
| `serviceMatcher` | `ServiceMatcher` | 按 base-url + 路径匹配服务 |
| `tokenFetcher` | `TokenFetcher` | 取号、缓存、驱逐 |
| `tokenStore` | `LocalTokenStore` | 令牌缓存 |
| `lockProvider` | `LocalLockProvider` | 单实例并发控制 |
| `oAuth2TokenRequestClient` | `OAuth2TokenRequestClient` | 真正发取号请求的 HTTP 客户端 |
| `headerManager` | `HeaderManager` | Header 注入管线 |

`FeignClientConfig` 本身是逐个 `@FeignClient` 子上下文里的配置类，不在主容器中。它注册的 `RequestInterceptor` / `ErrorDecoder` / `Retryer` 只作用于挂了它的客户端。

---

## 4. 从零到第一次跑通

> 这一节是给「刚接手这个 starter、之前没单独配过 OpenFeign」的同学写的完整路径：确认手里有什么 → 加依赖 → 配置放哪 → 挂启动类 → 写客户端 → 发第一个请求 → 对着日志判断成败。只想查某个配置项的含义，直接跳到第 6 节。

### 4.1 先确认你手里有什么

开工前向接口提供方（或对方接口文档）确认这 6 项，缺任何一项都会卡在某一步：

| 要确认的东西 | 写在哪个配置 | 不知道会怎样 |
|---|---|---|
| 令牌端点 URL，例如 `https://api.example.com/oauth/token` | `auth.token-url` | 请求期 `token-url is required for OAuth2 service baseUrl=...` |
| `client_id` / `client_secret` | `auth.clients[].id` / `secret` | IdP 返回 `error=invalid_client` |
| 取号用 POST 还是 GET；凭证放请求体、查询串还是 Basic 头 | `auth.method` + `auth.client-authentication` | IdP 返回 400/401，报文格式对不上 |
| 令牌要放哪个 Header、要不要 `Bearer ` 前缀 | `auth.token-header` + `auth.token-prefix` | 令牌取到了，但对端仍回 401 |
| 业务 API 的协议、域名、端口、路径前缀 | `services.<name>.base-url` | `no service config matched`（4.8） |
| 你工程用的 Spring Boot 版本 | 决定 Spring Cloud 版本（4.2） | 依赖冲突，启动阶段就失败 |

同时确认工程本身：JDK 8+、Spring Boot 2.2.x~2.7.x、已引入 OpenFeign。这三项任一不满足，先解决它再往下。

### 4.2 加依赖

`pom.xml` 里加两段：

```xml
<dependency>
    <groupId>io.github.devoracode</groupId>
    <artifactId>feign-auth-spring-boot-starter</artifactId>
    <version>2.0.0</version>
</dependency>

<dependency>
    <groupId>org.springframework.cloud</groupId>
    <artifactId>spring-cloud-starter-openfeign</artifactId>
</dependency>
```

第二个依赖不写 `<version>` 的前提是工程已经 import 了 Spring Cloud BOM；没 import 就按下面这张对照表填一个具体版本（表的来源见 [Spring Cloud](https://spring.io/projects/spring-cloud/) 官方版本说明）：

| Spring Boot | Spring Cloud release train | spring-cloud-starter-openfeign |
|---|---|---|
| 2.2.x、2.3.x | `Hoxton` | 2.2.x |
| 2.4.x、2.5.x | `2020.0.x` | 3.0.x |
| 2.6.x、2.7.x | `2021.0.x` | 3.1.x |

> 本 starter 在 Spring Boot 2.2.5 + feign-core 10.7.4 上开发与验证。用表中更高版本时，先确认你自己的 Feign 接口能正常调通，再接本 starter。

确认依赖真的进来了：

```bash
mvn dependency:tree -Dincludes=io.github.devoracode
```

应输出 `io.github.devoracode:feign-auth-spring-boot-starter:jar:2.0.0`，并带出 `org.apache.httpcomponents:httpclient`（取号用的，不需要你声明）。

### 4.3 配置写在哪个文件

写到 `src/main/resources/application.yml`。`feign-auth` 是**根键**，必须顶格（第 0 列）——它和 `spring:`、`server:` 是同一层，不是它们的子项：

```yaml
server:
  port: 8080

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

- `order` 是你**自己起的逻辑服务名**，用于配置引用、缓存键、`HeaderCustomizer#supports`，与 `@FeignClient(name = ...)` 没有强制绑定关系。匹配只看 URL 与路径（见第 8 节）。
- `base-url` 必须与 Feign 实际 target 的 `scheme://host:port` 一致（默认端口 80/443 会归一化）。
- `clients` 必须是**用 `-` 开头的列表**，至少一个；没有 `path-prefixes` 的那个就是默认 Client。

第一次接入只需要上面这几行，其余配置项都有默认值：

| 不配的项 | 默认值 |
|---|---|
| `auth.type` | 无默认，**必须写** `oauth2` |
| `auth.method` | `post`（请求体用 JSON 编码） |
| `auth.client-authentication` | `auto` |
| `auth.token-header` | `x-token` |
| `auth.token-prefix` | 空（不拼前缀） |
| `auth.expire-ahead-seconds` | `60` |
| `cache.provider` | `local`（不需要 Redis 依赖） |
| `feign-auth.http.*` 超时 | 连接 5s / 从连接池取连接 5s / 读取 10s |
| `feign-auth.fail-on-no-match` | `true` |

> 密钥不要提交进代码库：写成 `secret: ${ORDER_SECRET}`，从环境变量注入（第 16 节检查清单）。

### 4.4 启动类：别忘了 `@EnableFeignClients`

```java
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.openfeign.EnableFeignClients;

@SpringBootApplication
@EnableFeignClients
public class MyApplication {

    public static void main(String[] args) {
        SpringApplication.run(MyApplication.class, args);
    }
}
```

- `@EnableFeignClients` 默认只扫描启动类所在包及其子包；`@FeignClient` 接口在别的包时写成 `@EnableFeignClients(basePackages = "com.acme.feign")`。
- **漏掉这个注解的后果**：注入 `OrderFeignClient` 时启动报 `NoSuchBeanDefinitionException`。这条报错与 starter 无关，改法是补注解，不是去翻配置。

### 4.5 定义 FeignClient 并挂上 starter

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

- `FeignClientConfig` 是 **starter 提供的类**，不需要你自己写或复制一份。它给这个客户端注册请求拦截器、错误解码器和重试器三样东西。
- `configuration = FeignClientConfig.class` **不能漏**。漏了以后请求照样发出去，但不带认证 Header、也不会做过期重放，典型现象是对端一直回 401，而日志里一条 `FeignAuth` 都没有。
- `url = "${...}"` 是引用 4.3 里配的同一个值，避免域名写两处对不上；直接写死 `url = "https://api.example.com"` 也可以，但必须和 `base-url` 完全一致。
- 占位符里的键名拼错时**不会**编译报错，也不会在启动时报「占位符解析失败」：`@FeignClient` 注册发生在占位符解析器装上之前，`${...}` 会原样留在 target 上，第一次调用才以 `no service config matched for baseUrl=http://${...}` 的形式暴露——报错里能直接看到那串没解析的字面量。所以 `url` 一定要和 `base-url` 用同一个键，别两处手写。

### 4.6 业务代码调用

```java
@Service
public class OrderService {

    private final OrderFeignClient orderFeignClient;

    public OrderService(OrderFeignClient orderFeignClient) {
        this.orderFeignClient = orderFeignClient;
    }

    public String query(String orderId) {
        // 业务代码完全不需要关心 token
        return this.orderFeignClient.getOrder(orderId);
    }
}
```

### 4.7 第一次请求：日志应该长这样

先把 starter 的日志开到 DEBUG（`application.yml` 追加，与 `feign-auth` 顶格同级）：

```yaml
logging:
  level:
    io.github.devoracode.feignauth: DEBUG
```

启动阶段 starter 本身不打日志；6.9 里那些非法配置会直接抛异常终止启动，不会走到这一步。第一次调用 `orderFeignClient.getOrder("1")` 时，你会看到这两行：

```text
INFO  io.github.devoracode.feignauth.oauth2.TokenFetcher : FeignAuth [oauth2] fetching new token for service='order', clientId='my-client'
DEBUG io.github.devoracode.feignauth.feign.FeignAuthRequestInterceptor : FeignAuth [oauth2] service='order' header='x-token' path=/api/orders/1
```

第一行说明 starter 向 `token-url` 发过取号请求并且成功，第二行说明令牌已按 `x-token` 注进这个请求。第二次及以后的调用只剩 DEBUG 那行——令牌命中缓存，不再取号。

**判断跑通的三条标准**：

1. 日志里出现过 `fetching new token`；
2. 没有任何 `FeignAuth` 开头的 WARN / ERROR；
3. 对端返回 2xx，业务方法拿到正常返回值。

三条都满足就算接好了；想核对发出去的 Header **值**，starter 日志不会打印令牌内容（第 13 节），需要本机抓包或让对端回显。三条里有不满足的，按下一节的原文对照定位。

### 4.8 三种最常见的失败（原文对照）

**A. 一条 `FeignAuth` 日志都没有，请求直接 401**

不是 starter 报错，而是这个客户端没挂上 `FeignClientConfig`——回到 4.5 补 `configuration`。

**B. 匹配不到服务配置**

```text
io.github.devoracode.feignauth.exception.FeignAuthConfigurationException:
FeignAuth: no service config matched for baseUrl=https://api.example.com, path=/api/orders/1;
configured services: [order -> https://open.example.com]; the Feign target URL must point to the
same scheme/host/port as feign-auth.services.<name>.base-url (default ports 80/443 are normalized,
a base-url path is treated as an outer prefix for path matching); set
feign-auth.fail-on-no-match=false to send unmatched requests without auth headers
```

看 `configured services` 里列出的就是你配的 `base-url`，把它和前半句的 `baseUrl=` 逐字符比：协议（http/https）、端口、拼写。若显示 `<none configured>`，说明根键没写对（多半还写着 1.x 的 `feign:`）。

**C. 取号本身失败**

```text
ERROR io.github.devoracode.feignauth.feign.FeignAuthRequestInterceptor :
FeignAuth [oauth2] failed to obtain token for service='order', path=/api/orders/1:
Token request failed: 401, error=invalid_client
```

冒号后面是 IdP 的原话。`invalid_client` 是凭证不对或认证方式不匹配；换成 `Connection refused` / `Read timed out` 这类，则是网络或超时问题，跟配置写法无关。完整实例见 12.4。

### 4.9 先绕开 starter，验证凭证本身能不能拿到

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

响应里出现 `access_token` 就说明凭证、端点、认证方式都对，问题一定出在 YAML 写法上；出现 `error=invalid_client` 就先去核对凭证，别改 starter 配置。

---

## 5. 新手常见踩坑

照抄第 4 节之后仍然跑不通，多半落在下面 12 条里。每条给「现象 → 原因 → 改法」；现象里的报错文字与代码实际抛出的消息模板一致，可以直接复制去搜索（个别来自 YAML/Spring 绑定层，已在条目里说明）。

### 5.1 YAML 用了 Tab 缩进

- **现象**：工程启动即失败，报 `found character '\t(TAB) that cannot start any token`。
- **原因**：YAML 规范不允许 Tab 作为缩进字符。
- **改法**：全选缩进处，换成空格，一层两个空格。IDEA 里 `Convert Indents to Spaces`。

### 5.2 根键还写着 1.x 的 `feign:`

- **现象**：启动一切正常，第一次调用抛 `... no service config matched for baseUrl=..., path=...`，报错里 `configured services: <none configured>`。
- **原因**：2.0.0 的配置根键是 `feign-auth`，`feign.services.*` 不再绑定，配置读不到但不报错。
- **改法**：根键改成 `feign-auth:`。完整升级对照见第 17 节。

### 5.3 `token-prefix` 忘了加引号

- **现象**：令牌正常取到（日志有 `fetching new token`），对端仍回 401；实际 Header 是 `BearereyJhbGc...`。
- **原因**：`token-prefix: Bearer ` 不带引号时，YAML 会去掉末尾空格，前缀变成 `Bearer`，starter 只做原样字符串拼接。
- **改法**：`token-prefix: "Bearer "`（保留空格必须加引号）。

### 5.4 `clients` 写成了 Map

- **现象**：启动阶段绑定失败，报错点名 `feign-auth.services.order.auth.clients`。
- **原因**：`clients` 是列表，写成 `clients:` 下直接 `id: ...`（或 `{id: ..., secret: ...}`）无法转成 `List`。
- **改法**：每个 Client 用短横线开头：

```yaml
clients:
  - id: my-client
    secret: my-secret
```

### 5.5 配了两个默认 Client

- **现象**：请求期抛 `Multiple default OAuth2 clients configured for service: order`。
- **原因**：没有 `path-prefixes` 的 Client 就是默认 Client，一个服务只能有一个。
- **改法**：给需要按路径路由的那个补 `path-prefixes`，只留一个不带前缀的。

### 5.6 `base-url` 多带了路径

- **现象**：`no service config matched`，报错里 `baseUrl=https://api.example.com`（没有 `/v1`）而 `configured services: [order -> https://api.example.com/v1]`。
- **原因**：匹配用 `scheme://host:port` 比 authority，`base-url` 上的路径会被当作「外层前缀」并入路径匹配；`@FeignClient(url=...)` 通常只写到 host，两边就对不齐了。
- **改法**：确认对端接口路径确实带这层前缀，并把 `@FeignClient(url)` 写成同一个值（推荐用 `${feign-auth.services.order.base-url}` 引用）；否则把 `base-url` 改成只到 host。详见 8.2。

### 5.7 `token-url` 漏写协议

- **现象**：`Invalid token-url: api.example.com/oauth/token`。
- **原因**：URI 解析失败，缺 `https://`。
- **改法**：补协议。生产环境建议 HTTPS，明文 `http://`（非回环地址）时 starter 会按服务记一次 WARN。

### 5.8 `auth.type` 拼错

- **现象**：`FeignAuth: service(s) matched baseUrl=... but have invalid auth config: order (unsupported auth.type 'oauth')`。
- **原因**：只接受 `oauth2` 和 `api-key`（忽略大小写），`oauth`、`apikey` 都算配置错误。
- **改法**：改成合法取值。这一条不受 `fail-on-no-match=false` 影响：只要 authority 匹配上了，非法的 `auth` 一定抛异常，只能改配置。

### 5.9 把 OAuth2 的路由配在 `auth.path-prefixes`

- **现象**：不同路径想用不同 Client，结果全部命中默认 Client。
- **原因**：`auth.path-prefixes` 只对 `api-key` 生效。
- **改法**：OAuth2 的前缀路由配在 `auth.clients[].path-prefixes` 上。

### 5.10 忘挂 `configuration = FeignClientConfig.class`

- **现象**：对端一直 401，且日志里一条 `FeignAuth` 都没有。
- **原因**：拦截器、错误解码器、重试器都由这个配置类注册，没挂就等于完全没接 starter。
- **改法**：`@FeignClient(..., configuration = FeignClientConfig.class)`。每个要用鉴权的客户端都要挂。

### 5.11 `provider: redis` 但没引入 Redis 依赖

- **现象**：启动失败：`FeignAuth: feign-auth.cache.provider=redis but no RedisConnectionFactory is available in the context; add spring-boot-starter-data-redis and configure spring.redis.*, or set feign-auth.cache.provider=local for the in-memory cache`。
- **原因**：2.0.0 起 `spring-boot-starter-data-redis` 是 optional，不再传递引入。
- **改法**：加依赖并配置连接；单机或先用本地缓存就把 `provider` 改回 `local`。

### 5.12 超时写成 `0` 或负数

- **现象**：启动期 `FeignAuth: invalid configuration feign-auth.http.connect-timeout=..., must be a positive duration (0 means infinite wait)`。
- **原因**：Apache HttpClient 把 `0` 当作无限等待，starter 不做静默钳制，直接拒绝。
- **改法**：写成 `5s`、`500ms`、`1m` 这类正时长（不足 1 毫秒的正值同样非法）。

> 另有两条容易误会的行为，不是错误但常被当成配错：一是 starter 只把令牌注入 **Header**，不会替你写进查询参数或请求体；二是 `request-headers` 与 `token-request-headers` 各管各的，不会互相带过去，需要两边都带就分别写两份。

---

## 6. 配置项全量参考

### 6.1 根级

| 配置键 | 类型 | 默认值 | 含义 |
|---|---|---|---|
| `feign-auth.services` | Map | 空 | 以逻辑服务名为 key 的服务配置集合，见 6.2 |
| `feign-auth.fail-on-no-match` | boolean | `true` | 请求未匹配到任何服务时是否快速失败 |
| `feign-auth.cache` | 对象 | — | 令牌缓存配置，见 6.6 |
| `feign-auth.http` | 对象 | — | 取号 HTTP 客户端配置，见 6.7 |

**`fail-on-no-match`**

- `true`（默认）：抛出 `FeignAuthConfigurationException`，请求**不会发出**。异常信息里会列出当前已配置的服务清单（`名称 -> base-url`）与修正指引。
- `false`：记录 WARN 后**不带鉴权 Header 放行**。WARN 对同一个 baseUrl 只记一次，后续降级为 DEBUG。
- 无论取值如何，**命中了服务但 `auth` 缺失或 `auth.type` 非法时始终快速失败**。

```yaml
feign-auth:
  fail-on-no-match: false   # 仅在迁移期/灰度期使用，注意未鉴权请求会被真实发出
```

---

### 6.2 `feign-auth.services.<name>`

`<name>` 是逻辑服务名，可任意命名；它出现在缓存键、`HeaderCustomizer#supports` 参数与异常文案中。

| 配置键 | 类型 | 必填 | 默认值 | 含义 |
|---|---|---|---|---|
| `base-url` | String | 匹配所必需 | 无 | 服务基础 URL；Feign target 的 `scheme://host:port` 必须与之一致 |
| `auth` | 对象 | OAuth2/API Key 均必需 | 空对象 | 鉴权配置，见 6.3 |

**`base-url` 规则**

- 比较的是 **authority**：`scheme` 与 `host` 忽略大小写，省略端口时补默认端口（`http`→80，`https`→443），因此 `https://api.x.com` 与 `HTTPS://API.X.COM:443` 等价。
- `base-url` 携带的路径（如 `https://api.x.com/v1`）会作为**外层前缀**：请求路径落在其下时先剥离该段，再参与 `path-prefixes` 匹配。
- 支持的写法：`https://api.x.com`、`https://api.x.com/v1`、`https://api.x.com/v1/`（末尾斜杠忽略）、`http://localhost:8080`。
- 不可解析的写法（如缺少协议名的 `api.x.com`）**永远匹配不上**；请求期会以 `no service config matched` 失败。

---

### 6.3 `feign-auth.services.<name>.auth`

这一层同时承载 OAuth2 与 API Key 的配置，未使用的字段会被忽略。

| 配置键 | 类型 | 默认值 | 适用 | 含义 |
|---|---|---|---|---|
| `type` | String | 无 | 通用 | `oauth2` 或 `api-key`，忽略大小写；缺失/拼错则请求期快速失败 |
| `token-url` | String | 无 | OAuth2 | 令牌端点 URL，**建议 HTTPS** |
| `method` | String | `post` | OAuth2 | 取号用的 HTTP 方法：`post` / `get` |
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
| `path-prefixes` | List\<String\> | 空 | **API Key** | 该 API Key 服务生效的路径前缀；为空即兜底 |
| `clients` | List | 空 | OAuth2 | 客户端凭证列表，至少一个 |
| `request-fields` | 对象 | 默认字段名 | OAuth2 | 自定义取号请求字段名，见 6.5 |
| `request-headers` | Map | 空 | 通用 | 每个业务请求都会带上的静态 Header |
| `token-request-headers` | Map | 空 | OAuth2 | 每个取号请求都会带上的静态 Header；`Content-Type` 还决定请求体编码 |

> **`path-prefixes` 的归属**：`auth.path-prefixes` 只对 `api-key` 生效。OAuth2 的路径路由请配在 `auth.clients[].path-prefixes` 上；写在 OAuth2 的 `auth.path-prefixes` 下不会生效。

**`type` 的取值**

- `oauth2`：取号并注入 token。
- `api-key`：直接注入 `value`。
- 其他值（如 `oauth`、`apikey`）属于配置错误：请求命中该服务时抛 `FeignAuthConfigurationException`，不受 `fail-on-no-match` 影响。
- 空白 `type` 同样按非法处理。

**`token-prefix`**

对两种认证类型都生效，直接字符串拼接：

```yaml
token-prefix: "Bearer "     # 结果为 Authorization: Bearer eyJ...
```

含空格时务必加引号；不配则为空串（不拼接）。

**Header 名如何决定**

| 认证类型 | 使用的键 | 默认值 |
|---|---|---|
| OAuth2 | `token-header` | `x-token` |
| API Key | `header-name` | `Authorization` |

`resolveHeaderName()` 的口径是：API Key 取 `header-name`（空白则 `Authorization`），其余取 `token-header`（空白则 `x-token`）。

---

### 6.4 `feign-auth.services.<name>.auth.clients[]`

| 配置键 | 类型 | 必填 | 默认值 | 含义 |
|---|---|---|---|---|
| `id` | String | OAuth2 取号必需 | 无 | OAuth2 client id |
| `secret` | String | OAuth2 取号必需 | 无 | OAuth2 client secret |
| `grant-type` | String | 否 | `client_credentials` | 授权类型；具体是否发送见 7.1.4 |
| `path-prefixes` | List\<String\> | 否 | 空 | 该 Client 生效的路径前缀；为空即默认 Client |

规则：

- 一个服务至少要有一个 Client，否则请求期抛 `No OAuth2 clients configured for service: <name>`。
- 有 `path-prefixes` 的 Client 按**最长前缀**竞争；长度相同且都命中时抛配置冲突异常。
- **没有 `path-prefixes` 的 Client 是默认 Client**，只在所有带前缀的 Client 都没命中时使用。
- 默认 Client 最多一个，配两个会抛 `Multiple default OAuth2 clients configured for service: <name>`。
- 每个 Client 独立缓存：缓存键是 `{服务名}:{clientId}`。

---

### 6.5 `feign-auth.services.<name>.auth.request-fields`

用于对接使用非标准字段名的 IdP（如 `appId` / `appSecret`）。

| 配置键 | 类型 | 默认值 | 含义 |
|---|---|---|---|
| `client-id` | String | `client_id` | 请求中 client id 的字段名 |
| `client-secret` | String | `client_secret` | 请求中 client secret 的字段名 |
| `grant-type` | String | `grant_type` | 请求中 grant type 的字段名（仅自定义字段名场景生效） |

**grant-type 的三态规则**（由 `TokenRequestParameters.build` 实现）：

| `request-fields.client-id` / `client-secret` | `clients[].grant-type` | 实际行为 |
|---|---|---|
| 均未配置 | 任意（不填即 `client_credentials`） | 用固定字段名 `grant_type` 发送 |
| 至少配置一个 | 已配置 | 用 `request-fields.grant-type`（未配则 `grant_type`）发送其值 |
| 至少配置一个 | 未配置 | **不发送** grant-type 参数 |

即：一旦自定义了 client-id/client-secret 字段名，`grant-type` 就完全由你显式给出，不会自动补 `client_credentials`。

---

### 6.6 `feign-auth.cache`

| 配置键 | 类型 | 默认值 | 含义 |
|---|---|---|---|
| `provider` | String | `local` | 缓存与锁的实现：`local` 或 `redis`（忽略大小写） |
| `redis.key-prefix` | String | `feign-auth:` | Redis 键前缀；不能为 `null` |

**`provider`**

- `local`：`ConcurrentHashMap` 令牌缓存 + 64 段 `ReentrantLock`。适合单实例。
- `redis`：Redis 令牌缓存（TTL 自动过期）+ Redis 分布式锁。适合多实例共享令牌。
- 其他值（包括空串）启动即失败：`FeignAuth: invalid configuration feign-auth.cache.provider=<值>, must be either 'local' or 'redis'`。
- 大小写不敏感（与 `@ConditionalOnProperty` 的 `havingValue` 口径一致）。

**`redis.key-prefix`**

| 用途 | 键格式 | 示例 |
|---|---|---|
| 令牌缓存 | `{prefix}token:{serviceName}:{clientId}` | `feign-auth:token:order:my-client` |
| 分布式锁 | `{prefix}lock:{serviceName}:{clientId}` | `feign-auth:lock:order:my-client` |

- 令牌值只包含 `accessToken` / `ttl` / `expireAt` 三个字段的固定 JSON，不带类型元数据；读取时不依赖值里的类名决定实例化类型。外部写入的坏值会被 WARN 后删除，下次取号重建。
- Redis 中存的是**令牌明文**，请按敏感数据要求配置访问控制与持久化策略。

---

### 6.7 `feign-auth.http`

只影响 starter 内置的**取号** HTTP 客户端，不影响业务 Feign 请求。

| 配置键 | 类型 | 默认值 | 含义 |
|---|---|---|---|
| `connect-timeout` | Duration | `5s` | 建立 TCP 连接的超时 |
| `connection-request-timeout` | Duration | `5s` | 从连接池借用连接的等待超时 |
| `socket-timeout` | Duration | `10s` | 读取响应的超时 |
| `max-total` | int | `200` | 连接池最大连接数 |
| `default-max-per-route` | int | `50` | 单路由最大连接数 |

规则：

- 三个超时都必须**严格大于 0**，且不能小于 1 毫秒（`0` / 负数 / 不足 1ms 都非法）。Apache HttpClient 把 `0` 解释为「无限等待」，starter 不做静默钳制，而是在启动期抛 `FeignAuthConfigurationException` 并点名具体配置键。
- `max-total` 与 `default-max-per-route` 必须 > 0，否则启动期失败。
- 内置客户端已关闭**自动重试**与 **3xx 重定向跟随**：连接中断/超时直接失败；IdP 返回 3xx 按非 2xx 抛异常，凭证不会随重定向发送到 `Location`。
- 该客户端是容器 Bean，上下文关闭时会自动 `close()` 释放连接池。

---

### 6.8 配置绑定约定

- 推荐使用 kebab-case（`token-url`、`expire-ahead-seconds`）；Spring Boot 的宽松绑定同时接受 `tokenUrl`、`TOKEN_URL` 等写法。
- `Duration` 支持 `5s`、`500ms`、`1m` 等写法。
- 环境变量：`feign-auth.cache.provider` 对应 `FEIGNAUTH_CACHE_PROVIDER`（Spring 会丢弃属性名中的短横线、把 `.` 换成 `_`）。因此配置改名前后环境变量名一致，无需调整；写成 `FEIGN_AUTH_...` 反而**不会**生效。
- `feign-auth.services` 是 Map，键名建议只使用字母、数字、短横线。
- 在 IDE 中自动补全依赖 starter 打包的 `META-INF/additional-spring-configuration-metadata.json`。

---

### 6.9 启动期校验一览

`FeignAuthProperties.afterPropertiesSet()` 在启动时校验以下项，失败即抛 `FeignAuthConfigurationException`（消息以 `FeignAuth: invalid configuration <key>=<value>, ...` 开头）：

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

**不在启动期校验**（请求期才失败）：`auth.type` 缺失/非法、`token-url` 为空、`clients` 为空、`client.id`/`secret` 为空、API Key 的 `value` 为空、`method` 非 get/post。

`response-status-field` 配了以后，`expired-token-statuses` 允许业务码（`10001`、`-1` 等），因为此时它还会与响应体字段值比较。

---

## 7. 认证方式详解

### 7.1 OAuth2

#### 7.1.1 取号流程

1. 业务请求进入拦截器，匹配到服务与 Client（`{service}:{clientId}`）。
2. 查缓存：命中且未过期 → 直接注入。
3. 未命中 → 在锁内二次检查 → 仍未命中则发取号请求。
4. 解析响应，写入缓存（TTL 见 7.1.5），注入 Header。

取号请求由内置 Apache HttpClient 发出，路径为 `token-url`：

- `method: post`：参数放请求体，编码由 `token-request-headers` 的 `Content-Type` 决定。
- `method: get`：参数放查询串（`URIBuilder`）。

#### 7.1.2 `client-authentication`

| 取值 | `method: get` | `method: post` |
|---|---|---|
| `auto`（默认） | `Authorization: Basic base64(id:secret)` | 凭证作为请求参数（请求体） |
| `basic` | `Authorization: Basic ...` | `Authorization: Basic ...` |
| `parameters` | 凭证进查询串 | 凭证进请求体 |

补充规则：

- 走 Basic 时，参数表里会**移除**凭证两个字段（字段名取自 `request-fields`，改名后同样生效），`grant_type` 等其余参数照发。
- 如果你在 `token-request-headers` 或 `HeaderCustomizer` 里已经设置了 `Authorization`，以你的配置为准：在 `basic`（或 `get` + `auto`）本该走 Basic 的场景下，starter 不再代发凭证，凭证也不会出现在参数里，只在 DEBUG 记一条说明。`post` + `auto` 时凭证仍按参数发送，你设置的 `Authorization` 会原样带到取号请求上。
- Basic 编码使用 UTF-8。`client_id` 含冒号时无法构造 Basic，会抛 `FeignAuthConfigurationException` 并点名服务与两种修法。
- 显式选 `parameters` 且 `method: get` 时，`client_secret` 会出现在 URL 中；starter 不阻止，但按服务记一次 WARN（每服务一条）。

#### 7.1.3 `token-request-headers` 与请求体编码

POST 取号的请求体编码由 `Content-Type` 决定（未配置默认 `application/json`）：

| `Content-Type` | 请求体 |
|---|---|
| `application/json`、`*/*+json`、`text/json` 等 JSON 族 | JSON 对象 |
| `application/x-www-form-urlencoded` | UTF-8 表单键值对（RFC 6749 惯例） |
| 其他值或无法解析的值 | 首次取号时抛 `FeignAuthConfigurationException`，不会发出头体错配的请求 |
| `application/json;charset=GBK` | JSON，按声明字符集编码 |

> `Content-Type` 可以来自 YAML，也可以由 `HeaderCustomizer` 的 token 请求分支设置；两者最后都会被 `resolve(headers)` 读取。

JSON 编码使用容器的 `ObjectMapper`（见 14.5），失败时抛 `FeignAuthTokenException: Serialize token request failed`。

#### 7.1.4 取号参数

- 默认字段名：`client_id`、`client_secret`，以及 `grant_type`（值为 `clients[].grant-type`，缺省 `client_credentials`）。
- 自定义字段名与 grant-type 发送规则见 6.5。
- 参数值来自 `clients[].id` / `clients[].secret`，均为配置原文。

#### 7.1.5 响应解析

**Token 字段**

- 未配置 `token-field`：依次尝试 `access_token` → `accessToken` → `token`。
- 配置了 `token-field`：按点分路径逐段下钻，如 `data.accessToken`。
  - 路径**没有**层数上限（与 `expires_in` 的搜索不同）。
  - 中间段是标量/数组、叶节点缺失/是对象/是空白串时，统一报 `Token field '<配置原值>' not found or blank in response`。
  - `data[0].token` 这类数组下标写法不支持。
  - 查找时会 `trim()`，但异常文案保留配置原值。
  - 显式配置了 `token-field` 却没命中时**不会**回退到默认字段，避免静默拿到另一个令牌。
  - 叶节点是数字时按文本取用（既有口径）；叶节点是 `null` 或布尔值时的行为未做约定。

**过期时间**

- 优先使用 `token-expires-in-seconds`（> 0）。
- 否则从响应 JSON 中逐层查找（最多 3 层）`expires_in` → `expiresIn` → `expire_in` → `expireIn`。
- 都找不到时默认 **7200 秒**。
- 生效 TTL 计算：`refreshIn = max(1, expiresIn - min(expireAheadSeconds, expiresIn - 1))`，保证至少 1 秒，避免 Redis `SETEX` 非法与缓存立即过期。
- TTL 上限 **86400 秒（1 天）**：超长有效期（含把毫秒当秒返回的错误值）一律按 1 天缓存并每日刷新，避免时间戳溢出导致每请求刷新。

**响应体大小与字符集**

- 读取上限 **64 KiB**：只按实际读到的字节累积，不按 `Content-Length` 预分配。
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

正文超过 512 字符会被截断后再拼进消息。

---

### 7.2 API Key

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
- API Key 不涉及取号、缓存、锁、过期判定；这些配置对它无意义。

---

## 8. 服务与 Client 的匹配规则

### 8.1 第一步：按 authority 过滤

Feign target 的 URL 与每个服务的 `base-url` 各自归一化成 `scheme://host:port`：

- scheme/host 忽略大小写；
- 省略端口补默认端口（`http`→80，`https`→443）；
- 路径不参与这一步比较。

只有 authority 相同的服务才进入下一步。

### 8.2 第二步：剥掉 base-url 的路径前缀

如果配置的 `base-url` 带路径（`https://api.x.com/v1`），请求路径以 `/v1` 开头时先剥掉它，再用剩余路径匹配 `path-prefixes`。

```yaml
base-url: https://api.x.com/v1
# 请求 /v1/orders/1 → 参与匹配的路径是 /orders/1
```

请求路径恰好等于该前缀时，剩余路径按 `/` 处理。

### 8.3 第三步：最长前缀优先 + 兜底

在 authority 相同的候选中：

1. 计算每个服务/Client 的 `path-prefixes` 命中长度，取最长者；
2. 没有任何前缀命中时使用兜底（API Key：`auth.path-prefixes` 为空；OAuth2：存在默认 Client）；
3. 命中长度相同且来自不同服务 → 抛 `Multiple services matched requestPath=...`；
4. 同一服务内命中长度相同且来自不同 Client → 抛 `Multiple OAuth2 clients matched requestPath=...`；
5. 有多个兜底 → 抛 `Multiple fallback services configured for baseUrl=...`。

前缀匹配是**路径段边界**匹配：`/api/measure` 命中 `/api/measure/1`，**不**命中 `/api/measurement/1`。前缀末尾的 `/` 会被忽略。

### 8.4 请求路径归一化

两侧（拦截器与错误解码器）都必须收敛到同一个路径字符串：

- 补全开头 `/`；
- 去掉查询串与 `#` 片段；
- 折叠连续斜杠（`//` → `/`）；
- 去掉末尾斜杠（根路径 `/` 保留）。

因此 `base-url` 写成 `https://api.x.com/api//v1` 也不会导致「注入时命中 A，驱逐时命中 B」。**例外**：`path-prefixes` 里的前缀本身只做 `normalizePath`（补 `/`、去查询串），**不折叠** `//`——把前缀写成 `/api//measure` 匹配不上已折叠的请求路径。

### 8.5 未匹配与非法配置

| 情况 | 行为 |
|---|---|
| 没有任何服务的 authority 匹配 | `fail-on-no-match=true` 抛异常；`false` 记 WARN 后放行 |
| authority 匹配但 `auth` 缺失 | 始终抛异常，并在消息里列出所有非法服务 |
| authority 匹配但 `auth.type` 非法 | 始终抛异常，同上 |
| authority 匹配但没有任何前缀/兜底命中 | 视同「未匹配」，按 `fail-on-no-match` 处理 |

> `@FeignClient` 使用服务发现（`name` 走负载均衡、没有 `url`）时，target 可能是 `http://服务名`，与配置的 `https://真实域名` authority 不同，通常匹配不上。需要显式配置 `url = "${feign-auth.services.<name>.base-url}"`，或临时放开 `fail-on-no-match`（但那样请求不带鉴权，仅适合迁移期）。

---

## 9. Header 注入

> **本节是可选能力，不属于必需项。** 只要认证本身配好了（OAuth2 的 `token-url` + `clients`，或 API Key 的 `value`），下面这些一项都不配，请求照样带上认证 Header 正常发出：
>
> - `request-headers` / `token-request-headers`：默认都是空 Map，不配就不会额外附带静态 Header。
> - `HeaderCustomizer`：容器里没有该 Bean 时，动态阶段不会写入任何 Header。
> - 认证 Header 的名字也有默认值（OAuth2 取 `token-header`，空白则 `x-token`；API Key 取 `header-name`，空白则 `Authorization`），只有对端要求别的名字时才需要显式配置。

### 9.1 注入顺序

业务请求（`HeaderManager.applyRequestHeaders`）：

1. **静态** `auth.request-headers`：按 Map 写入；名称为空白或值为 `null` 的条目会被跳过。
2. **动态** `HeaderCustomizer`：按 Bean 顺序依次调用 `supports` 为 `true` 的定制器；同名 Header 会覆盖静态值。

最终 Header 合并进 `RequestTemplate` 时采用「先清除同名 Header 再写入」的方式，因此 Feign 重试不会让同名 Header 叠加。

> **注意写入次序**：认证 Header 先写入，`request-headers` 与定制器后写入。如果静态 `request-headers` 的键与 `token-header`（OAuth2）或 `header-name`（API Key）同名，**静态值会覆盖认证值**，请求将以静态值发出。不要在这两处使用同名键。

令牌请求（`HeaderManager.buildTokenHeaders`）：

1. 静态 `auth.token-request-headers`；
2. 动态 `HeaderCustomizer#customize(TokenRequestContext, HttpHeaders)`。

### 9.2 静态 Header

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
- `token-request-headers`：只作用于取号请求；其中 `Content-Type` 决定 POST 请求体编码（见 7.1.3）。
- 这两个 Map **不会**自动带到对方那边，需要两边都配时请分别写。

### 9.3 动态 Header

实现 `HeaderCustomizer` 并注册为 Spring Bean：

```java
import io.github.devoracode.feignauth.autoconfigure.FeignAuthProperties;
import io.github.devoracode.feignauth.header.HeaderCustomizer;
import io.github.devoracode.feignauth.header.RequestContext;
import io.github.devoracode.feignauth.header.TokenRequestContext;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;

@Component
@Order(1)
public class TracingCustomizer implements HeaderCustomizer {

    @Override
    public boolean supports(String serviceName, FeignAuthProperties.Service service) {
        return true;   // 对所有服务生效
    }

    @Override
    public void customize(RequestContext ctx, HttpHeaders headers) {
        String traceId = MDC.get("traceId");
        if (traceId != null && !traceId.isEmpty()) {
            headers.set("X-Trace-Id", traceId);
        }
    }
}
```

`RequestContext` 提供：

| 方法 | 含义 |
|---|---|
| `getServiceName()` | 逻辑服务名 |
| `getService()` | 该服务的完整配置对象 |
| `getRequestPath()` | 归一化后的请求路径 |
| `getMethod()` | HTTP 方法 |
| `getBody()` | 请求体字节（可能为 null） |
| `getQueryParams()` | 查询参数 Map |
| `getTemplate()` | 完整 `RequestTemplate`，可读取任意内容 |

`TokenRequestContext` 提供 `getServiceName()`、`getService()`、`getClient()`、`getParameters()`（取号参数，只读）。

**行为约定**

- 多个定制器按 Spring Bean 自然顺序执行，用 `@Order` 控制。
- 同一个定制器可以同时覆写两个 `customize` 重载：业务请求走第一个，取号请求走第二个。
- 定制器抛异常时会先记一条 ERROR（含定制器类名与服务名），再把异常原样抛出，请求失败。
- `supports` 也会在取号请求前被调用；不想介入取号就只覆写业务请求那个重载。
- **重试会重复执行定制器**：Feign 重试时拦截器会再跑一遍，HMAC 时间戳/nonce 会被消耗两次。定制器应保持无副作用。

---

## 10. 令牌缓存与并发控制

### 10.1 缓存键

`{服务名}:{clientId}`。本地缓存与 Redis 都以此为基础；Redis 额外加前缀与 `token:`：

```text
本地：order:my-client
Redis：feign-auth:token:order:my-client
```

因此：

- 同一服务不同 Client 互不干扰；
- 不同服务即使 clientId 相同也互不干扰；
- 改了服务名或 clientId 等于换了一份缓存，旧条目会按 TTL 自然过期。

### 10.2 本地缓存（`provider: local`）

- `ConcurrentHashMap` 存储，线程安全。
- 读取时若发现 `expireAt` 已过，删除并返回 null（懒清理）。
- 无额外依赖，适合单实例部署。

### 10.3 Redis 缓存（`provider: redis`）

- 使用 Redis TTL 自动过期，多实例共享。
- 值为固定 JSON（`accessToken` / `ttl` / `expireAt`），读写使用 starter 自建的 `StringRedisSerializer` + 专用 JSON 序列化器，**不借用**容器里已有的 `RedisTemplate`，因此其他组件的序列化配置不会破坏令牌读写。
- 读取到无法反序列化的值或异常类型时：WARN 后删除该键，下次取号按当前格式重建。
- 需要容器中存在 `RedisConnectionFactory`。缺失时启动失败：

```text
FeignAuth: feign-auth.cache.provider=redis but no RedisConnectionFactory is available in the context;
add spring-boot-starter-data-redis and configure spring.redis.*, or set feign-auth.cache.provider=local
for the in-memory cache
```

### 10.4 锁

| Provider | 实现 | 说明 |
|---|---|---|
| `local` | 64 个 `ReentrantLock`，按 key 哈希取模 | 单实例内互斥；不同 key 可能共享分段，只影响并发度，不影响正确性 |
| `redis` | `SET NX PX` + Lua 解锁 | 分布式互斥；锁 TTL 30 秒，重试间隔 200ms，最多 60 次（约 12 秒）后抛 `Failed to acquire lock for key: <key>` |

Redis 解锁使用 Lua 脚本比对持有者标识后再 `DEL`，不会误删他人的锁；解锁失败只记 WARN，锁会随 TTL 过期。

### 10.5 取号的「双重检查」

`TokenFetcher.getCachedOrFetch` 的流程：

1. 锁外读一次缓存（大多数请求走这里，命中即返回）；
2. 未命中 → 获取锁 → 再读一次（避免并发重复取号）；
3. 仍未命中 → 取号 → 写缓存。

这保证同一 `{服务名}:{clientId}` 在单实例内最多一个线程真正取号（Redis 模式下多实例也互斥）。

---

## 11. 过期令牌识别与自动重放

### 11.1 判定条件

远端响应满足以下任一条件时，认为缓存的令牌已过期：

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

`response-status-field` 支持 `data.code` 这样的点分路径；只支持对象字段，不支持数组下标。它只在**非 2xx** 响应上生效——`ErrorDecoder` 本来就不会处理 2xx。

### 11.2 驱逐与重放

判定过期后：

1. 调用 `invalidateToken` 删除缓存；
2. 删除成功 → 抛 `RetryableException`，Feign 的 `Retryer` 重放请求，重新取号并注入新令牌；
3. 删除失败（冷却期内或缓存已空）→ 抛不可重试的 `FeignAuthTokenException`，消息含 `token eviction skipped (cooldown or cache empty), request not retried`。

### 11.3 30 秒冷却

同一 `{服务名}:{clientId}` 的驱逐有硬编码 **30 秒冷却**：

- 冷却自「确实删掉了缓存令牌」起算；空转不写入冷却时间戳。
- 冷却期内的重复过期响应不再清缓存、不再重放，直接抛 `FeignAuthTokenException`。
- 目的：远端持续 401 时不形成「401 → 刷新 → 401」风暴轰击 IdP。
- 冷却期内的 WARN：`FeignAuth [oauth2] token eviction suppressed within cooldown for service='...', clientId='...'`。

### 11.4 默认 Retryer

`FeignClientConfig` 注册：

```java
new Retryer.Default(100, TimeUnit.SECONDS.toMillis(1), 2)
```

即：首次失败后等待约 100ms（上限 1s）重试一次，**总共 2 次尝试**。

- 连接/读取超时等 I/O 异常同样会被这个 Retryer 重试。
- 带 `@ConditionalOnMissingBean(Retryer.class)`：你在该客户端的配置中先注册自己的 `Retryer`，starter 自动让位。
- 想完全关闭重放：

```java
@Configuration
public class NoReplayConfig {
    @Bean
    public Retryer noReplayRetryer() {
        return Retryer.NEVER_RETRY;
    }
}
```

```java
@FeignClient(
    name = "orders",
    url = "${feign-auth.services.orders.base-url}",
    configuration = {NoReplayConfig.class, FeignClientConfig.class}
)
public interface OrdersClient { ... }
```

注意：

- 自定义配置要排在 `FeignClientConfig` 之前；顺序无法保证时给自定义 `Retryer` 加 `@Primary`。
- `Retryer.NEVER_RETRY` 会连「令牌过期后的那次重放」一起关掉：驱逐仍会发生（缓存被清，下次取号拿到新令牌），但当前请求直接把异常抛给业务方。
- 无参 `new Retryer.Default()` 是 5 次尝试，不能用来关闭重放。
- **非幂等接口**（如创建订单）请设计幂等键：超时/401 后的重放可能造成重复提交。

---

## 12. 异常与排障

### 12.1 异常类型

| 异常 | 抛出时机 | 含义 |
|---|---|---|
| `FeignAuthConfigurationException` | 启动期 / 请求期 | 配置错误：键值非法、服务未匹配、auth 缺失、Client 缺失、token-url 非法、Content-Type 不支持等 |
| `FeignAuthTokenException` | 请求期 | 取号失败、远程返回错误、驱逐失败、锁获取失败等 |
| `RetryableException` | 请求期（ErrorDecoder） | 令牌过期且已成功驱逐，交给 Feign Retryer 重放 |
| `feign.FeignException` | 响应链路 | 通常作为 `FeignAuthTokenException` 的 **cause** 出现；只有 `Response.request()` 为空等极少数情况才由 Feign 默认解码器直接返回 |

`FeignAuthTokenException` 对远程错误的统一消息格式：

```text
FeignAuth: service '<serviceName>' returned <status>, <说明>
```

说明按状态码表驱动：

| 状态码 | 说明 |
|---|---|
| 401 | current credentials are not allowed to access this endpoint |
| 403 | the remote service explicitly rejected this request |
| 404 | the target resource or path does not exist |
| 429 | the remote service rate limit has been exceeded |
| 5xx | the remote service is temporarily unavailable |
| 其他 | request failed on the remote side |

cause 保留原始 `FeignException`，正文仍可从 cause 中读取。

### 12.2 常见报错速查

| 消息 | 原因 | 处理 |
|---|---|---|
| `FeignAuth: no service config matched for baseUrl=..., path=...` | target 与所有 `base-url` 的 scheme/host/port 不一致；或请求路径没命中任何前缀且无兜底 | 核对 `base-url` 与 `@FeignClient(url)`；确认前缀/兜底配置 |
| 消息后半段 `configured services: [name -> url]` | 同上 | 用清单快速对比拼写、端口、协议 |
| `FeignAuth: service(s) matched baseUrl=... but have invalid auth config: <name> (unsupported auth.type '...')` | `auth.type` 拼错或未配置 | 改为 `oauth2` / `api-key` |
| `... (auth config is missing, expected auth.type 'oauth2' or 'api-key')` | 服务下缺 `auth` | 补 `auth` 块 |
| `FeignAuth: service '<name>' has no auth config` | 同上（拦截器侧防御） | 同上 |
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
| `FeignAuth: invalid configuration feign-auth.http.*` | 超时/池大小越界 | 见 6.7 |
| `FeignAuth: feign-auth.cache.provider=redis but no RedisConnectionFactory...` | 未引入 data-redis 或排除了 RedisAutoConfiguration | 补依赖并配置 `spring.redis.*`，或改 `local` |
| `Token request failed: 401, error=invalid_client...` | IdP 拒绝凭证 | 核对 id/secret、grant-type、认证方式 |
| `Token field '...' not found or blank in response` | `token-field` 路径不匹配 | 对照实际响应体 |
| `Token response exceeds 65536 bytes...` | 2xx 响应体超过 64 KiB | 检查 IdP 是否返回异常内容 |
| `Invalid Content-Type in token-request-headers: '...'` | Content-Type 无法解析 | 修正值 |
| `Unsupported OAuth2 token request Content-Type: '...'` | 不是 JSON 族或表单 | 只支持这两类 |
| `Unsupported OAuth2 token method: ...` | `method` 不是 get/post | 修正 |
| `FeignAuth: client id of service '...' cannot be used with client-authentication=basic` | client id 含冒号 | 改 id 或改 `parameters` |
| `Failed to acquire lock for key: ...` | Redis 锁 60 次重试仍失败 | 检查是否有长事务占锁/Redis 故障 |
| `FeignAuth: token eviction failed for service='...'` | Redis 驱逐异常 | 看 cause |
| `Invalid token-url: <原值>` | `token-url` 缺协议或含非法字符 | 补 `https://` |

### 12.3 排查步骤建议

1. **先看启动日志**：配置校验类错误会在启动期直接失败。
2. **再确认 target**：`@FeignClient(url = ...)` 展开后的实际 URL 是否为预期域名与端口。
3. **打开 DEBUG**：`logging.level.io.github.devoracode.feignauth=DEBUG`，可看到匹配结果、Header 注入、过期判定等细节。
4. **取号问题看异常 cause**：`FeignAuthTokenException` 的 cause 链里有 IdP 的状态码与正文片段。
5. **Redis 模式直接查键**：`GET feign-auth:token:<service>:<clientId>`。

### 12.4 三个完整排障实例

每个实例按「原文 → 逐句拆解 → 改哪里 → 怎么确认改对」走一遍。报错文字取自源码里的消息模板，示例中的服务名与域名是虚构的。

**实例一：`no service config matched`**

```text
FeignAuthConfigurationException: FeignAuth: no service config matched for
baseUrl=http://api.example.com, path=/api/orders/1; configured services:
[order -> https://api.example.com]; the Feign target URL must point to the same
scheme/host/port as feign-auth.services.<name>.base-url (default ports 80/443 are
normalized, a base-url path is treated as an outer prefix for path matching);
set feign-auth.fail-on-no-match=false to send unmatched requests without auth headers
```

- 前半句 `baseUrl=http://api.example.com` 是 Feign **实际** target 拆出来的 `scheme://host:port`，来自 `@FeignClient(url = ...)`。
- `configured services: [order -> https://api.example.com]` 是你 YAML 里配的每一项。
- 两者只差在 `http` 与 `https`——协议不同就不算同一个 authority，所以匹配不上。

改法二选一：把 `@FeignClient(url = "${feign-auth.services.order.base-url}")` 改成引用配置（推荐：两边引用同一个值，不会再对不齐）；或把 YAML 的 `base-url` 改成 `http://api.example.com`。生产环境应该统一走 HTTPS，不要为了跑通把 `base-url` 降级。

确认：再发一次请求，日志出现 `fetching new token`（INFO）与 `service='order' header='x-token' path=/api/orders/1`（DEBUG），说明这次匹配上了。

**实例二：取号被 IdP 拒绝（报文编码对不上）**

```text
ERROR ... FeignAuthRequestInterceptor : FeignAuth [oauth2] failed to obtain token for
service='order', path=/api/orders/1: Token request failed: 400, error=invalid_request,
error_description=...
```

冒号后面这一段是 starter 从 IdP 响应体里原样带出来的（`error` / `error_description` 字段，超过 512 字符会截断），不是 starter 自己造的错。

- 先分清责任：按 4.9 用 curl 手工取一次号。表单 curl 能成功、starter 失败，说明对方只认表单，而 starter 默认 POST 发的是 **JSON**。
- 改法：显式声明表单编码，starter 会同时把请求体和这个头一起按表单发：

```yaml
auth:
  token-request-headers:
    Content-Type: application/x-www-form-urlencoded
```

- 如果 curl 也失败，就不要动 starter 配置，回去核对 `client_id` / `client_secret` / `grant_type` 与认证方式（`client-authentication`）。
- 顺带一条：`Content-Type` 写成既不是 JSON 族也不是表单的值，首次取号会抛 `Unsupported OAuth2 token request Content-Type: '...'`，请求根本不会发出，这是有意的（避免头体错配）。

**实例三：令牌能取到，对端却一直 401，而且日志里全是 `fetching new token`**

```text
INFO  TokenFetcher : FeignAuth [oauth2] fetching new token for service='order', clientId='my-client'
WARN  TokenFetcher : FeignAuth [oauth2] token eviction suppressed within cooldown for service='order', clientId='my-client'
```

- 这是「每次调用都被 401 → 命中默认 `expired-token-statuses: [401, 421, 423]` → 驱逐令牌并重新取号」的循环，不是 IdP 挂了。
- 真正的原因几乎都在 Header 写法上：对端要求 `Authorization: Bearer <token>`，而 starter 默认注的是 `x-token: <token>`。

```yaml
auth:
  token-header: Authorization
  token-prefix: "Bearer "      # 尾部空格必须保留，见 5.3
```

- 如果你确认对端的过期信号不是 401（例如网关统一用 `421`，或者错误码在响应体的业务字段里），就收窄判定条件，别把与令牌无关的 401 也算成过期：配 `expired-token-statuses`，必要时配 `response-status-field`（见 11.1）。
- 30 秒冷却只是挡住同一对 `{服务名}:{clientId}` 的重复驱逐，不会替你修好 Header；日志里那条 `token eviction suppressed within cooldown` 正说明你在被 401 反复打到。

---

## 13. 日志与可观测性

| Logger | 级别 | 场景 |
|---|---|---|
| `...feign.FeignAuthRequestInterceptor` | DEBUG | 每个请求注入了哪个 Header（不含令牌值） |
| `...feign.FeignAuthRequestInterceptor` | WARN | `fail-on-no-match=false` 时未匹配服务（每 baseUrl 一次） |
| `...feign.FeignAuthStatusHandler` | WARN | 令牌过期驱逐/冷却、读取响应体失败、URL 解析失败（每 URL 一次） |
| `...oauth2.TokenFetcher` | INFO | `fetching new token`、`token evicted` |
| `...oauth2.TokenFetcher` | WARN | 冷却期内驱逐被抑制 |
| `...oauth2.TokenEndpointWarnings` | WARN / DEBUG | 凭证进查询串（每服务一次）、非 HTTPS `token-url`（每服务一次）、Authorization 已配置所以不代发（DEBUG） |
| `...oauth2.OAuth2TokenRequestClient` | WARN | `Failed to close token response: ...` |
| `...oauth2.lock.RedisLockProvider` | WARN | 解锁失败，锁会随 TTL 过期 |
| `...oauth2.store.RedisTokenStore` | WARN | 反序列化失败/值类型异常，已删除坏键 |

**这些日志不会打印令牌或密钥本身**；但 Redis 中存的是令牌明文，访问控制需自行保证。

`token-url` 安全提醒：明文 `http://` 且非回环地址时，每个服务记一次 WARN（`localhost`、`127.*`、`::1` 不提示）。

---

## 14. 扩展点

所有默认 Bean 都带 `@ConditionalOnMissingBean`，你可以用同类型 Bean 替换。

### 14.1 `HeaderCustomizer`

见 9.3。最常用的扩展点，用于签名、Trace、租户 Header 等。

### 14.2 自定义 `TokenStore`

```java
@Bean
public TokenStore tokenStore() {
    return new MyTokenStore();   // 实现 get / put / remove
}
```

- 提供后 starter 的本地/Redis TokenStore 让位。
- 只替换 `TokenStore` 而保留默认 `LockProvider` 时，Redis provider 的启动校验仍会要求 Redis 连接（见 10.3）。

### 14.3 自定义 `LockProvider`

```java
@Bean
public LockProvider lockProvider() {
    return new MyLockProvider();   // 实现 <T> T execute(String key, Callable<T> callable)
}
```

### 14.4 自定义 `TokenFetcher`

```java
@Bean
public TokenFetcher tokenFetcher(...) {
    return new MyTokenFetcher(...);
}
```

- 一旦容器中存在 `TokenFetcher`，starter 的 `TokenFetcherConfiguration` 整体让位：不再注册内置取号客户端，也不会触发 Redis 连接校验。`TokenStore` / `LockProvider` 的 Bean 定义仍可能存在（取决于 `cache.provider`），但没有任何组件消费它们。
- `FeignClientConfig` 通过 `ObjectProvider` 取用，不会因构造顺序报错。

### 14.5 自定义 `ObjectMapper`

自动配置里的 `ObjectMapper` 是 `@ConditionalOnMissingBean`：应用中已有（如 Spring Boot 的 Jackson 自动配置）就用你的，用于令牌响应解析与 JSON 请求体序列化。

### 14.6 替换取号 HTTP 客户端

声明自己的 `OAuth2TokenRequestClient` Bean（该类不是接口，需要继承并覆写 `requestToken`）：

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

starter 默认实现带 `@ConditionalOnMissingBean`，会自动让位。若你的实现持有资源，实现 `AutoCloseable` 以便容器关闭时释放。

### 14.7 `ServiceMatcher` / `OAuth2ClientMatcher` / `HeaderManager`

同样可以注册同类型 Bean 覆盖，适用于需要自定义匹配策略或完全接管 Header 管线的场景。

> 替换 `TokenStore` 与 `LockProvider` 时，**建议两者一起替换**：只替换其一时，另一侧仍按 `cache.provider` 装配，`provider: redis` 下仍需要 `RedisConnectionFactory`。

---

## 15. 场景合集

> 以下场景均使用 2.0.0 的 `feign-auth` 根键。示例省略了 `@FeignClient` 的 `name` 与方法，只保留关键部分。

### 场景 1：单服务 + 默认 POST 取号

最简形态。`auto` 且 `post` → 凭证在请求体（`client_secret_post`）。

```yaml
feign-auth:
  services:
    order:
      base-url: https://api.example.com
      auth:
        type: oauth2
        token-url: https://api.example.com/oauth/token
        clients:
          - id: order-client
            secret: ${ORDER_SECRET}
```

```java
@FeignClient(name = "order", url = "${feign-auth.services.order.base-url}",
    configuration = FeignClientConfig.class)
public interface OrderClient { }
```

### 场景 2：GET 取号 + Basic（RFC 6749 推荐）

```yaml
auth:
  type: oauth2
  token-url: https://api.example.com/oauth/token
  method: get
  # client-authentication 默认 auto → GET 自动走 Authorization: Basic
  clients:
    - id: my-id
      secret: my-secret
```

实际请求：

```text
GET /oauth/token?grant_type=client_credentials
Authorization: Basic base64(my-id:my-secret)
```

### 场景 3：老 IdP 只认查询参数

IdP 不支持 Basic，只能用 GET + query。选 `parameters`，starter 会记一条 WARN 提示凭证泄露风险。

```yaml
auth:
  type: oauth2
  token-url: http://legacy-idp.internal/oauth/token   # 非回环的 http 另有一条 WARN
  method: get
  client-authentication: parameters
  clients:
    - id: legacy-id
      secret: legacy-secret
```

### 场景 4：令牌在嵌套字段 + 显式 TTL

响应形如 `{"code":0,"data":{"accessToken":"...","expires":7200}}`：

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

### 场景 5：自定义取号字段名（appId / appSecret）

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

请求体变为 `{"appId":"...","appSecret":"...","grantType":"client_credentials"}`。
若省略 `grant-type`，则**不发送**该参数（见 6.5 三态规则）。

### 场景 6：一个服务内多套凭证（按路径）

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
          - id: default-client          # 其余路径都用它
            secret: default-secret
```

- `/api/measure/event/1` → `event-client`
- `/api/measure/telemetry/x` → `telemetry-client`
- `/api/measure/other` → `default-client`

### 场景 7：同一个 host 下多个服务（base-url 带路径）

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

> 同一个服务下不同请求路径需要不同 token 时，用「场景 6」的 Client 路由更合适；只有需要不同 `token-url`、不同 `token-header` 等整体配置差异时才拆成多个服务。

### 场景 8：多个不同域名

```yaml
feign-auth:
  services:
    order:
      base-url: https://orders.example.com
      auth: { type: oauth2, token-url: https://orders.example.com/oauth/token,
              clients: [{ id: order-client, secret: ${ORDER_SECRET} }] }
    user:
      base-url: https://users.example.com
      auth: { type: api-key, value: ${USER_KEY} }
```

每个 `@FeignClient` 的 `url` 指向对应 `base-url` 即可。

### 场景 9：纯 API Key

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
```

请求头：`Authorization: Bearer sk-xxxxxxxx`。

### 场景 10：同 host 多个 API Key + 兜底

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

### 场景 11：同一个 host 混合认证方式

`/public/**` 用 API Key，其余用 OAuth2：

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

注意：两种认证类型的服务是**同一组候选**，一起按「最长前缀 → 兜底」竞争。上面 OAuth2 服务靠「无前缀的默认 Client」成为兜底项，API Key 服务靠 `/public` 前缀参与竞争，两者不冲突。但如果 API Key 服务也没有 `path-prefixes`，同一 host 就会出现两个兜底，直接抛 `Multiple fallback services configured for baseUrl=...`。因此**同一 host 只能有一个兜底**。

### 场景 12：静态 Header + 动态签名

```yaml
auth:
  request-headers:
    X-App-Id: order-portal
    X-Version: v2
```

```java
@Component
@Order(2)
public class OrderSignatureCustomizer implements HeaderCustomizer {

    private final HmacSigner signer;

    public OrderSignatureCustomizer(HmacSigner signer) {
        this.signer = signer;
    }

    @Override
    public boolean supports(String serviceName, FeignAuthProperties.Service service) {
        return "order".equals(serviceName);
    }

    @Override
    public void customize(RequestContext ctx, HttpHeaders headers) {
        headers.set("X-Timestamp", String.valueOf(System.currentTimeMillis()));
        headers.set("X-Signature", this.signer.sign(
            ctx.getMethod(), ctx.getRequestPath(), ctx.getQueryParams(), ctx.getBody()));
    }
}
```

### 场景 13：给取号请求加自定义 Header

某些 IdP 要求额外的 `X-App-Id` 或签名头：

```java
@Component
public class TokenHeaderCustomizer implements HeaderCustomizer {

    @Override
    public boolean supports(String serviceName, FeignAuthProperties.Service service) {
        return "measure".equals(serviceName);
    }

    @Override
    public void customize(TokenRequestContext ctx, HttpHeaders headers) {
        headers.set("X-App-Id", ctx.getClient().getId());
    }
}
```

### 场景 14：Redis 多实例共享令牌

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

并确保 POM 中显式声明了 `spring-boot-starter-data-redis`。多实例下同一个 `{service}:{clientId}` 只会有一个实例真正取号（Redis 锁），其余实例读到同一份令牌。

### 场景 15：用业务状态码识别过期令牌

```yaml
auth:
  type: oauth2
  token-url: https://api.example.com/oauth/token
  response-status-field: code
  expired-token-statuses: [401, 10001, -1]
  clients: [{ id: c, secret: s }]
```

响应 `400 {"code":10001,"msg":"token expired"}` → 驱逐并重放。

### 场景 16：自定义过期状态码

某些网关用 `419` 或 `403` 表示令牌失效：

```yaml
auth:
  expired-token-statuses: [401, 403]
```

一旦配置了列表，默认的 `[401, 421, 423]` 不再生效，需自行补全。
未配置 `response-status-field` 时，列表元素必须在 100–599 之间，否则启动失败。

### 场景 17：关闭自动重放（非幂等接口）

```java
@Configuration
public class NoReplayConfig {
    @Bean
    public Retryer noReplayRetryer() {
        return Retryer.NEVER_RETRY;
    }
}
```

```java
@FeignClient(name = "orders", url = "${feign-auth.services.orders.base-url}",
    configuration = {NoReplayConfig.class, FeignClientConfig.class})
public interface OrdersClient { }
```

### 场景 18：只调整重试次数

```java
// 总共 3 次尝试：首次 + 2 次重试
@Bean
public Retryer threeAttemptsRetryer() {
    return new Retryer.Default(100, TimeUnit.SECONDS.toMillis(1), 3);
}
```

### 场景 19：迁移期允许未匹配请求放行

```yaml
feign-auth:
  fail-on-no-match: false
```

仅建议在灰度/迁移期使用：未匹配的请求会**不带任何鉴权头**发出，日志每个 baseUrl 只提醒一次。

### 场景 20：本地开发使用 HTTP 回环地址

```yaml
auth:
  token-url: http://localhost:8080/oauth/token
```

回环地址（`localhost`、`127.*`、`::1`）不会触发非 HTTPS 的 WARN；仍建议生产使用 HTTPS。

### 场景 21：替换缓存实现（自定义 TokenStore + LockProvider）

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

### 场景 22：完全接管取号

```java
@Bean
public TokenFetcher tokenFetcher(FeignAuthProperties properties,
                                 OAuth2ClientMatcher clientMatcher,
                                 TokenStore tokenStore,
                                 LockProvider lockProvider) {
    return new MyTokenFetcher(properties, clientMatcher, new MyTokenClient(), tokenStore, lockProvider);
}
```

提供自己的 `TokenFetcher` 后，starter 的取号客户端、缓存与锁装配整体让位。

### 场景 23：同一服务不同接口用不同 Header 名

`token-header` 是服务级配置，同一服务下所有请求共用。若确实需要同一 token 写入不同 Header，可以：

- 用 `HeaderCustomizer` 在业务请求上补一个副本头（例如同时写 `x-token` 与 `X-Auth-Token`）；
- 或拆成两个服务配置：同一 base-url、**各自带路径前缀的 Client 且都不设默认 Client**，两组前缀互不重叠。注意不要给两边都配默认 Client（同一 host 只能有一个兜底），否则会触发 `Multiple fallback services configured`。

### 场景 24：状态码在数组里时的处理

`response-status-field` 只支持对象路径（`data.code`），不支持 `data[0].code`。若状态码在数组里，需要业务侧自行转换，或让服务端在顶层/对象里给出状态码。

---

## 16. 生产检查清单

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

## 17. 升级到 2.0.0

2.0.0 有四项破坏性变更，升级时逐项检查：

### 17.1 配置根键 `feign` → `feign-auth`

```yaml
# 1.x
feign:
  services: ...

# 2.0.0
feign-auth:
  services: ...
```

旧键**静默失效**：应用能正常启动，`services` 为空，每个请求运行期抛 `FeignAuthConfigurationException`。必须整片改名，不能只改一部分。

### 17.2 取消全局 `auth` 层

| 1.x | 2.0.0 |
|---|---|
| `feign.auth.cache.*` | `feign-auth.cache.*` |
| `feign.auth.http.*` | `feign-auth.http.*` |
| `feign.auth.fail-on-no-match` | `feign-auth.fail-on-no-match` |

各服务下的 `feign.services.<name>.auth.*` 层级不变，只是根键改为 `feign-auth.services.<name>.auth.*`。

### 17.3 `@FeignClient` 占位符同步改名

```java
// 1.x
url = "${feign.services.order.base-url}"
// 2.0.0
url = "${feign-auth.services.order.base-url}"
```

这类写法**不会编译报错**，漏改会让 Feign 拿到解析不出的字面量。

### 17.4 环境变量名不变

`SystemEnvironmentPropertyMapper` 映射时会丢掉短横线、把 `.` 换成 `_`，因此：

```text
feign-auth.cache.provider   → FEIGNAUTH_CACHE_PROVIDER
feign.services.x.base-url   → FEIGN_SERVICES_X_BASEURL  （旧）
feign-auth.services.x.base-url → FEIGNAUTH_SERVICES_X_BASEURL（新）
```

注意：`FEIGN_AUTH_...`（带下划线分段）在改名前后都不会生效，不必为其调整。

### 17.5 自定义 `@Bean FeignAuthProperties` 的代码

`FeignAuthProperties.GlobalAuth`、`getAuth()`、`setAuth()` 已删除，改为：

- `getCache()` / `setCache(...)`
- `getHttp()` / `setHttp(...)`
- `isFailOnNoMatch()` / `setFailOnNoMatch(...)`
- `getServices()`

显式 `setCache(null)` / `setHttp(null)` 会回退默认对象，不会 NPE。

### 17.6 `cache.provider: redis` 的项目补依赖

`spring-boot-starter-data-redis` 不再传递引入，需在自身 POM 显式声明。漏声明时启动失败并点名 `feign-auth.cache.provider=redis` 与两种修法。

### 17.7 其它可观察变化

- 两条取号安全告警的 logger 名改为 `io.github.devoracode.feignauth.oauth2.TokenEndpointWarnings`；按 logger 名调级或路由的配置需同步。
- 非 HTTPS `token-url` 的 WARN 去重键加入服务名（同服务多 Client 仍只一条）。
- `PathUtils` 新增公共 API `parseBaseUrl` / `BaseUrlParts`；既有方法签名与语义不变。

---

## 18. 已知边界

1. **`response-status-field` 只在非 2xx 响应上生效**：HTTP 200 但响应体里报 token 过期的接口无法自动识别。
2. **`response-status-field` 只支持对象路径**，不支持数组下标；值必须能转成整数（数字或数字字符串）。
3. **取号 HTTP 客户端不自动重试、不跟随 3xx**：连接失败/超时直接抛 `FeignAuthTokenException`；重试交给 Feign `Retryer`。
4. **`token-url` 缺少协议名**（如 `127.0.0.1:8080/oauth/token`）时，请求期抛 `Invalid token-url: <原值>`；使用非 HTTP(S) 协议时报 `Token request failed: null`，排障时先核对 URL。
5. **自定义 `RequestInterceptor` 改写了 `template.target(...)`** 时，路径归一化无法消除 host/前缀改写差异，可能出现「注入了令牌但不驱逐、不重放」。
6. **`path-prefixes` 中的 `//` 不会被折叠**，而请求路径会被折叠；前缀里不要写连续斜杠。
7. **同一 host 只能有一个兜底**（API Key 无前缀 / OAuth2 有默认 Client），否则报冲突。
8. **`expired-token-statuses` 一旦配置就完全替换默认值**，不会与 `[401, 421, 423]` 合并。
9. **本地缓存是懒清理**：过期条目在被访问时才删除；不访问就不会释放（无后台线程）。
10. **Redis 缓存存令牌明文**，需按敏感数据管理。
11. **服务发现（负载均衡名）通常无法匹配**：匹配依赖 target 的真实 scheme/host/port，请显式给 `url` 或接受 `fail-on-no-match` 的放行语义。
12. **`token-field` 对数组下标、`null`/布尔叶节点没有约定**：不建议依赖这类响应结构。
13. **`@FeignClient` 的 `name` 与配置中的服务名无绑定**：服务名只用于配置引用、缓存键、日志与定制器。
14. **2.0.0 的兼容范围**：README 声明 Spring Boot 2.2.x ~ 2.7.x；更高版本不在当前验证范围内。

---

## 附：默认值速查

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
| `auth.method` | `post` |
| `auth.client-authentication` | `auto` |
| `auth.token-header` | `x-token` |
| `auth.header-name` | `Authorization` |
| `auth.token-prefix` | 空 |
| `auth.expire-ahead-seconds` | `60` |
| `auth.expired-token-statuses` | `[401, 421, 423]` |
| 令牌 TTL（响应未给出时） | `7200s` |
| 令牌 TTL 上限 | `86400s` |
| 取号响应体上限 | `65536` 字节 |
| 令牌驱逐冷却 | `30s` |
| 默认 Retryer | 2 次尝试，初始间隔 100ms，上限 1s |
| Redis 锁 TTL / 重试 | 30s / 200ms × 60 |
| 本地锁分段数 | 64 |
| 请求路径前缀的 token 字段顺序 | `access_token` → `accessToken` → `token` |
| 过期时间字段搜索顺序（最多 3 层） | `expires_in` → `expiresIn` → `expire_in` → `expireIn` |

---

## 附：术语表

按本文用法解释，不是通用定义的完整版本。

| 术语 | 在本文里的意思 | 主要出现在 |
|---|---|---|
| starter | Spring Boot 的「自动装配依赖包」：引进来就自动生效，不需要你写 `@Bean` | 全文 |
| Bean / 容器 | 交给 Spring 管理的对象。starter 的取号器、匹配器、缓存都是 Bean | 3.3、14 |
| OpenFeign / Feign | 把 HTTP 调用写成 Java 接口的框架；`@FeignClient` 标注的那个接口就是你要用的客户端 | 4.2、4.5 |
| 拦截器（`RequestInterceptor`） | 请求发出前必过的一段代码，starter 在这里把令牌塞进 Header | 9 |
| 错误解码器（`ErrorDecoder`） | 收到非 2xx 响应时决定「抛什么异常」的一段代码，starter 在这里判断令牌是否过期 | 11 |
| 重试器（`Retryer`） | 决定失败的请求是否再发一次、间隔多久 | 11.4 |
| OAuth2 | 一种「先拿令牌、再带令牌访问」的授权协议；本文只用 `client_credentials`（机器对机器）这一种模式 | 7.1 |
| IdP | Identity Provider，发令牌的那台服务器（`token-url` 指向它） | 6.3、7.1、12.2 |
| `access_token` / 令牌 | IdP 发给你的访问凭证，有效期内可重复使用，过期后要重新取 | 7.1.5、10 |
| `client_id` / `client_secret` | 应用的身份与密码，用来换令牌；配在 `auth.clients[]` 下 | 6.4 |
| Basic 认证 | 把 `client_id:client_secret` 做 Base64 后放进 `Authorization` 头，而不是放进请求体 | 7.1.2 |
| TTL | 令牌还能活多少秒；starter 用它决定缓存过期时间 | 7.1.5、10.1 |
| Header | HTTP 请求头，键值对；对端靠它认令牌 | 9 |
| authority / `baseUrl` | URL 里的 `scheme://host:port` 部分。starter 用它认服务，`base-url` 上的路径另作前缀用 | 8.1、8.2 |
| 路径前缀 | `clients[].path-prefixes` / `auth.path-prefixes` 里写的路径开头，用来区分同一 host 下的不同服务或凭证 | 8.3 |
| 兜底 / 默认 Client | 没配路径前缀的那一项，前缀都不命中时用它 | 6.4、8.3 |
| 缓存键 | 令牌在缓存里的名字，格式 `{服务名}:{clientId}`；Redis 下还会带 `key-prefix` | 10.1 |
| 驱逐（evict） | 把缓存里的令牌扔掉，下次请求重新取号 | 11.2 |
| 重放（replay） | 驱逐后用新令牌把刚才那个请求再发一次 | 11.2 |
| 幂等 | 同一请求发两次不会造成两次后果；不幂等的接口不能随便重放 | 11.4、16 |
| 锁 / 分布式锁 | 防止多个线程或多个实例同时去取号，只让一个去拿 | 10.4 |
| 双重检查 | 拿到锁后再看一遍缓存，避免白取一次号 | 10.5 |
| kebab-case | 全小写、用短横线分词的写法，如 `token-url` | 6.8 |
| 宽松绑定 | Spring Boot 允许 `token-url`、`tokenUrl`、`TOKEN_URL` 都绑到同一项；推荐统一写 kebab-case | 6.8 |
| `Duration` | 时长写法：`5s`、`500ms`、`1m` | 6.7、6.8 |
| 占位符 `${...}` | YAML/注解里引用别处的值，如 `${ORDER_SECRET}` 引用环境变量 | 4.5、6.8 |
| BOM | 一份「版本清单」依赖，import 之后其它依赖不用逐个写版本号 | 4.2 |
| optional 依赖 | 不向下传递的依赖：Redis 与 feign-core 都是 optional，需要你自己声明 | 2、3.1 |
| 启动期 / 请求期 | 启动期失败=应用起不来；请求期失败=能启动，用到才报错。两者的排查方式不同 | 6.9、12.1 |
| 明文 / HTTPS | `http://` 下密钥与令牌在网络上是可读的；`token-url` 非 HTTPS 时 starter 会告警 | 12、16 |
| nonce / HMAC 签名 | 某些对端要求每次请求带一次性随机数与签名，只能自己实现 `HeaderCustomizer` | 9.3、14.1 |
