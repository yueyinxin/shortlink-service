package io.github.yueyinxin.shortlink.stats.dto;

import java.time.LocalDate;
import java.util.List;

/**
 * 短链访问统计响应。
 *
 * <h2>为什么没有「总 UV」字段</h2>
 * 区间内的总独立访客数<b>不等于</b>每日 UV 之和。同一个人在周一和周三各访问一次，
 * 两天的 UV 各计 1，相加得 2，但区间内的真实独立访客数是 1。
 *
 * <p>要得到正确的区间 UV，需要维护一个覆盖整个区间的 HyperLogLog 并做并集
 * （{@code PFMERGE}），而本项目只在 Redis 中保留当日的 HLL —— 历史数据的 HLL
 * 在刷库后即被删除。因此本响应<b>不提供</b> totalUv 字段。
 *
 * <p>提供一个数值上错误的"总 UV"比不提供更糟：它看起来合理，会被直接用于汇报，
 * 而错误只有在被追问时才会暴露。若将来确实需要区间 UV，应当在刷库时为每个短链
 * 额外维护月级 HLL，而不是靠累加日 UV 拼凑。
 *
 * <p>{@code totalPv} 是 {@code daily} 中各日 PV 之和 —— PV 是纯粹的计数，
 * 跨天相加在语义上成立。
 *
 * @param linkId  短链主键
 * @param code    短码
 * @param from    统计起始日期（含）
 * @param to      统计结束日期（含）
 * @param totalPv 区间内总访问量
 * @param daily   按日期升序的每日数据，<b>包含访问量为 0 的日期</b>
 */
public record StatsResponse(
        Long linkId,
        String code,
        LocalDate from,
        LocalDate to,
        long totalPv,
        List<DailyStat> daily
) {

    /**
     * 单日统计。
     *
     * @param date 日期
     * @param pv   访问量
     * @param uv   独立访客数估算值（HyperLogLog，标准误差约 0.81%）
     */
    public record DailyStat(LocalDate date, long pv, long uv) {
    }
}
