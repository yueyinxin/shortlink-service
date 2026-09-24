# 架构设计说明书

| 项目 | shortlink-service |
| --- | --- |
| 版本 | v1.0.0 |
| 最后更新 | 2026-09-24 |

---

## 1. 架构目标与约束

### 1.1 驱动架构决策的核心特征

这个系统的读写特征**极不对称**，这是所有设计决策的出发点：

| 维度 | 跳转链路 | 管理链路 |
| --- | --- | --- |
| 调用频率 | 极高（占总流量 99%+） | 低 |
| 读写比 | 读 100% | 读多写少 |
| 一致性要求 | 最终一致可接受 | 强一致 |
| 鉴权 | 匿名 | 需 JWT |
| 延迟敏感度 | 极高（P99 < 50 ms） | 低 |

**结论**：跳转链路必须与写入、鉴权、统计彻底解耦。这直接推导出后面的缓存策略、异步统计、以及独立的限流维度。

### 1.2 约束

| 约束 | 说明 |
| --- | --- |
| Java 17 | 运行环境已确定 |
| 单实例起步 | 初期流量不需要集群，但必须**无状态**以便随时水平扩展 |
| 必须可容器化 | 交付方式为 Docker Compose |
| 不引入消息队列 | 当前规模下 MQ 是过度设计；用 Redis + 定时任务已满足最终一致性要求（见 ADR-0004） |

---

## 2. 系统上下文

```
        ┌──────────────┐
        │  匿名访客     │  点击短链
        └──────┬───────┘
               │ GET /{code}            无鉴权、高频
               ▼
        ┌──────────────────────────────────────┐
        │                                      │
        │        shortlink-service             │
        │                                      │
        └───────┬──────────────────────┬───────┘
                │                      │
    ┌───────────┴──────┐    ┌──────────┴─────────┐
    │   MySQL 8.0      │    │     Redis 7        │
    │                  │    │                    │
    │ · 用户与链接持久化 │    │ · 跳转缓存（热点读） │
    │ · 统计明细与聚合   │    │ · PV/UV 实时计数    │
    │ · 强一致来源      │    │ · refresh token    │
    │                  │    │ · 限流滑动窗口      │
    └──────────────────┘    └────────────────────┘
               ▲
               │              ┌──────────────┐
               │              │  Prometheus  │  抓取 /actuator/prometheus
    ┌──────────┴───────┐      └──────────────┘
    │  登录用户/API     │
    │  管理短链         │      /api/v1/**  需 JWT
    └──────────────────┘
```

**关键点**：MySQL 是唯一的**真相来源（Source of Truth）**。Redis 中的所有数据都可由 MySQL 重建——这决定了 Redis 故障时系统可以降级而非崩溃。这是可用性设计的基石。

---

## 3. 分层架构

```
┌──────────────────────────────────────────────────────────────────┐
│  接口层 (web)                                                     │
│                                                                  │
│   RedirectController   LinkController   AuthController            │
│   StatsController                                                │
│   ─────────────────────────────────────────────────────────       │
│   JwtAuthenticationFilter   RateLimitFilter                       │
│   GlobalExceptionHandler                                         │
│                                                                  │
│   职责：HTTP 协议适配、DTO ↔ 领域对象转换、参数校验                  │
│   禁止：写业务逻辑、直接访问 Repository                             │
└────────────────────────────┬─────────────────────────────────────┘
                             │ 调用
┌────────────────────────────▼─────────────────────────────────────┐
│  应用层 (application)                                             │
│                                                                  │
│   AuthService   LinkService   RedirectService   StatsService      │
│                                                                  │
│   职责：用例编排、事务边界（@Transactional 只出现在这一层）、         │
│        组合多个领域操作                                             │
│   禁止：包含 HTTP 概念（HttpServletRequest 等）                      │
└────────────────────────────┬─────────────────────────────────────┘
                             │ 调用
┌────────────────────────────▼─────────────────────────────────────┐
│  领域层 (domain)                                                  │
│                                                                  │
│   ShortLink   User   ShortCode   ShortCodeGenerator               │
│   LinkStatus                                                     │
│                                                                  │
│   职责：纯业务规则。如「短链能否跳转」的判定逻辑                      │
│   特征：不依赖 Spring、JPA、Redis —— 可用纯单元测试毫秒级验证          │
└────────────────────────────┬─────────────────────────────────────┘
                             │ 依赖抽象（接口）
┌────────────────────────────▼─────────────────────────────────────┐
│  基础设施层 (infrastructure)                                       │
│                                                                  │
│   ShortLinkRepository (JPA)   LinkCache (Redis)                   │
│   StatsCounter (Redis)   RefreshTokenStore (Redis)                │
│                                                                  │
│   职责：实现领域层定义的接口，隔离外部技术细节                        │
└──────────────────────────────────────────────────────────────────┘
```

### 3.1 依赖方向规则

**依赖只能自上而下，绝不可反向或跨层跳跃。**

```
web ──▶ application ──▶ domain ◀── infrastructure
```

- `domain` **不知道** `infrastructure` 的存在，只定义接口。
- `infrastructure` 实现 `domain` 定义的接口（依赖倒置）。
- 这条规则的目的是：**让核心业务逻辑不依赖任何易变的框架**。当 Redis 换成 Memcached、JPA 换成 MyBatis 时，`domain` 层一行都不用改。

> **可验证性**：这个约束可以通过 ArchUnit 测试自动守护——`domain` 包下不允许出现 `org.springframework` 或 `jakarta.persistence` 的 import。本项目在 `ArchitectureTest` 中实现了这条检查。这也是防止架构随时间腐化的有效手段：**架构约束必须能被自动验证，否则一定会被违反**。

---

## 4. 代码组织：按功能垂直切分

```
io.github.yueyinxin.shortlink
├── ShortlinkServiceApplication.java
│
├── common/                         横切关注点
│   ├── exception/
│   │   ├── BusinessException.java      业务异常基类（携带 ErrorCode）
│   │   ├── ErrorCode.java              错误码枚举（HTTP 状态 + 码 + 文案）
│   │   └── GlobalExceptionHandler.java @RestControllerAdvice
│   ├── response/
│   │   ├── ApiResponse.java            统一响应包装
│   │   └── PageResponse.java           统一分页响应
│   └── util/
│       ├── Base62.java                 编解码
│       └── ClientIpResolver.java       真实 IP 提取（含 X-Forwarded-For）
│
├── config/                         框架配置
│   ├── SecurityConfig.java             SecurityFilterChain、密码编码器
│   ├── RedisConfig.java                RedisTemplate 序列化、Lua 脚本注册
│   ├── OpenApiConfig.java              Swagger 元信息与 JWT 安全方案
│   ├── AsyncConfig.java                统计异步执行器
│   ├── JpaConfig.java                  JPA 审计（@EnableJpaAuditing）
│   └── properties/                     类型安全的 @ConfigurationProperties
│
├── auth/                           功能域：认证
│   ├── AuthController.java
│   ├── AuthService.java
│   ├── dto/
│   ├── jwt/
│   │   ├── JwtTokenProvider.java       签发/解析
│   │   ├── JwtAuthenticationFilter.java
│   │   └── AuthenticatedUser.java      认证主体
│   ├── RefreshTokenStore.java          接口
│   └── infrastructure/RedisRefreshTokenStore.java   实现
│
├── user/                           功能域：用户
│   ├── User.java
│   ├── UserRepository.java
│   └── UserService.java
│
├── link/                           功能域：短链（核心）
│   ├── ShortLink.java                  实体 + 领域行为
│   ├── ShortCode.java                  值对象（格式校验、保留字检查）
│   ├── LinkStatus.java
│   ├── ShortCodeGenerator.java         领域服务：短码生成
│   ├── ShortLinkRepository.java
│   ├── LinkCache.java                  接口
│   ├── LinkService.java                应用服务：创建/管理
│   ├── RedirectService.java            应用服务：跳转（核心热路径）
│   ├── LinkController.java
│   ├── RedirectController.java
│   ├── dto/
│   └── infrastructure/
│       ├── JpaShortLinkRepository.java
│       └── RedisLinkCache.java
│
└── stats/                          功能域：统计
    ├── LinkStat.java                   按天聚合实体
    ├── StatsCounter.java               接口：Redis 计数
    ├── StatsService.java               查询（合并 Redis 增量）
    ├── StatsFlushScheduler.java        定时刷库
    ├── StatsController.java
    └── infrastructure/
        ├── RedisStatsCounter.java
        └── JpaLinkStatRepository.java
```

### 4.1 为什么按功能切分而不是按技术分层

| | 按技术分层（controller/service/repository 顶层包） | 按功能垂直切分（本项目） |
| --- | --- | --- |
| 定位代码 | 改一个功能要跳 4 个顶层目录 | 相关代码在一个目录内 |
| 包内聚性 | 低，一个包内都是不相关的类 | 高 |
| 拆分为微服务 | 需要跨包大范围搬迁 | 整个功能域目录直接搬走 |
| 对新人友好度 | 分层脉络清晰 | 需要先理解功能划分 |

**决策**：本项目采用垂直切分。理由是这个系统的功能域边界非常清晰（auth / link / stats），且垂直切分让「未来拆微服务」的成本降到最低。同时每个功能域**内部**仍保持 web → application → domain 的分层，兼顾了两种方式的优点。

---

## 5. 核心链路设计

### 5.1 跳转链路（热路径）

这是全系统最关键的一条路径，设计目标是把它的耗时压到极致。

```
① 请求到达 GET /{code}
        │
        ▼
② RateLimitFilter ── 超限 ──▶ 429
        │  (Redis Lua 原子计数)
        ▼
③ RedirectController.redirect(code)
        │
        ▼
④ RedirectService.resolve(code)
        │
        ▼
⑤ LinkCache.get(code) ──── 命中 ──▶ ⑥ 返回缓存对象
        │
        │ 未命中
        ▼
⑦ ShortLinkRepository.findByCode(code)
        │
        ├─ 不存在 ──▶ 缓存空值(TTL 60s) ──▶ 404
        │
        ▼
⑧ 写回缓存 (TTL 24h ± 10% 抖动)
        │
        ▼
⑥ 状态判定（领域逻辑）
        │
        ├─ 已过期 ──▶ 410
        ├─ 已禁用 ──▶ 403
        │
        ▼
⑨ 记录访问（异步，不阻塞响应）
   StatsCounter.record(code, clientIp)
        │
        ▼
⑩ 302 Location: originalUrl
```

**关键设计点：**

| 步骤 | 设计 | 理由 |
| --- | --- | --- |
| ② | 限流在 Filter 中做，早于业务逻辑 | 被限流的请求不应触碰任何业务代码，成本最低 |
| ⑤ | 缓存的是整个短链对象，不是 URL 字符串 | 状态判定需要 `status` / `expiresAt`，缓存整个对象才能命中后不做二次查询 |
| ⑧ | TTL 加 ±10% 随机抖动 | 防止大量短链同时过期造成**缓存雪崩** |
| ⑨ | 统计异步化 | 跳转响应时间不包含任何写操作 |
| ⑦→⑧ | 空值也缓存 | 防止不存在的短码反复穿透到数据库（**缓存穿透**） |

### 5.2 短码生成

短码生成是唯一需要"无冲突 + 不可枚举"两个属性的地方，采用**混淆自增序列**方案：

```
数据库自增 BIGINT seq
        │
        ▼
   打散：permuted = (seq × A + B) mod 62^6      A 与 62^6 互质 ⟹ 双射
        │
        ▼
   编码：Base62(permuted) 补齐到 6 位
        │
        ▼
   短码："aB3x9K"
```

**为什么这个方案同时满足两个要求：**

- **无冲突**：`(seq × A + B) mod M` 在 `gcd(A, M) = 1` 时是一个**双射**。不同的 seq 必然映射到不同的 permuted，不可能碰撞。这不同于"随机生成 + 冲突重试"——后者在高并发下会重试甚至失败。
- **不可枚举**：连续 seq 经过线性同余打散后，在 Base62 空间中的分布是跳跃的。攻击者拿到几个短码，无法推出下一个。

详细权衡见 ADR-0002。

### 5.3 统计链路

```
跳转发生
    │
    ▼
Redis 记录（一次 pipeline，2 条命令）
    INCR  shortlink:pv:{code}:{yyyyMMdd}
    PFADD shortlink:uv:{code}:{yyyyMMdd} {clientIp}
    │
    │
    ▼ 每 60 秒（@Scheduled）
StatsFlushScheduler
    │  扫描前一日 + 当日 key
    ▼
UPSERT 到 MySQL link_stat(code, stat_date, pv, uv)
    │  刷库成功后删除当日 Redis 计数 key
    ▼
数据落库
```

**查询时的一致性问题**：因为刷库有最长 60 秒延迟，直接查 MySQL 会看到旧数据。因此 `StatsService` 查询时会**合并 Redis 中的未刷库增量**：

```
最终值 = MySQL 已落库值 + Redis 当日增量
```

这样对外表现始终是"最新的"，而写入路径保持异步。这是**读时修复（Read-Repair）**思路的一个应用。

UV 使用 Redis HyperLogLog（`PFADD`）而非 Set：统计 1 亿个独立 IP，Set 需要约 1.6 GB 内存，HyperLogLog 固定只需 12 KB，误差 0.81%。对于"投放效果评估"这个场景，0.81% 的误差完全可以接受。详细权衡见 ADR-0004。

---

## 6. 安全设计

| 层面 | 措施 | 防的是什么 |
| --- | --- | --- |
| 密码存储 | BCrypt cost=10 | 拖库后明文泄露 |
| 认证 | JWT（HS256，30 min） | 会话劫持 |
| 令牌撤销 | refresh token 存 Redis，一次性使用 | 令牌重放 |
| 越权 | 所有查询强制带 `userId` 条件；跨用户返回 404 | 水平越权（IDOR） |
| 开放重定向 | 协议白名单 `http`/`https` | 服务被用作 XSS / 钓鱼跳板 |
| 用户枚举 | 登录失败统一返回 `INVALID_CREDENTIALS` | 用户名枚举 |
| 分页炸弹 | `size` 上限 100 | 内存耗尽型 DOS |
| 限流 | Redis Lua 滑动窗口 | 暴力破解、爬虫 |
| Actuator | 仅放行 `/actuator/health` | 配置与内部结构泄露 |
| 错误响应 | 500 不返回堆栈 | 内部实现泄露 |

### 6.1 水平越权的防御

**错误做法**：先查再判断归属。

```java
ShortLink link = repository.findById(id);          // ← 危险
if (!link.getUserId().equals(currentUserId))
    throw new ForbiddenException();
```

问题在于：`findById` 已经把所有者的数据取出来了，任何一处忘记判断就产生越权；而且返回 403 泄露了"该资源存在"。

**本项目做法**：把 `userId` 作为查询条件的一部分，查不到就是 404。

```java
ShortLink link = repository.findByIdAndUserId(id, currentUserId)
        .orElseThrow(() -> new NotFoundException(ErrorCode.NOT_FOUND));
```

归属校验下沉到 SQL 的 `WHERE` 子句，**从数据访问层面杜绝越权**，而不是依赖每个开发者记得写 if。

---

## 7. 技术选型

| 组件 | 选型 | 版本 | 理由 |
| --- | --- | --- | --- |
| 语言 | Java | 17 | 约束；LTS |
| 框架 | Spring Boot | 4.1.1 | 生态成熟，Boot 4 基于 Spring Framework 7 |
| Web | Spring MVC | — | 同步阻塞模型与 JDBC 一致，本场景无高并发长连接需求，无需 WebFlux |
| 持久化 | Spring Data JPA + Hibernate | — | 领域模型与表结构映射自然；避免手写 SQL 样板 |
| 数据库 | MySQL | 8.0 | 唯一索引与 UPSERT 语义可靠 |
| 迁移 | Flyway | — | SQL 脚本入版本控制，DDL 变更有审计轨迹 |
| 缓存 | Redis | 7 | 缓存 + 计数 + 限流 + 令牌，一套组件覆盖四个场景 |
| 缓存客户端 | Lettuce | — | Boot 默认，基于 Netty，线程安全，无需连接池管理开销 |
| 鉴权 | jjwt | 0.13.0 | API 简洁 |
| 接口文档 | springdoc-openapi | 3.1.1 | 从注解生成 OpenAPI 3，附带 Swagger UI |
| 监控 | Micrometer + Prometheus | — | Boot 原生集成 |
| 测试 | JUnit 5 + Mockito + Testcontainers | — | Testcontainers 用真实 MySQL/Redis，避免 H2 与生产行为差异 |
| 构建 | Maven | 3.9.16 (wrapper) | wrapper 保证所有环境构建结果一致 |

### 7.1 关于测试数据库的选型说明

本项目**不使用 H2** 做集成测试。原因是 H2 与 MySQL 存在大量行为差异（`ON DUPLICATE KEY UPDATE`、`LIMIT` 语法、字符集与排序规则、`DATETIME` 精度），在 H2 上通过的测试不能保证 MySQL 上也通过——**这类"假绿"测试比没有测试更危险**。

Testcontainers 在 Docker 中启动真实 MySQL 8.0，保证测试环境与生产环境一致。代价是首次运行需要拉取镜像（约 600 MB）和更长的启动时间，这个代价换来的是**测试结果可信**。

---

## 8. 已知架构风险

诚实地列出当前设计的弱点和触发条件——**不写风险的架构文档是不完整的**。

| 风险 | 触发条件 | 影响 | 预案 |
| --- | --- | --- | --- |
| Redis 成为单点 | Redis 故障 | 缓存不命中，全部压到 MySQL | 已实现降级（AC-05.6）；后续加 Redis 哨兵 |
| MySQL 单点 | MySQL 故障 | 服务不可写；已缓存短链仍可跳转 | 后续主从 + 故障转移 |
| 统计刷库任务单实例 | 部署多实例 | 任务重复执行，PV 翻倍 | v1.1 引入分布式锁（Redisson/ShedLock） |
| 缓存与 DB 短暂不一致 | 更新短链后 | 最长一个 TTL 内跳转旧地址 | 更新时主动删除缓存（已实现）|
| 短码空间耗尽 | 生成量 > 568 亿 | 打散后回绕，产生冲突 | 监控 seq 水位；耗尽前扩展短码长度到 7 位 |
| 单表数据量增长 | 短链数 > 1 亿 | 查询变慢，索引增大 | 按 `user_id` 哈希分表 |

> **当前单实例部署下统计任务不会重复执行**，但这是依赖部署形态的隐含假设。多实例部署前必须先解决，这个风险已记录在案。

---

## 9. 变更记录

| 版本 | 日期 | 变更 |
| --- | --- | --- |
| v1.0.0 | 2026-09-24 | 初版 |
