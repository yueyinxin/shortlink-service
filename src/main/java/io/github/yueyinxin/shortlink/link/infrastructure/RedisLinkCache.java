package io.github.yueyinxin.shortlink.link.infrastructure;

import tools.jackson.databind.ObjectMapper;
import io.github.yueyinxin.shortlink.config.properties.ShortLinkProperties;
import io.github.yueyinxin.shortlink.link.LinkCache;
import io.github.yueyinxin.shortlink.link.LinkSnapshot;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Repository;

import java.time.Duration;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 基于 Redis 的短链缓存。
 *
 * <h2>降级策略</h2>
 * 所有 Redis 操作都被捕获并降级为"缓存未命中"，而不是向上抛异常。
 * 这是本项目可用性设计的核心：<b>Redis 是性能组件，不是可用性的单点</b>。
 * 缓存不可用时跳转会退化为直查数据库 —— 变慢，但仍可用（AC-05.6）。
 * 若让异常向上传播，一个小概率的缓存抖动就会变成用户可见的 500。
 *
 * <p>降级分支全部打 WARN 日志，因为它是"服务正在以降低的性能运行"这一事实的信号，
 * 需要在监控上可见，而不能被静默吞掉。
 *
 * <h2>TTL 抖动</h2>
 * 正常缓存的 TTL 为 {@code ttl × (1 ± jitterRatio)} 的随机值。若所有短链的 TTL 相同，
 * 批量导入的短链会在同一秒集中过期，届时全部请求穿透到数据库 —— 这就是缓存雪崩。
 */
@Repository
public class RedisLinkCache implements LinkCache {

    private static final Logger log = LoggerFactory.getLogger(RedisLinkCache.class);

    private static final String KEY_PREFIX = "shortlink:link:";

    /**
     * 空值标记。
     *
     * <p>用一个不可能出现在合法载荷中的字符串。合法载荷是 {@link LinkSnapshot} 的
     * JSON 表示，必定以 {@code '{}'} 开头，因此判定方式简单且不会误判。
     * 不用 {@code null} 或空串：Redis 中无法存储 Java 的 null，
     * 而空串与"键存在但值为空"难以区分。
     */
    private static final String ABSENT_MARKER = "ABSENT";

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    private final ShortLinkProperties properties;

    public RedisLinkCache(StringRedisTemplate redisTemplate,
                          ObjectMapper objectMapper,
                          ShortLinkProperties properties) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
        this.properties = properties;
    }

    @Override
    public Lookup get(String code) {
        String value;
        try {
            value = redisTemplate.opsForValue().get(key(code));
        } catch (RuntimeException e) {
            log.warn("读取缓存失败，降级为查库: code={}, 原因={}", code, e.getClass().getSimpleName());
            return new Lookup.Miss();
        }

        if (value == null) {
            return new Lookup.Miss();
        }
        if (ABSENT_MARKER.equals(value)) {
            return new Lookup.Absent();
        }

        try {
            return new Lookup.Hit(objectMapper.readValue(value, LinkSnapshot.class));
        } catch (Exception e) {
            // 反序列化失败说明缓存中的数据结构与当前版本不兼容
            // （例如发版时给 LinkSnapshot 增删了字段，而旧数据尚未过期）。
            // 按"未命中"处理：回查数据库并写回新格式，缓存会自行修复。
            // 若这里抛异常，一次发版就会让所有未过期的缓存条目变成请求失败。
            log.warn("缓存反序列化失败，按未命中处理并覆盖: code={}", code, e);
            return new Lookup.Miss();
        }
    }

    @Override
    public void put(LinkSnapshot snapshot) {
        try {
            String json = objectMapper.writeValueAsString(snapshot);
            redisTemplate.opsForValue().set(key(snapshot.code()), json, jitteredTtl());
        } catch (Exception e) {
            // 写缓存失败不影响本次请求的正确性 —— 结果已经从数据库拿到了。
            // 只是下一次还要再查一次库。记录后继续。
            log.warn("写入缓存失败: code={}", snapshot.code(), e);
        }
    }

    @Override
    public void putAbsent(String code) {
        try {
            redisTemplate.opsForValue().set(key(code), ABSENT_MARKER, properties.cache().nullTtl());
        } catch (RuntimeException e) {
            log.warn("写入空值缓存失败: code={}", code, e);
        }
    }

    @Override
    public void evict(String code) {
        try {
            redisTemplate.delete(key(code));
        } catch (RuntimeException e) {
            // 删除失败意味着缓存中可能残留旧数据，最长在一个 TTL 内不一致。
            // 这是需要关注的信号（用户会看到已更新的链接仍跳转到旧地址），故记 WARN。
            log.warn("删除缓存失败，可能存在短暂不一致: code={}", code, e);
        }
    }

    /**
     * 计算带随机抖动的 TTL。
     *
     * <p>抖动比例为配置项（默认 ±10%）。{@code ThreadLocalRandom} 避免多线程竞争。
     */
    private Duration jitteredTtl() {
        Duration base = properties.cache().ttl();
        double ratio = properties.cache().jitterRatio();
        if (ratio <= 0) {
            return base;
        }
        // nextDouble(-ratio, +ratio) 生成对称区间内的偏移
        double factor = 1.0 + ThreadLocalRandom.current().nextDouble(-ratio, ratio);
        long millis = Math.max(1L, (long) (base.toMillis() * factor));
        return Duration.ofMillis(millis);
    }

    private static String key(String code) {
        return KEY_PREFIX + code;
    }
}
