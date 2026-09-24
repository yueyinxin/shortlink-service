-- =============================================================================
-- V2: 短链表
-- =============================================================================

CREATE TABLE short_link
(
    id           BIGINT        NOT NULL AUTO_INCREMENT COMMENT '主键，同时是短码生成器的序列源',
    code         VARCHAR(10)   NOT NULL COLLATE utf8mb4_bin COMMENT '短码，4-10 位字母数字',
    original_url VARCHAR(2048) NOT NULL COMMENT '目标 URL',
    user_id      BIGINT        NOT NULL COMMENT '所属用户 ID',
    status       VARCHAR(16)   NOT NULL DEFAULT 'ACTIVE' COMMENT '状态：ACTIVE / DISABLED',
    expires_at   DATETIME(6)   NULL COMMENT '过期时间，NULL 表示永不过期',
    created_at   DATETIME(6)   NOT NULL COMMENT '创建时间',
    updated_at   DATETIME(6)   NOT NULL COMMENT '更新时间',

    PRIMARY KEY (id),

    UNIQUE KEY uk_short_link_code (code),

    -- 短链列表接口固定按 created_at DESC 排序，倒序复合索引可直接满足排序而无需 filesort。
    -- user_id 置于最左列是必须的：所有查询都强制带 user_id 条件做数据隔离，
    -- 而「先定位到用户」再「按时间倒序」的顺序，决定了 user_id 必须是索引最左前缀。
    KEY idx_short_link_user_created (user_id, created_at DESC),

    KEY idx_short_link_user_status (user_id, status),

    CONSTRAINT fk_short_link_user FOREIGN KEY (user_id) REFERENCES users (id),

    CONSTRAINT ck_short_link_status CHECK (status IN ('ACTIVE', 'DISABLED'))
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci COMMENT ='短链表';

-- 说明：
--
-- 1) code 列必须使用大小写敏感的排序规则（utf8mb4_bin）。
--    MySQL 默认的 utf8mb4_0900_ai_ci 是大小写不敏感的，若沿用默认值，
--    短码 "aB3x9K" 与 "Ab3X9k" 会被唯一索引判定为重复 —— 这会让 62 个字符的
--    短码空间退化为 36 个字符（只剩字母不分大小写），且产生用户可见的错误：
--    两个视觉上完全不同的短链互相冲突。这是本项目最容易被忽略的建表细节。
--
-- 2) original_url 使用 VARCHAR(2048)，参考 IE 的 2083 字符上限（最保守的浏览器限制）。
--    该列不建索引：项目不做 URL 等值查询，关键字搜索使用 LIKE '%kw%'，
--    无法命中索引，其性能分析见 docs/03-database-design.md §3.4。
--
-- 3) status 与 expires_at 分离存储而非合并为单一状态列：
--    「已禁用」是人为操作，「已过期」是时间自然流逝，二者可以同时成立，
--    合并会导致无法区分「管理员禁用了一个已过期的链接」这类情形。
