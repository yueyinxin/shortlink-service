-- =============================================================================
-- V3: 短链访问统计表（按天聚合）
-- =============================================================================

CREATE TABLE link_stat
(
    id         BIGINT   NOT NULL AUTO_INCREMENT COMMENT '主键',
    link_id    BIGINT   NOT NULL COMMENT '短链 ID',
    stat_date  DATE     NOT NULL COMMENT '统计日期',
    pv         BIGINT   NOT NULL DEFAULT 0 COMMENT '页面访问量',
    uv         BIGINT   NOT NULL DEFAULT 0 COMMENT '独立访客数（HyperLogLog 估算值，误差约 0.81%）',
    created_at DATETIME(6) NOT NULL COMMENT '创建时间',
    updated_at DATETIME(6) NOT NULL COMMENT '更新时间',

    PRIMARY KEY (id),

    -- 刷库任务使用 INSERT ... ON DUPLICATE KEY UPDATE 实现幂等累加，
    -- 依赖这个唯一键判断「该链接在该日期是否已有记录」。
    UNIQUE KEY uk_link_stat_link_date (link_id, stat_date),

    -- 删除短链时其统计数据一并删除。
    -- 短链都不存在了，孤立的统计行没有意义，且会阻碍短码复用（见 ADR-0002 与数据库设计 §3.1）。
    CONSTRAINT fk_link_stat_link FOREIGN KEY (link_id) REFERENCES short_link (id) ON DELETE CASCADE,

    CONSTRAINT ck_link_stat_pv_non_negative CHECK (pv >= 0)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci COMMENT ='短链访问统计表';

-- 说明：
--
-- 1) 本表只存「按天聚合」的值，不存访问明细（每次访问一行）。
--    这是 ADR-0004 决策的直接结果：明细需要消息队列承载，当前规模下是过度设计。
--    代价是无法回答「某次具体访问来自哪里」，若需要则属于新需求。
--
-- 2) uv 列存储的是 HyperLogLog 的估算值而非精确去重计数。
--    列宽用 BIGINT 而非 INT：估值本身的量级正确，只是精度有 0.81% 的误差。
--
-- 3) 之所以用 (link_id, stat_date) 作为唯一键而不是 (code, stat_date)：
--    link_id 是 BIGINT（8 字节）而定长 code 是 VARCHAR（含长度前缀），
--    用整型做主键与索引键的空间和比较效率都更高。
--    刷库任务拿到的是 code（来自 Redis key），需先批量解析为 link_id 再写入，
--    这一步通过 uk_short_link_code 索引批量查询完成，代价可忽略。
--
-- 4) ON DELETE CASCADE 是物理删除方案的一部分。软删除在本项目被否决，
--    原因见 docs/03-database-design.md §3.1（MySQL 唯一索引中 NULL 互不相等，
--    会导致未删除记录的短码唯一性约束完全失效）。
