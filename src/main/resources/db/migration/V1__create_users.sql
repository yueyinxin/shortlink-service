-- =============================================================================
-- V1: 用户表
-- =============================================================================

CREATE TABLE users
(
    id            BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    username      VARCHAR(32)  NOT NULL COMMENT '登录名，[a-zA-Z0-9_-]{3,32}',
    email         VARCHAR(128) NOT NULL COMMENT '邮箱',
    password_hash VARCHAR(60)  NOT NULL COMMENT 'BCrypt 哈希，固定 60 字符',
    status        VARCHAR(16)  NOT NULL DEFAULT 'ACTIVE' COMMENT '状态：ACTIVE / DISABLED',
    created_at    DATETIME(6)  NOT NULL COMMENT '创建时间',
    updated_at    DATETIME(6)  NOT NULL COMMENT '更新时间',

    PRIMARY KEY (id),

    -- 唯一约束建在数据库层：应用层「先查询再插入」在并发下必然产生重复数据
    UNIQUE KEY uk_users_username (username),
    UNIQUE KEY uk_users_email (email),

    CONSTRAINT ck_users_status CHECK (status IN ('ACTIVE', 'DISABLED'))
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci COMMENT ='用户表';

-- 说明：
--   username / email 使用默认的 ai_ci（大小写不敏感）排序规则，这是刻意的：
--   「Alice」与「alice」应视为同一用户名，避免用户注册出视觉上无法区分的账号。
--   MySQL 在 WHERE username = 'Alice' 时会自动匹配到 'alice'，登录逻辑无需额外处理。
