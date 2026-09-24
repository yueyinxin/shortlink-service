package io.github.yueyinxin.shortlink.stats;

import io.github.yueyinxin.shortlink.common.exception.BusinessException;
import io.github.yueyinxin.shortlink.common.exception.ErrorCode;
import io.github.yueyinxin.shortlink.config.properties.ShortLinkProperties;
import io.github.yueyinxin.shortlink.link.ShortLink;
import io.github.yueyinxin.shortlink.link.ShortLinkRepository;
import io.github.yueyinxin.shortlink.stats.dto.StatsResponse;
import io.github.yueyinxin.shortlink.stats.dto.StatsResponse.DailyStat;
import io.github.yueyinxin.shortlink.stats.infrastructure.LinkStatRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 统计查询服务。
 *
 * <h2>核心问题：刷库有延迟，但用户希望看到最新数据</h2>
 * 统计是异步落库的（每 60 秒一次），因此直接查数据库会看到最长 60 秒前的数据 ——
 * 用户刚发的链接点了几下，后台仍显示旧数字。这种"看起来坏了"的体验会引发大量
 * 无效工单，而它其实是设计选择的结果。
 *
 * <p>解决方案是<b>读时合并</b>：查询时把 Redis 中尚未落库的增量加进来。
 * 对外表现始终是最新的，而写入路径保持异步。这是"读时修复（Read-Repair）"
 * 思路的一个应用。
 *
 * <h2>合并公式对当天与历史日期都成立</h2>
 * <pre>
 *   pv = 数据库已落库值 + Redis 待落库增量
 *   uv = max(数据库已落库值, Redis 当日 HLL 估值)
 * </pre>
 * 对<b>历史日期</b>：Redis 中的计数键在刷库后已被删除，{@code readPv} 返回 0、
 * {@code readUv} 返回 0，于是 {@code pv = 数据库值}、{@code uv = max(数据库值, 0) = 数据库值}。
 * 无需为两类日期写不同的分支 —— 同一个公式自然退化出正确的行为。
 *
 * <p>这也是为什么 UV 用 {@code max} 而不是相加：当日 HLL 估值本身就包含了数据库里
 * 已落库的那部分访客，两者相加会重复计数。
 */
@Service
public class StatsService {

    private final ShortLinkRepository linkRepository;
    private final LinkStatRepository linkStatRepository;
    private final StatsCounter statsCounter;
    private final ShortLinkProperties properties;

    public StatsService(ShortLinkRepository linkRepository,
                        LinkStatRepository linkStatRepository,
                        StatsCounter statsCounter,
                        ShortLinkProperties properties) {
        this.linkRepository = linkRepository;
        this.linkStatRepository = linkStatRepository;
        this.statsCounter = statsCounter;
        this.properties = properties;
    }

    /**
     * 查询短链的按天访问统计。
     *
     * @param userId 当前用户，用于归属校验
     * @param linkId 短链 ID
     * @param days   统计天数；为 {@code null} 时使用配置的默认值
     * @throws BusinessException 短链不存在或不属于该用户（404）
     */
    @Transactional(readOnly = true)
    public StatsResponse getStats(Long userId, Long linkId, Integer days) {
        ShortLink link = requireOwnedLink(userId, linkId);

        int effectiveDays = resolveDays(days);
        LocalDate today = LocalDate.now();
        LocalDate from = today.minusDays(effectiveDays - 1L);

        Map<LocalDate, LinkStat> storedByDate = linkStatRepository
                .findByLinkIdAndStatDateBetweenOrderByStatDateAsc(linkId, from, today)
                .stream()
                .collect(Collectors.toMap(LinkStat::getStatDate, Function.identity()));

        List<DailyStat> daily = new ArrayList<>(effectiveDays);
        long totalPv = 0L;

        for (LocalDate date = from; !date.isAfter(today); date = date.plusDays(1)) {
            LinkStat stored = storedByDate.get(date);
            long storedPv = stored != null ? stored.getPv() : 0L;
            long storedUv = stored != null ? stored.getUv() : 0L;

            long pv = storedPv + statsCounter.readPv(link.getCode(), date);
            long uv = Math.max(storedUv, statsCounter.readUv(link.getCode(), date));

            daily.add(new DailyStat(date, pv, uv));
            totalPv += pv;
        }

        return new StatsResponse(linkId, link.getCode(), from, today, totalPv, daily);
    }

    /**
     * 补零的日期循环。
     *
     * <p>遍历区间内的<b>每一个</b>日期，而不是只遍历数据库返回的行。数据库里没有记录的
     * 日期（当天无人访问）也必须出现在结果中，值为 0（AC-07.2）。
     *
     * <p>这是服务端的职责而不是让前端补齐：前端画折线图时缺日期会导致 X 轴断裂，
     * 而且"哪些日期应该有数据"这个信息只有服务端掌握（它知道查询区间）。
     * 让每个消费方各自补零，必然出现图表与表格口径不一致的情况。
     */
    private int resolveDays(Integer days) {
        ShortLinkProperties.Stats config = properties.stats();
        if (days == null) {
            return config.defaultQueryDays();
        }
        if (days < 1) {
            // 交给全局异常处理器转换为 400，并带上字段名
            throw BusinessException.of(ErrorCode.VALIDATION_FAILED, List.of(
                    new io.github.yueyinxin.shortlink.common.exception.FieldViolation(
                            "days", "统计天数必须大于 0")));
        }
        return Math.min(days, config.maxQueryDays());
    }

    private ShortLink requireOwnedLink(Long userId, Long linkId) {
        return linkRepository.findByIdAndUserId(linkId, userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "短链不存在"));
    }
}
