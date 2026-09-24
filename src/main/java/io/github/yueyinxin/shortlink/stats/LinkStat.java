package io.github.yueyinxin.shortlink.stats;

import io.github.yueyinxin.shortlink.common.persistence.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDate;

/**
 * 短链按天聚合的访问统计。
 *
 * <p>只存聚合值，不存访问明细。这是 ADR-0004 决策的直接结果：
 * 明细需要消息队列承载，当前规模下是过度设计。代价是无法回答"某次具体访问来自哪里"，
 * 若需要则属于新需求。
 *
 * <p>实体本身几乎是纯粹的字段容器 —— 与 {@code ShortLink} 不同，这里没有值得封装的
 * 业务规则（PV/UV 的累加语义由仓储的 UPSERT 语句决定）。强行给它加方法
 * （如 {@code incrementPv()}）反而会暗示"先查出来改再存回去"这种在并发下会丢失更新的做法。
 */
@Entity
@Table(name = "link_stat")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class LinkStat extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "link_id", nullable = false)
    private Long linkId;

    @Column(name = "stat_date", nullable = false)
    private LocalDate statDate;

    /** 页面访问量。 */
    @Column(nullable = false)
    private long pv;

    /**
     * 独立访客数。
     *
     * <p>存储的是 HyperLogLog 的<b>估算值</b>，标准误差约 0.81%，
     * 不是精确的去重计数。这一点必须在任何向用户展示该数字的地方说明，
     * 否则会被当作精确值使用。
     */
    @Column(nullable = false)
    private long uv;

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof LinkStat stat)) {
            return false;
        }
        return id != null && id.equals(stat.id);
    }

    @Override
    public int hashCode() {
        return LinkStat.class.hashCode();
    }

    @Override
    public String toString() {
        return "LinkStat{linkId=%d, date=%s, pv=%d, uv=%d}".formatted(linkId, statDate, pv, uv);
    }
}
