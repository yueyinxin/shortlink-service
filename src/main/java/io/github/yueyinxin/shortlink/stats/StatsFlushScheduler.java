package io.github.yueyinxin.shortlink.stats;

import io.github.yueyinxin.shortlink.config.properties.ShortLinkProperties;
import io.github.yueyinxin.shortlink.link.ShortLink;
import io.github.yueyinxin.shortlink.link.ShortLinkRepository;
import io.github.yueyinxin.shortlink.stats.infrastructure.LinkStatRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 统计刷库任务：把 Redis 中的访问计数定期落库。
 *
 * <h2>为什么必须同时处理「当日」与「前一日」</h2>
 * 当日 23:59:59 之后，本任务可能还没来得及跑完最后一次刷库，日期已经跨到了第二天。
 * 此时前一日残留的计数若不再被处理，那部分访问就<b>永久丢失</b>了。
 * 因此每次执行都同时处理今天和昨天两个日期。
 *
 * <h2>为什么用 SCAN 而不是 KEYS</h2>
 * {@code KEYS pattern} 会遍历整个键空间且<b>阻塞 Redis 的所有其他命令</b>。
 * 在有几十万个短链的实例上执行一次，足以造成数百毫秒的服务停顿 ——
 * 而本任务每 60 秒执行一次，等于每 60 秒制造一次人为的延迟毛刺。
 *
 * <p>{@code SCAN} 使用游标分批返回，每次只遍历一小部分键，不阻塞其他命令。
 * 代价是：在遍历过程中新加入的键可能被遗漏或重复返回。这对本任务是可以接受的 ——
 * 遗漏的键会在下一轮（60 秒后）被处理，而重复处理是安全的（见下）。
 *
 * <h2>幂等性</h2>
 * 本任务的每一步都设计为可安全重试：
 * <ul>
 *   <li>PV 用"原子取出并清零"，同一份增量不可能被取出两次；</li>
 *   <li>UV 写入时取 {@code GREATEST}，重复写入同一个值不产生变化；</li>
 *   <li>UPSERT 由数据库原子完成，不存在"先查后插"的竞态。</li>
 * </ul>
 *
 * <h2>已知限制：多实例部署</h2>
 * 本任务使用 {@code @Scheduled}，在多实例部署时会<b>每个实例都执行一遍</b>。
 * 由于上述幂等设计，这不会导致数据错误，但会产生重复的无效工作。
 * 正确的做法是引入分布式锁（Redisson / ShedLock），或者按配置只在一个实例上启用
 * （{@code shortlink.stats.enabled=false} 可以关闭其他实例的任务）。
 * 这一点已记录在架构建档的「已知风险」中。
 */
@Component
public class StatsFlushScheduler {

    private static final Logger log = LoggerFactory.getLogger(StatsFlushScheduler.class);

    private static final String PV_KEY_PREFIX = "shortlink:stats:pv:";
    private static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter.ofPattern("yyyyMMdd");

    /** SCAN 每次返回的键数量提示。取值需要权衡：太小则往返次数多，太大则单次耗时长。 */
    private static final long SCAN_BATCH_SIZE = 500L;

    /** 单次刷库最多处理的短链数，避免一次任务运行过久（下一轮会被调度器跳过并发执行）。 */
    private static final int MAX_CODES_PER_RUN = 10_000;

    private final StringRedisTemplate redisTemplate;
    private final StatsCounter statsCounter;
    private final ShortLinkRepository linkRepository;
    private final LinkStatRepository linkStatRepository;
    private final ShortLinkProperties properties;

    public StatsFlushScheduler(StringRedisTemplate redisTemplate,
                               StatsCounter statsCounter,
                               ShortLinkRepository linkRepository,
                               LinkStatRepository linkStatRepository,
                               ShortLinkProperties properties) {
        this.redisTemplate = redisTemplate;
        this.statsCounter = statsCounter;
        this.linkRepository = linkRepository;
        this.linkStatRepository = linkStatRepository;
        this.properties = properties;
    }

    /**
     * 定期刷库。
     *
     * <p>用 {@code fixedDelay} 而非 {@code fixedRate}：{@code fixedDelay} 在<b>上一次执行结束后</b>
     * 才开始计时，因此不会出现任务堆积；{@code fixedRate} 按固定频率触发，
     * 若某次执行超过了周期，下一次会立即开始，多次堆积后可能耗尽线程池。
     *
     * <p>周期通过属性占位符读取毫秒数。不用 SpEL 引用配置 Bean
     * （{@code #{@shortLinkProperties...}}）：{@code @ConfigurationProperties} 注册的
     * Bean 名是 {@code <prefix>-<全限定类名>} 的形式，直接写短名解析不到。
     */
    @Scheduled(fixedDelayString = "${shortlink.stats.flush-interval-millis}")
    public void flush() {
        if (!properties.stats().enabled()) {
            return;
        }

        LocalDate today = LocalDate.now();
        LocalDate yesterday = today.minusDays(1);

        try {
            // 先处理前一日：跨天时它的数据已完整，应当尽快落库并释放 Redis 内存
            int flushedYesterday = flushDate(yesterday, true);
            int flushedToday = flushDate(today, false);

            if (flushedYesterday + flushedToday > 0) {
                log.info("统计刷库完成: 前一日 {} 条, 当日 {} 条", flushedYesterday, flushedToday);
            }
        } catch (RuntimeException e) {
            // 刷库失败不能让调度线程终止 —— 否则任务会永久停止运行。
            // Spring 的调度器在任务抛出异常后会继续下一次调度，但这里显式捕获
            // 可以保证"一次失败不影响后续日期"。
            log.error("统计刷库任务执行失败，将在下个周期重试", e);
        }
    }

    /**
     * 刷写某一日期的所有待落库计数。
     *
     * @param date        目标日期
     * @param dayIsClosed 该日期是否已经结束。结束时才清理 Redis 中的 UV 的 HyperLogLog，
     *                    因为当日结束前它还需要继续被读取以支持实时查询
     * @return 实际写入的记录数
     */
    private int flushDate(LocalDate date, boolean dayIsClosed) {
        Set<String> codes = scanPendingCodes(date);
        if (codes.isEmpty()) {
            return 0;
        }

        Map<String, Long> linkIdByCode = resolveLinkIds(codes);
        int flushed = 0;

        for (String code : codes) {
            Long linkId = linkIdByCode.get(code);

            if (linkId == null) {
                // 短链已被删除，但它的计数键还残留在 Redis 中（可能是删除时未清理，
                // 也可能是删除后仍有缓存的跳转请求）。清理掉即可，不写入数据库 ——
                // 否则会触发外键约束失败。
                statsCounter.clearDay(code, date);
                continue;
            }

            long pv = statsCounter.drainPv(code, date);
            long uv = statsCounter.readUv(code, date);

            if (pv == 0L && uv == 0L && !dayIsClosed) {
                // 当日且无数据：可能只是 SCAN 恰好扫到了一个刚被清空的键，跳过
                continue;
            }

            // PV 为 0 但 UV 非 0 是可能的（该时间段内只有重复访客）。
            // 这种情况下仍要写入，因为 UV 是绝对值，需要把更大的估值落库。
            if (pv > 0L || uv > 0L) {
                linkStatRepository.upsert(linkId, date, pv, uv);
                flushed++;
            }

            if (dayIsClosed) {
                statsCounter.clearDay(code, date);
            }
        }

        return flushed;
    }

    /**
     * 扫描某一日有待落库数据的短码。
     *
     * <p>只匹配 PV 键（{@code shortlink:stats:pv:{code}:{date}}）。任何一次访问都会先
     * {@code INCR} PV 键，因此"存在 PV 键"等价于"该短链在该日期有访问"。
     * UV 的键不用于发现，避免两倍数量的扫描。
     */
    private Set<String> scanPendingCodes(LocalDate date) {
        String suffix = ":" + date.format(DATE_FORMAT);
        String pattern = PV_KEY_PREFIX + "*" + suffix;
        Set<String> codes = new HashSet<>();

        try (Cursor<String> cursor = redisTemplate.scan(
                ScanOptions.scanOptions().match(pattern).count(SCAN_BATCH_SIZE).build())) {

            while (cursor.hasNext() && codes.size() < MAX_CODES_PER_RUN) {
                String key = cursor.next();
                String code = extractCode(key, suffix);
                if (code != null) {
                    codes.add(code);
                }
            }
        } catch (RuntimeException e) {
            log.warn("扫描待刷库统计键失败: date={}", date, e);
            return Set.of();
        }

        if (codes.size() >= MAX_CODES_PER_RUN) {
            log.warn("单次刷库达到处理上限 {} 条，剩余将在下个周期处理。"
                    + "若持续出现，说明短链数量已超出当前刷库周期的处理能力，需要调小周期或分片处理",
                    MAX_CODES_PER_RUN);
        }
        return codes;
    }

    /**
     * 从 Redis 键中提取短码。
     *
     * <p>键格式为 {@code shortlink:stats:pv:{code}:{yyyyMMdd}}。用前缀与后缀截取，
     * 而不是按分隔符切分 —— 短码只含字母数字且长度固定，但截取法对任何短码都成立，
     * 不依赖"短码中不含分隔符"这一假设。
     *
     * <p>注意 {@code SCAN} 的匹配模式中 {@code *} 可能跨过冒号，理论上会匹配到
     * 格式不符的键（例如未来引入其他 {@code shortlink:stats:} 子类型）。
     * 因此这里校验截取结果，不符合的直接返回 {@code null} 而不是当作短码使用 ——
     * 把一个错误的短码传给数据库查询不会造成危害，但会让日志充满误导性记录。
     */
    private static String extractCode(String key, String suffix) {
        if (!key.startsWith(PV_KEY_PREFIX) || !key.endsWith(suffix)) {
            return null;
        }
        int from = PV_KEY_PREFIX.length();
        int to = key.length() - suffix.length();
        if (to <= from) {
            return null;
        }
        return key.substring(from, to);
    }

    /**
     * 批量把短码解析为主键。
     *
     * <p>用一次 {@code IN} 查询代替 N 次单条查询。刷库一次可能涉及上万个短链，
     * 逐个查询会产生同样数量的数据库往返 —— 这在流量高峰期足以把连接池占满。
     */
    private Map<String, Long> resolveLinkIds(Set<String> codes) {
        List<String> codeList = new ArrayList<>(codes);
        List<ShortLink> links = linkRepository.findByCodeIn(codeList);
        return links.stream().collect(Collectors.toMap(
                ShortLink::getCode,
                ShortLink::getId,
                // 短码上有唯一索引，理论上不会重复。保留合并函数以避免
                // 因数据异常抛出 IllegalStateException 中断整批刷库。
                (existing, replacement) -> existing
        ));
    }
}
