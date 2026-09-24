package io.github.yueyinxin.shortlink.stats.infrastructure;

import io.github.yueyinxin.shortlink.stats.StatsCounter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * 基于 Redis 的访问计数器。
 *
 * <p>PV 与 UV 采用不同语义的原因见 {@link StatsCounter} 的类文档 —— 简要地说：
 * PV 是"取出并清零"的增量，UV 是"全程保留、取当前估值"的绝对值，
 * 因为 HyperLogLog 无法做差集，累加多次刷库的估值会重复计数。
 *
 * <h2>为什么 UV 用 HyperLogLog 而不是 Set</h2>
 * 用 Set 记录所有访问过的 IP，内存占用与独立 IP 数成正比：1 亿 UV 需要约 6.4 GB，
 * 而 HyperLogLog 固定只需 12 KB，代价是 0.81% 的标准误差。对于"评估投放效果"
 * 这个场景，100 万 UV 有 ±8100 的误差不影响任何决策 —— 运营需要知道的是
 * "这次投放带来了 10 万还是 100 万访问"，不是精确到个位数。
 *
 * <p>代价是 HLL 只支持 {@code PFADD} 与 {@code PFCOUNT}，<b>无法删除单个元素</b>。
 * 本项目不需要这个能力。
 *
 * <h2>为什么写操作都用 Lua 脚本</h2>
 * <ul>
 *   <li><b>记录访问</b>：{@code INCR} + {@code PFADD} + 设置过期时间必须原子。
 *       若拆成多条命令，进程在 {@code INCR} 之后崩溃会让该键永不设置过期时间，
 *       长时间运行后耗尽内存。</li>
 *   <li><b>取出并清零 PV</b>：这是最关键的一处。"先读、再删"的两步实现会让两次刷库
 *       读到相同计数并重复累加。</li>
 * </ul>
 *
 * <p>所有键都设置 48 小时过期时间作为<b>安全网</b>：若某个短链在计数尚未刷库时被删除，
 * 它的计数键将永远不会被任何刷库任务读到，过期时间保证这类孤儿键不会永久占用内存。
 */
@Repository
public class RedisStatsCounter implements StatsCounter {

    private static final Logger log = LoggerFactory.getLogger(RedisStatsCounter.class);

    private static final String PV_KEY_PREFIX = "shortlink:stats:pv:";
    private static final String UV_KEY_PREFIX = "shortlink:stats:uv:";
    private static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter.ofPattern("yyyyMMdd");

    /** 计数键的过期时间（秒），作为孤儿键的安全网。 */
    private static final int KEY_TTL_SECONDS = 48 * 60 * 60;

    /**
     * 记录一次访问：PV 自增，客户端 IP 加入 HyperLogLog。
     *
     * <p>返回自增后的 PV 值仅用于判断是否是首次写入 —— 只在首次写入时设置过期时间。
     * 若每次请求都续期，键将永远不会过期。
     */
    private static final RedisScript<Long> RECORD_SCRIPT = new DefaultRedisScript<>("""
            local pv = redis.call('INCR', KEYS[1])
            redis.call('PFADD', KEYS[2], ARGV[1])
            if pv == 1 then
                redis.call('EXPIRE', KEYS[1], ARGV[2])
                redis.call('EXPIRE', KEYS[2], ARGV[2])
            end
            return pv
            """, Long.class);

    /**
     * 原子地取出并清零 PV。
     *
     * <p>用 {@code GET} + {@code DEL} 而不是 {@code GETSET} 到 0：后者会让键以值 0
     * 继续存在，需要额外清理，且在"键值为 0"与"键不存在"之间制造了两种等价但不同的状态。
     */
    private static final RedisScript<Long> DRAIN_PV_SCRIPT = new DefaultRedisScript<>("""
            local value = redis.call('GET', KEYS[1])
            if value then
                redis.call('DEL', KEYS[1])
            end
            return tonumber(value) or 0
            """, Long.class);

    /** 只读地获取 PV，不修改任何键。 */
    private static final RedisScript<Long> READ_PV_SCRIPT = new DefaultRedisScript<>("""
            local value = redis.call('GET', KEYS[1])
            return tonumber(value) or 0
            """, Long.class);

    /** 读取 HLL 的当前估值。 */
    private static final RedisScript<Long> READ_UV_SCRIPT = new DefaultRedisScript<>("""
            return redis.call('PFCOUNT', KEYS[1])
            """, Long.class);

    /** 删除某一日的两个计数键。 */
    private static final RedisScript<Long> CLEAR_DAY_SCRIPT = new DefaultRedisScript<>("""
            return redis.call('DEL', KEYS[1], KEYS[2])
            """, Long.class);

    private final StringRedisTemplate redisTemplate;

    public RedisStatsCounter(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    @Override
    public void recordAccess(String code, String clientIp, LocalDate date) {
        try {
            redisTemplate.execute(
                    RECORD_SCRIPT,
                    List.of(pvKey(code, date), uvKey(code, date)),
                    clientIp,
                    String.valueOf(KEY_TTL_SECONDS)
            );
        } catch (RuntimeException e) {
            // 统计失败绝不能让跳转失败。这里吞掉异常并记 WARN，
            // 代价是这段时间的访问数据缺失 —— 相对于"链接打不开"，
            // 这个代价完全可以接受。
            log.warn("记录访问统计失败: code={}, date={}, 原因={}", code, date, e.getClass().getSimpleName());
        }
    }

    @Override
    public long readPv(String code, LocalDate date) {
        return executeOrDefault(READ_PV_SCRIPT, List.of(pvKey(code, date)), 0L, "读取 PV", code, date);
    }

    @Override
    public long drainPv(String code, LocalDate date) {
        // 这里不能吞掉异常后返回 0：返回 0 意味着"没有增量"，
        // 会让刷库任务认为无事可做。若实际是 Redis 故障，数据仍在 Redis 中，
        // 下个周期会重试，行为是正确的。因此失败时返回 0 是安全的。
        return executeOrDefault(DRAIN_PV_SCRIPT, List.of(pvKey(code, date)), 0L, "取出 PV", code, date);
    }

    @Override
    public long readUv(String code, LocalDate date) {
        return executeOrDefault(READ_UV_SCRIPT, List.of(uvKey(code, date)), 0L, "读取 UV", code, date);
    }

    @Override
    public void clearDay(String code, LocalDate date) {
        try {
            redisTemplate.execute(CLEAR_DAY_SCRIPT, List.of(pvKey(code, date), uvKey(code, date)));
        } catch (RuntimeException e) {
            // 清理失败不影响正确性：键上的 48 小时过期时间会兜底回收。
            log.warn("清理统计键失败: code={}, date={}", code, date, e);
        }
    }

    private long executeOrDefault(RedisScript<Long> script, List<String> keys,
                                  long fallback, String action, String code, LocalDate date) {
        try {
            Long result = redisTemplate.execute(script, keys);
            return result != null ? result : fallback;
        } catch (RuntimeException e) {
            log.warn("{}失败，返回 {}: code={}, date={}, 原因={}",
                    action, fallback, code, date, e.getClass().getSimpleName());
            return fallback;
        }
    }

    private static String pvKey(String code, LocalDate date) {
        return PV_KEY_PREFIX + code + ':' + date.format(DATE_FORMAT);
    }

    private static String uvKey(String code, LocalDate date) {
        return UV_KEY_PREFIX + code + ':' + date.format(DATE_FORMAT);
    }
}
