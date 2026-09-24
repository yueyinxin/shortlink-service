# ShortLink Service

短链接服务 —— 提供短链生成、跳转、管理与访问统计能力。

[![CI](https://github.com/yueyinxin/shortlink-service/actions/workflows/ci.yml/badge.svg)](https://github.com/yueyinxin/shortlink-service/actions/workflows/ci.yml)
[![Java](https://img.shields.io/badge/Java-17-blue)](https://adoptium.net/)
[![Spring Boot](https://img.shields.io/badge/Spring%20Boot-4.1.1-brightgreen)](https://spring.io/projects/spring-boot)
[![License](https://img.shields.io/badge/license-MIT-blue)](LICENSE)

---

## 这个项目是什么

一个可投入生产的短链接服务。把长链接转成 6 位短码（形如 `https://s.example.com/aB3x9K`），
记录每次访问并对外提供按天聚合的 PV / UV 统计。

**它不只是一个 CRUD 演示。** 短链服务的读写特征极不对称 —— 跳转占总流量的 99% 以上，
且对延迟极度敏感；而管理操作频率低、要求强一致。本项目的每一个设计决策都从这一事实出发：
缓存策略、异步统计、限流维度、以及分层边界，全部是被这个不对称性推导出来的。

代码规模约 60 个源文件，单元测试 72 个（含一个对 1477 万个取值空间的穷举验证）。

---

## 目录

- [快速开始](#快速开始)
- [核心设计](#核心设计)
- [架构](#架构)
- [接口概览](#接口概览)
- [项目结构](#项目结构)
- [技术栈](#技术栈)
- [已知限制](#已知限制)
- [文档导航](#文档导航)

---

## 快速开始

### 前置要求

- JDK 17
- Docker（用于启动 MySQL 与 Redis）

Maven 不需要单独安装，项目自带 Wrapper。

### 三步启动

```bash
# 1. 启动基础设施
docker compose up -d
docker compose ps          # 等 mysql 与 redis 都显示 healthy

# 2. 启动应用（首次启动会自动执行 Flyway 迁移建表）
./mvnw spring-boot:run
```

### 试用

```bash
# 注册
curl -X POST http://localhost:8080/api/v1/auth/register \
  -H 'Content-Type: application/json' \
  -d '{"username":"demo","email":"demo@example.com","password":"demopassword"}'

# 登录，拿到 accessToken
TOKEN=$(curl -s -X POST http://localhost:8080/api/v1/auth/login \
  -H 'Content-Type: application/json' \
  -d '{"usernameOrEmail":"demo","password":"demopassword"}' \
  | python -c "import sys,json; print(json.load(sys.stdin)['data']['accessToken'])")

# 创建短链
curl -X POST http://localhost:8080/api/v1/links \
  -H "Authorization: Bearer $TOKEN" \
  -H 'Content-Type: application/json' \
  -d '{"originalUrl":"https://spring.io/projects/spring-boot"}'

# 跳转（注意 -L 不跟随，只看响应头）
curl -i http://localhost:8080/<上一步返回的 code>

# 查看统计
curl "http://localhost:8080/api/v1/links/1/stats?days=7" \
  -H "Authorization: Bearer $TOKEN"
```

**接口文档（Swagger UI）**：http://localhost:8080/swagger-ui.html
点击右上角 "Authorize" 填入 accessToken 即可直接调试受保护接口。

### 常用命令

```bash
./mvnw test                    # 单元测试（不需要 Docker，秒级反馈）
./mvnw verify                  # 单元测试 + 集成测试 + 覆盖率报告
./mvnw -Dtest=ShortCodeGeneratorTest test    # 只跑某个测试类
docker compose down            # 停止基础设施（保留数据）
docker compose down -v         # 停止并删除数据
```

---

## 核心设计

### 1. 短码无冲突是数学保证，不是概率保证

短码由数据库自增主键经 **Feistel 网络格式保留加密（FPE）** 变换得到：

```
自增 seq ∈ [0, 62^6)
    │  Feistel 网络（36 位分组、8 轮、密钥化轮函数）
    ▼
permuted ∈ [0, 2^36)
    │  Cycle-Walking：越界则再次置换，直到落入有效区间
    ▼
mapped ∈ [0, 62^6)
    │  Base62 编码，左侧补零
    ▼
短码 "aB3x9K"
```

Feistel 结构的一个关键性质是：**只要保留每轮的左右交换，整个网络必然是双射**，
与轮函数的具体形式无关。因此不同的序列号必然产生不同的短码 —— 不需要查重、
不需要重试、并发下不会失败。

同时，密钥化的 8 轮迭代使攻击者无法从已知短码推算出其他短码，
**不可枚举**（区别于"看起来乱"）。

> 早期的实现曾使用线性同余打散 `(seq × A + B) mod 62^6`，它在数学上同样双射，
> 但相邻输出的差值恒为 `A` —— 攻击者连续创建几个短链即可解出参数并枚举全站短码。
> 设计评审时发现并否决了这个方案，完整过程记录在
> [ADR-0002](docs/adr/0002-short-code-generation.md)。那个缺陷现在被一条回归测试守护着。

### 2. 跳转路径上没有任何数据库写操作

```
GET /{code}
  → 限流（Redis Lua，原子）
  → 查缓存 ── 命中 ──▶ 状态判定 ──▶ 记录统计（仅 Redis，约 1ms）──▶ 302
              │
              └ 未命中 ──▶ 查 MySQL ──▶ 写回缓存 ──▶ ...
```

缓存中存的是**包含状态与过期时间的完整快照**，而非只有 URL 字符串。
这样命中缓存即可完成全部判定，热路径零数据库访问。

代价与防护措施：

| 问题 | 措施 |
| --- | --- |
| 缓存穿透（查询不存在的短码） | 空值缓存（TTL 60 秒，短于正常缓存） |
| 缓存雪崩（大量 key 同时失效） | TTL 加 ±10% 随机抖动 |
| 缓存与数据库不一致 | Cache-Aside，先更新数据库再删缓存 |
| **Redis 完全故障** | **降级为直查数据库，跳转仍可用** |

最后一条是可用性设计的核心：MySQL 是唯一真相来源，Redis 中的数据全部可重建。
因此 Redis 是性能组件，而不是可用性单点。

### 3. 统计的 PV 与 UV 采用不同语义

| | PV | UV |
| --- | --- | --- |
| 语义 | **增量**（取出并清零） | **绝对值**（当日累计） |
| Redis 形态 | 计数器，刷库后删除 | HyperLogLog，当日全程保留 |
| 写入数据库 | `pv = pv + 增量` | `uv = GREATEST(uv, 新值)` |

为什么 UV 不能也用增量：HyperLogLog 无法做差集。若每次刷库都取出估值并累加，
同一天内跨多次刷库出现的重复 IP 会被**重复计数** —— 同一访客 10 点、11 点各访问一次，
两次刷库各得 UV=1，累加后当天 UV=2，而正确答案是 1。

UV 使用 HyperLogLog 而非 Set：1 亿 UV 在 Set 中需要约 6.4 GB，HyperLogLog 固定只需 12 KB，
误差 0.81%。对"评估投放效果"这个场景，100 万 UV 有 ±8100 的误差不影响任何决策。

查询时合并 Redis 中的未落库增量，因此**对外表现始终是最新的**，而写入路径保持异步。

### 4. 归属校验下沉到 SQL

```java
// 危险：先查出来再判断归属 —— 任何一处忘记判断就是越权漏洞
ShortLink link = repository.findById(id);
if (!link.getUserId().equals(currentUserId)) throw new ForbiddenException();

// 本项目：归属是查询条件的一部分，不属于你的记录根本查不出来
ShortLink link = repository.findByIdAndUserId(id, currentUserId)
        .orElseThrow(() -> new NotFoundException(...));
```

跨用户访问返回 **404 而非 403** —— 403 会泄露"该短码存在但不属于你"这一信息。

---

## 架构

```
┌──────────────────────────────────────────────────────────────────────┐
│  接口层   RedirectController · LinkController · AuthController        │
│           StatsController · JwtAuthenticationFilter · RateLimitFilter │
│           GlobalExceptionHandler                                     │
├──────────────────────────────────────────────────────────────────────┤
│  应用层   RedirectService · LinkService · AuthService · StatsService  │
│           事务边界只出现在这一层                                        │
├──────────────────────────────────────────────────────────────────────┤
│  领域层   ShortLink · LinkSnapshot · ShortCode · ShortCodeGenerator   │
│           LinkAccessState · ReservedCodes                            │
│           不依赖 Spring / JPA / Redis —— 可用毫秒级单元测试验证          │
├──────────────────────────────────────────────────────────────────────┤
│  基础设施  ShortLinkRepository · RedisLinkCache · RedisStatsCounter    │
│           RedisRefreshTokenStore · StatsFlushScheduler                │
└───────┬──────────────────────────────────────┬───────────────────────┘
        ▼                                      ▼
  ┌───────────────┐                     ┌───────────────┐
  │   MySQL 8.0   │  唯一真相来源         │   Redis 7     │  可重建的加速层
  │  用户 / 短链    │                     │  跳转缓存       │
  │  按天统计      │                     │  PV/UV 计数     │
  └───────────────┘                     │  刷新令牌       │
                                        │  限流窗口       │
                                        └───────────────┘
```

**依赖方向**：`web → application → domain ← infrastructure`。
`domain` 包中不出现任何框架 import，这条约束由 `ArchitectureTest` 守护。

代码按**功能垂直切分**（`auth` / `user` / `link` / `stats`），而非按技术分层。
改一个功能只需读一个目录，未来拆分微服务时整个目录可以直接搬走。
详细权衡见 [ADR-0001](docs/adr/0001-package-by-feature.md)。

---

## 接口概览

| 方法 | 路径 | 鉴权 | 说明 |
| --- | --- | --- | --- |
| `POST` | `/api/v1/auth/register` | 匿名 | 注册 |
| `POST` | `/api/v1/auth/login` | 匿名 | 登录，返回令牌对 |
| `POST` | `/api/v1/auth/refresh` | 匿名 | 刷新令牌（一次性使用） |
| `POST` | `/api/v1/auth/logout` | 匿名 | 作废刷新令牌 |
| `GET` | `/api/v1/users/me` | 需要 | 当前用户信息 |
| `POST` | `/api/v1/links` | 需要 | 创建短链 |
| `GET` | `/api/v1/links` | 需要 | 分页查询（支持关键字搜索） |
| `GET` | `/api/v1/links/{id}` | 需要 | 短链详情 |
| `PATCH` | `/api/v1/links/{id}/status` | 需要 | 启用 / 禁用 |
| `DELETE` | `/api/v1/links/{id}` | 需要 | 删除 |
| `GET` | `/api/v1/links/{id}/stats` | 需要 | 按天统计（PV / UV） |
| `GET` | `/{code}` | 匿名 | **跳转** |
| `GET` | `/actuator/health` | 匿名 | 健康检查 |
| `GET` | `/actuator/prometheus` | 需要 | 监控指标 |

**统一响应格式**：

```jsonc
// 成功
{ "success": true, "data": { /* ... */ } }

// 失败
{
  "success": false,
  "error": {
    "code": "CODE_TAKEN",          // 供客户端做程序化判断
    "message": "该短码已被占用",     // 可直接展示给用户
    "details": [                   // 仅参数校验失败时存在
      { "field": "customCode", "reason": "短码只能包含字母和数字" }
    ],
    "traceId": "a1b2c3..."         // 与后端日志关联，报障时提供它
  }
}
```

完整错误码清单见[需求文档 §7](docs/01-requirements.md#7-错误码清单)。

---

## 项目结构

```
shortlink-service/
├── src/main/java/io/github/yueyinxin/shortlink/
│   ├── common/             横切关注点
│   │   ├── exception/      统一错误码、业务异常、全局异常处理
│   │   ├── filter/         traceId 注入
│   │   ├── persistence/    审计基类
│   │   ├── ratelimit/      Redis 固定窗口限流（Lua）
│   │   ├── response/       统一响应信封、分页、错误写入器
│   │   ├── util/           Base62、客户端 IP 解析
│   │   └── validation/     安全 URL 校验（协议白名单）
│   ├── config/             Security / OpenAPI / JPA / 配置属性 / 启动自检
│   ├── auth/               认证：注册、登录、刷新、登出
│   ├── user/               用户
│   ├── link/               短链：创建、管理、跳转（核心）
│   └── stats/              统计：计数、查询、定时刷库
│
├── src/main/resources/
│   ├── application.yml         主配置（含开发默认值）
│   ├── application-prod.yml    生产配置（无默认值，缺配置即启动失败）
│   └── db/migration/           Flyway 迁移脚本 V1-V3
│
├── src/test/java/...           单元测试（*Test）与集成测试（*IT）
│
├── docs/
│   ├── 01-requirements.md      需求规格（含可执行的验收标准）
│   ├── 02-architecture.md      架构设计（含已知风险）
│   ├── 03-database-design.md   数据库设计
│   ├── 05-development-process.md  开发流程说明
│   ├── 08-interview-guide.md   面试讲解手册
│   └── adr/                    架构决策记录
│
├── deploy/                 生产部署（compose + 环境变量模板 + 部署脚本）
├── Dockerfile              多阶段构建
├── compose.yaml            本地开发的基础设施
└── .github/                CI 流水线、Issue 与 PR 模板
```

---

## 技术栈

| 组件 | 选型 | 选择理由 |
| --- | --- | --- |
| 语言 | Java 17 | LTS；record、sealed interface、模式匹配 |
| 框架 | Spring Boot 4.1.1 | 基于 Spring Framework 7 |
| Web | Spring MVC | 同步阻塞模型与 JDBC 一致，本场景无需 WebFlux |
| 持久化 | Spring Data JPA + Hibernate | 领域模型映射自然 |
| 数据库 | MySQL 8.0 | 唯一索引与 UPSERT 语义可靠 |
| 迁移 | Flyway | SQL 脚本入版本控制，DDL 变更有审计轨迹 |
| 缓存 | Redis 7 + Lettuce | 一套组件覆盖缓存、计数、限流、令牌四个场景 |
| 鉴权 | jjwt 0.13 | 短期 JWT + Redis 存储的一次性 Refresh Token |
| 文档 | springdoc-openapi 3.1 | 从注解生成，附带可调试的 Swagger UI |
| 监控 | Micrometer + Prometheus | Boot 原生集成，HTTP 耗时直方图 |
| 测试 | JUnit 5 + Mockito + Testcontainers | **集成测试用真实 MySQL，不用 H2** |

**为什么集成测试不用 H2**：H2 与 MySQL 在 `ON DUPLICATE KEY UPDATE` 语法、`LIMIT` 分页、
字符集与排序规则、`DATETIME` 精度上都有差异。在 H2 上通过的测试不能保证 MySQL 上也通过 ——
这类"假绿"测试比没有测试更危险，因为它让人对结论产生错误的信心。
代价是首次运行需要拉取约 600MB 镜像，因此集成测试放在 `*IT.java` 中由 `mvn verify` 执行，
`mvn test` 保持秒级反馈。

---

## 已知限制

诚实列出当前设计的边界。**这些问题都有明确的触发条件和应对方案，不是遗漏。**

| 限制 | 触发条件 | 影响 | 应对 |
| --- | --- | --- | --- |
| Redis 单点 | Redis 故障 | 缓存不命中，全部压到 MySQL | 已实现降级；后续加哨兵 |
| MySQL 单点 | MySQL 故障 | 不可写；已缓存短链仍可跳转 | 后续主从 + 故障转移 |
| 统计任务不支持多实例 | 部署 > 1 个实例 | 任务重复执行（不产生错误数据） | 引入分布式锁，或只在一个实例开启 |
| 短码空间有限 | 生成量 > 568 亿 | 打散后回绕，产生冲突 | 监控水位，接近 80% 时扩展为 7 位 |
| 单表数据量 | 短链数 > 1 亿 | 查询变慢 | 按 `user_id` 哈希分表 |
| 搜索无法使用索引 | 单用户短链数 > 数万 | `LIKE '%kw%'` 变慢 | 引入全文索引或搜索引擎 |
| 统计最多丢失 60 秒 | Redis 崩溃且未持久化 | 该时段的访问数据缺失 | 已开启 AOF；如需更强保证需引入 MQ |
| UV 有 0.81% 误差 | 始终 | UV 不是精确值 | 已在使用该数值的地方明确标注 |

---

## 文档导航

### 想要理解"为什么这样设计"

| 文档 | 内容 |
| --- | --- |
| [需求规格](docs/01-requirements.md) | 用户故事、**可执行的验收标准**、非功能需求、明确的范围外事项 |
| [架构设计](docs/02-architecture.md) | 分层、核心链路、安全设计、**已知风险** |
| [数据库设计](docs/03-database-design.md) | ER 图、索引设计依据、关键取舍（软删除为何被否决） |
| [ADR 目录](docs/adr/) | 五篇架构决策记录，每篇含背景、决策、理由、**放弃的替代方案** |

### 想要理解"正规流程长什么样"

| 文档 | 内容 |
| --- | --- |
| [开发流程说明](docs/05-development-process.md) | 从需求到上线的完整流程，以及每个阶段的产物与检查点 |
| [面试讲解手册](docs/08-interview-guide.md) | 项目讲解顺序、高频追问与参考回答、可以主动暴露的不足 |

### ADR 索引

| 编号 | 决策 | 一句话结论 |
| --- | --- | --- |
| [0001](docs/adr/0001-package-by-feature.md) | 代码组织 | 按功能垂直切分，而非按技术分层 |
| [0002](docs/adr/0002-short-code-generation.md) | 短码生成 | Feistel 格式保留加密，而非线性打散 |
| [0003](docs/adr/0003-cache-strategy.md) | 缓存策略 | Cache-Aside + 空值缓存 + TTL 抖动 + 故障降级 |
| [0004](docs/adr/0004-stats-strategy.md) | 统计方案 | Redis 计数 + 定时刷库 + 读时合并，不引入 MQ |
| [0005](docs/adr/0005-authentication.md) | 认证方案 | 短期 JWT + Redis 存储的一次性 Refresh Token |

---

## 部署

```bash
cp deploy/.env.example deploy/.env
# 编辑 deploy/.env，用 openssl rand -base64 48 生成密钥

./deploy/deploy.sh              # 构建镜像并部署
./deploy/deploy.sh --status     # 查看状态
./deploy/deploy.sh --logs       # 查看日志
```

生产配置有两层保护，避免带着开发默认值上线：

1. `application-prod.yml` 中的敏感项**没有默认值**（形如 `${VAR}` 而非 `${VAR:default}`），
   缺少环境变量时应用无法启动。
2. `StartupSafetyCheck` 在启动完成后检查配置，若检测到仍在用开发默认密钥则**拒绝启动**
   并打印具体是哪个变量。

---

## 许可

[MIT](LICENSE)
