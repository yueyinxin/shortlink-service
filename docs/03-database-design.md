# 数据库设计说明书

| 项目 | shortlink-service |
| --- | --- |
| 数据库 | MySQL 8.0 |
| 字符集 | `utf8mb4` / `utf8mb4_0900_ai_ci` |
| 版本 | v1.0.0 |

---

## 1. ER 图

```
    ┌─────────────────────┐
    │       users         │
    ├─────────────────────┤
    │ id            BIGINT│ PK
    │ username      VARCHAR│ UK
    │ email         VARCHAR│ UK
    │ password_hash VARCHAR│
    │ status        VARCHAR│
    │ created_at    DATETIME│
    │ updated_at    DATETIME│
    └──────────┬──────────┘
               │ 1
               │
               │ N   (一个用户拥有多条短链)
               ▼
    ┌─────────────────────┐
    │    short_link       │
    ├─────────────────────┤
    │ id            BIGINT│ PK  ← 短码生成的序列来源
    │ code          VARCHAR│ UK
    │ original_url  VARCHAR│
    │ user_id       BIGINT│ FK
    │ status        VARCHAR│
    │ expires_at    DATETIME│ (可空 = 永不过期)
    │ created_at    DATETIME│
    │ updated_at    DATETIME│
    └──────────┬──────────┘
               │ 1
               │
               │ N   (一条短链有多天的统计)
               ▼
    ┌─────────────────────┐
    │     link_stat       │
    ├─────────────────────┤
    │ id            BIGINT│ PK
    │ link_id       BIGINT│ FK (ON DELETE CASCADE)
    │ stat_date     DATE  │
    │ pv            BIGINT│
    │ uv            BIGINT│
    │ created_at    DATETIME│
    │ updated_at    DATETIME│
    └─────────────────────┘
         UK (link_id, stat_date)
```

**注意**：`refresh_token` 不落库，仅存于 Redis（见 ADR-0005）。

---

## 2. 表结构

### 2.1 `users`

| 字段 | 类型 | 允许空 | 默认 | 说明 |
| --- | --- | --- | --- | --- |
| `id` | BIGINT UNSIGNED | 否 | AUTO_INCREMENT | 主键 |
| `username` | VARCHAR(32) | 否 | — | 登录名，`[a-zA-Z0-9_-]{3,32}` |
| `email` | VARCHAR(128) | 否 | — | 邮箱 |
| `password_hash` | VARCHAR(60) | 否 | — | BCrypt 哈希，固定 60 字符 |
| `status` | VARCHAR(16) | 否 | `ACTIVE` | `ACTIVE` / `DISABLED` |
| `created_at` | DATETIME(3) | 否 | — | JPA 审计写入 |
| `updated_at` | DATETIME(3) | 否 | — | JPA 审计写入 |

**索引：**

- `PRIMARY KEY (id)`
- `UNIQUE KEY uk_users_username (username)`
- `UNIQUE KEY uk_users_email (email)`

**设计说明：**

- `password_hash` 长度固定 60 是 BCrypt 的输出长度（`$2a$10$` 前缀 + 22 字符 salt + 31 字符 hash）。用 `CHAR(60)` 也可以，但 `VARCHAR(60)` 保留了未来更换算法的余地（如 Argon2 输出更长）。
- 唯一约束建在**数据库**而非仅在应用层判断。应用层的"先查询再插入"在并发下必然产生重复数据——两个请求同时查询都发现"用户名可用"，然后都插入成功。

### 2.2 `short_link`

| 字段 | 类型 | 允许空 | 默认 | 说明 |
| --- | --- | --- | --- | --- |
| `id` | BIGINT UNSIGNED | 否 | AUTO_INCREMENT | 主键，**同时是短码生成器的序列源** |
| `code` | VARCHAR(10) | 否 | — | 短码，4–10 位字母数字 |
| `original_url` | VARCHAR(2048) | 否 | — | 目标 URL |
| `user_id` | BIGINT UNSIGNED | 否 | — | 所属用户 |
| `status` | VARCHAR(16) | 否 | `ACTIVE` | `ACTIVE` / `DISABLED` |
| `expires_at` | DATETIME(3) | 是 | NULL | NULL 表示永不过期 |
| `created_at` | DATETIME(3) | 否 | — | |
| `updated_at` | DATETIME(3) | 否 | — | |

**索引：**

- `PRIMARY KEY (id)`
- `UNIQUE KEY uk_short_link_code (code)`
- `KEY idx_short_link_user_created (user_id, created_at DESC)`
- `KEY idx_short_link_user_status (user_id, status)`
- `CONSTRAINT fk_short_link_user FOREIGN KEY (user_id) REFERENCES users (id)`

**设计说明：**

- **`id` 是短码的序列来源**（见 ADR-0002）。之所以用自增主键而不是单独的序列表：自增主键已经提供了单调递增值，额外建一个序列表只会增加一次写入且没有收益。
- **`code` 用 `VARCHAR(10)` 而不是 `CHAR(6)`**：虽然自动生成的短码固定 6 位，但自定义短码允许 4–10 位。用 `CHAR(6)` 会导致自定义短码被截断或报错。
- **`original_url` 用 `VARCHAR(2048)`**：这是事实上的浏览器 URL 长度上限（IE 的 2083 字符限制是最保守的参考值）。不建索引，因为我们不做 URL 的等值查询。
- **`idx_short_link_user_created` 是倒序索引**：短链列表接口固定按 `created_at DESC` 排序（AC-06.2），倒序索引可以直接满足排序而无需 filesort。
- **复合索引的前缀顺序是 `(user_id, created_at)`**：因为查询永远带 `user_id` 条件（数据隔离要求，见架构文档 §6.1），`user_id` 作为最左前缀。反过来建 `(created_at, user_id)` 则无法用于本查询。

### 2.3 `link_stat`

| 字段 | 类型 | 允许空 | 默认 | 说明 |
| --- | --- | --- | --- | --- |
| `id` | BIGINT UNSIGNED | 否 | AUTO_INCREMENT | 主键 |
| `link_id` | BIGINT UNSIGNED | 否 | — | 短链 ID |
| `stat_date` | DATE | 否 | — | 统计日期（服务器时区） |
| `pv` | BIGINT UNSIGNED | 否 | 0 | 页面访问量 |
| `uv` | BIGINT UNSIGNED | 否 | 0 | 独立访客数（HyperLogLog 估算值） |
| `created_at` | DATETIME(3) | 否 | — | |
| `updated_at` | DATETIME(3) | 否 | — | |

**索引：**

- `PRIMARY KEY (id)`
- `UNIQUE KEY uk_link_stat_link_date (link_id, stat_date)`
- `CONSTRAINT fk_link_stat_link FOREIGN KEY (link_id) REFERENCES short_link (id) ON DELETE CASCADE`

**设计说明：**

- **`uk_link_stat_link_date` 是 UPSERT 的基础**：刷库任务使用 `INSERT ... ON DUPLICATE KEY UPDATE`，依赖这个唯一键判断"该天该链接是否已有记录"。
- **`ON DELETE CASCADE`**：删除短链时其统计数据一并删除。这是有意的选择——短链都不存在了，孤立的统计数据没有意义，且会阻碍短码复用（见下方"关于删除方式的决策"）。
- **`uv` 存储的是 HyperLogLog 的估算值**，不是精确值。列注释中已标明。误差约 0.81%（见 ADR-0004）。

---

## 3. 关键设计决策

### 3.1 关于删除方式：为什么用物理删除而不是软删除

需求 AC-06.6 要求删除后短码跳转返回 404，AC-06.7 要求删除后**同一自定义短码可以被再次创建**。

这两个需求组合起来，让软删除方案变得不可行：

**软删除方案 A**：`deleted_at` 置为时间戳，唯一索引仍建在 `code` 上。

- 问题：已删除的记录仍占用 `code`，AC-06.7 失败。

**软删除方案 B**：唯一索引改为 `(code, deleted_at)`，未删除时 `deleted_at` 为 NULL。

- 问题：**MySQL 的唯一索引中 NULL 互不相等**。因此可以存在多行 `(code='abc', deleted_at=NULL)`，唯一性约束对未删除的记录**完全失效**。这会导致同一短码被创建多次，是比"无法复用短码"严重得多的 bug。

**软删除方案 C**：增加 `deleted_flag` 列，未删除为 `0`，删除时写为 `id`（唯一值），唯一索引建在 `(code, deleted_flag)`。

- 可行，但引入了额外的列、额外的索引宽度，且每个查询都必须加上 `deleted_flag = 0` 条件——**一旦有人漏写，就会查到已删除的数据**。这类 bug 在代码评审中很难发现。

**本项目的选择**：**物理删除**。

理由：当前需求没有"数据审计"或"回收站"的要求（见需求文档 §2.2 范围外）。为不存在的需求引入软删除，代价是让每条查询都多一个易漏的条件。物理删除 + `ON DELETE CASCADE` 让语义最清晰：删除就是删除。

> **如果未来需要审计**，正确的做法不是给业务表加软删除，而是引入**独立的审计日志表**（记录谁在什么时候删除了什么），业务表保持物理删除。这样审计查询与业务查询互不干扰，也不会给业务查询埋下漏加条件的隐患。

### 3.2 关于时间字段的精度

所有时间字段使用 `DATETIME(6)`（微秒精度），而非 MySQL 默认的 `DATETIME`（秒精度）。

原因有两层：

**其一，排序稳定性。** `created_at` 用于排序。如果两条短链在同一秒内创建，秒精度下排序结果不确定（MySQL 可能返回任意顺序），会导致**分页结果不稳定**——同一条短链可能在第 1 页和第 2 页都出现，或者某条记录完全看不到。亚秒精度让排序在实际业务中稳定。

更严格的保证是用 `(created_at DESC, id DESC)` 复合排序键，本项目在查询中已同时使用两者。

**其二，与 ORM 的默认映射保持一致。** Hibernate 6 把 Java 的 `LocalDateTime` 默认映射为 `datetime(6)`。如果建表用 `DATETIME(3)`，那么在 `spring.jpa.hibernate.ddl-auto=validate` 模式下，启动时的实体与表结构校验可能因精度不一致而失败——**这类问题只会在启动时才暴露**，且在开发者的机器上可能复现不出来（取决于 Hibernate 版本对精度的比较策略）。

让建表精度与 ORM 的默认映射一致，是这个风险的零成本消除方式。选择「迁就 ORM 默认值」而不是「用 `@Column` 强行指定精度」，是因为后者会在每个时间字段上重复一遍，而前者只需在建表时写对一次。

### 3.3 关于时区

- 所有 `DATETIME` 存储的是**服务器本地时区**的时间。
- JDBC 连接参数显式指定 `serverTimezone`，避免驱动与服务器时区解释不一致导致的时间偏移（这是 Java + MySQL 最常见的线上事故之一：写入和读取差 8 小时）。
- `stat_date` 使用 DATE 类型，统计的"一天"以**应用服务器时区**为准。这意味着跨时区部署时统计口径会不同——本项目为单地域部署，可接受。若需全球部署，应统一使用 UTC 并在展示层转换。

### 3.4 关于搜索的已知限制

AC-06.3 要求按关键字搜索 `originalUrl` 或 `code`。实现使用 `LIKE '%keyword%'`，**无法使用索引**，在全表范围内是慢查询。

接受这个限制的理由：搜索条件中**必定包含 `user_id`**（数据隔离），因此实际扫描的是"该用户的短链"这一小集合，走 `idx_short_link_user_created` 索引先定位用户的记录，再在结果集内过滤。

**触发条件**：如果单个用户的短链数量达到数万条，搜索会明显变慢。届时的解决方案是引入全文索引（MySQL FULLTEXT）或外部搜索引擎（Elasticsearch）。已记录在架构风险表中。

---

## 4. 迁移脚本管理

使用 Flyway，脚本位于 `src/main/resources/db/migration/`。

**命名规范**：`V{版本号}__{描述}.sql`

| 脚本 | 内容 |
| --- | --- |
| `V1__create_users.sql` | 用户表 |
| `V2__create_short_link.sql` | 短链表 |
| `V3__create_link_stat.sql` | 统计表 |

**约束（团队规范）：**

1. **已发布的迁移脚本绝不可修改。** Flyway 会校验脚本的校验和，修改会导致启动失败。需要变更时必须新增脚本。
2. **每个脚本只做一件事**，便于定位问题和回滚决策。
3. **迁移脚本必须与实体类在同一个提交中提交**，避免代码与表结构不一致。
4. 生产环境的迁移由 CI/CD 在部署时自动执行，**不允许手工改表**。

---

## 5. 变更记录

| 版本 | 日期 | 变更 |
| --- | --- | --- |
| v1.0.0 | 2026-09-24 | 初版：users / short_link / link_stat 三张表 |
