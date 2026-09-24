package io.github.yueyinxin.shortlink.common.ratelimit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;

/**
 * 基于 Redis 的固定窗口限流器。
 *
 * <h2>为什么必须用 Lua 脚本</h2>
 * 限流的逻辑是"计数加一，若是第一次计数则设置过期时间，然后判断是否超限"。如果拆成
 * {@code INCR} / {@code EXPIRE} / 读取返回值三条命令在客户端执行，会出两个问题：
 * <ul>
 *   <li><b>非原子</b>：并发请求可能都读到相同的计数值，导致放行数超过上限。</li>
 *   <li><b>过期时间可能丢失</b>：若第一条 {@code INCR} 成功而 {@code EXPIRE} 未执行
 *       （进程崩溃、网络中断），这个键将<b>永不过期</b>。后果是该维度被永久限流 ——
 *       用户一旦触发了一次异常，就再也无法访问服务，比不限流更糟。</li>
 * </ul>
 * Lua 脚本在 Redis 中单线程原子执行，两个问题一并消除。
 *
 * <h2>固定窗口的已知局限</h2>
 * 固定窗口在窗口边界处允许最多 <b>2 倍</b>于限额的突发：假设限额 100 次/分钟，
 * 攻击者在 00:59 发 100 次、01:00 再发 100 次，这 200 次请求落在 2 秒内，
 * 但每个窗口内都没超限。
 *
 * <p>这个局限被接受，原因是：本项目的限流目标是<b>保护服务不被单一来源打满</b>，
 * 而不是精确的配额计费。2 倍突发不会击穿服务（真正的容量冗余按 10 倍设计）。
 *
 * <p>若将来需要精确限流，升级路径是用 ZSET 实现滑动窗口：
 * {@code ZREMRANGEBYSCORE} 清理过期成员后 {@code ZCARD} 计数。
 * 代价是每个请求都要在 Redis 中留下一个成员，内存占用与请求量成正比 ——
 * 这正是当前选择固定窗口（内存 O(1)）的原因。
 */
@Component
public class RedisRateLimiter implements RateLimiter {

    private static final Logger log = LoggerFactory.getLogger(RedisRateLimiter.class);

    private static final String KEY_PREFIX = "shortlink:ratelimit:";

    /**
     * 计数并返回当前值；仅当是本次窗口的第一次计数时设置过期时间。
     *
     * <p>{@code INCR} 返回的是自增后的值，因此 {@code current == 1} 精确对应
     * "这个键刚刚被创建"，只在这一刻设置过期时间。后续请求不会重置 TTL ——
     * 若每次请求都续期，窗口将永远不会结束，限流会退化成永久封禁。
     */
    private static final RedisScript<Long> INCREMENT_SCRIPT = new DefaultRedisScript<>("""
            local current = redis.call('INCR', KEYS[1])
            if current == 1 then
                redis.call('PEXPIRE', KEYS[1], ARGV[1])
            end
            return current
            """, Long.class);

    private final StringRedisTemplate redisTemplate;

    public RedisRateLimiter(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    @Override
    public RateLimitResult tryAcquire(String key, int limit, Duration window) {
        String redisKey = KEY_PREFIX + key;
        try {
            Long current = redisTemplate.execute(
                    INCREMENT_SCRIPT,
                    List.of(redisKey),
                    String.valueOf(window.toMillis())
            );

            if (current == null) {
                // 脚本返回空只可能发生在连接异常等边缘情况。
                // 此时选择<b>放行</b>：限流是保护措施，不是业务规则。
                // 因限流组件自身故障而拒绝用户访问，是把一个次要问题升级为主要故障。
                log.warn("限流脚本返回空值，本次请求放行: key={}", key);
                return RateLimitResult.allowed(0, limit);
            }

            return current <= limit
                    ? RateLimitResult.allowed(current, limit)
                    : RateLimitResult.rejected(current);

        } catch (RuntimeException e) {
            // Redis 故障时的降级策略：放行并告警。
            // 与缓存降级同样的思路 —— Redis 是性能组件，不应成为可用性的单点。
            // 代价是 Redis 故障期间限流失效，这需要在监控上体现（本分支会打 WARN 日志）。
            log.warn("限流检查失败，本次请求放行: key={}, 原因={}", key, e.getClass().getSimpleName());
            return RateLimitResult.allowed(0, limit);
        }
    }
}
