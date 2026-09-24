package io.github.yueyinxin.shortlink.stats.infrastructure;

import io.github.yueyinxin.shortlink.stats.LinkStat;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/**
 * 访问统计仓储。
 */
@Repository
public interface LinkStatRepository extends JpaRepository<LinkStat, Long> {

    /**
     * 累加统计（UPSERT）。
     *
     * <h2>为什么 PV 与 UV 的更新方式不同</h2>
     * <ul>
     *   <li><b>PV 用 {@code pv = pv + :pv}</b> —— 增量语义。调用方传入的是本次刷库
     *       从 Redis 取出并清零的增量，因此累加是正确且幂等的（同一个增量不可能被取出两次，
     *       因为取出操作是原子的）。</li>
     *   <li><b>UV 用 {@code uv = GREATEST(uv, :uv)}</b> —— 绝对值语义。调用方传入的是
     *       当日的完整 HLL 估值，取最大值保证它只会向前推进。这样即使同一份数据被写入
     *       多次，结果也不会虚高。若对 UV 也用累加，同一个访客在多次刷库中会被重复计数。</li>
     * </ul>
     *
     * <h2>为什么不用已废弃的 {@code VALUES()} 函数</h2>
     * MySQL 8.0.20 起 {@code ON DUPLICATE KEY UPDATE pv = pv + VALUES(pv)} 中的
     * {@code VALUES()} 被标记为废弃（计划移除）。本项目通过<b>重复传参</b>替代它：
     * {@code pv = pv + :pv} 直接引用参数，不依赖任何将被移除的函数。
     * 代价是同一个参数在语句中出现两次，这个代价远小于未来某次 MySQL 升级导致语句失效。
     *
     * <h2>为什么用原生 SQL</h2>
     * JPQL 没有 UPSERT 语义。用"先查询、不存在则插入、存在则更新"的 Java 实现
     * 会在并发下产生主键冲突（两个刷库实例同时发现记录不存在），
     * 而 {@code ON DUPLICATE KEY UPDATE} 由数据库原子完成，天然无竞态。
     *
     * <p>{@code created_at} / {@code updated_at} 由 SQL 直接写入 {@code NOW(6)}，
     * 而不是交给 JPA 审计 —— 原生 INSERT 不过审计监听器。这与建表脚本中
     * {@code DATETIME(6)} 的精度一致。
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = """
            INSERT INTO link_stat (link_id, stat_date, pv, uv, created_at, updated_at)
            VALUES (:linkId, :statDate, :pv, :uv, NOW(6), NOW(6))
            ON DUPLICATE KEY UPDATE
                pv = pv + :pv,
                uv = GREATEST(uv, :uv),
                updated_at = NOW(6)
            """, nativeQuery = true)
    void upsert(@Param("linkId") Long linkId,
                @Param("statDate") LocalDate statDate,
                @Param("pv") long pv,
                @Param("uv") long uv);

    /** 查询某短链在指定日期区间内的统计，按日期升序。 */
    List<LinkStat> findByLinkIdAndStatDateBetweenOrderByStatDateAsc(
            Long linkId, LocalDate from, LocalDate to);

    /** 查询某短链某一天的统计。 */
    Optional<LinkStat> findByLinkIdAndStatDate(Long linkId, LocalDate statDate);
}
