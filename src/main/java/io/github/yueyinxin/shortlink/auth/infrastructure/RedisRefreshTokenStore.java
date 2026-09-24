package io.github.yueyinxin.shortlink.auth.infrastructure;

import io.github.yueyinxin.shortlink.auth.RefreshTokenStore;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Repository;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Optional;

/**
 * 基于 Redis 的刷新令牌存储。
 *
 * <h2>安全要点：存储的是令牌哈希，不是令牌明文</h2>
 * 键使用 {@code SHA-256(令牌明文)} 而不是明文本身。这样做的意义在于：
 * <ul>
 *   <li><b>Redis 数据泄露不等于令牌泄露。</b> 攻击者拿到的是哈希值，无法直接用它换取访问令牌。</li>
 *   <li><b>内存快照与 MONITOR 输出同样安全。</b> 运维执行 {@code MONITOR}、
 *       或对 Redis 做内存转储做故障分析时，看到的都不是可直接使用的凭证。</li>
 *   <li><b>日志与监控不会意外记录凭证。</b> Redis 慢查询日志、键空间通知都会打印键名。</li>
 * </ul>
 *
 * <p>这里不需要加盐或使用慢哈希（如 BCrypt）：令牌是 256 位的高熵随机值，
 * 不存在字典攻击或彩虹表的适用空间。用 SHA-256 是为了消除可读性，
 * 而不是为了抵抗暴力破解 —— 后者对高熵输入没有意义。
 *
 * <h2>键的结构</h2>
 * <pre>
 *   shortlink:refresh:&lt;sha256-hex&gt;  →  &lt;userId&gt;
 * </pre>
 * 带业务前缀的作用是：在同一 Redis 实例被多个应用共用时（不推荐，但常见），
 * 可以按前缀区分与清理；同时 {@code KEYS shortlink:refresh:*} 这类排查命令
 * 不会误伤其他应用的数据。
 */
@Repository
public class RedisRefreshTokenStore implements RefreshTokenStore {

    private static final String KEY_PREFIX = "shortlink:refresh:";

    private final StringRedisTemplate redisTemplate;

    public RedisRefreshTokenStore(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    @Override
    public void store(String token, Long userId, Duration ttl) {
        redisTemplate.opsForValue().set(key(token), String.valueOf(userId), ttl);
    }

    @Override
    public Optional<Long> consume(String token) {
        // getAndDelete 对应 Redis 的 GETDEL 命令，是单条原子操作。
        // 若改用 "get 后再 delete"，两步之间的竞态窗口会让同一个刷新令牌
        // 被并发使用两次 —— 详见 RefreshTokenStore#consume 的说明。
        String userId = redisTemplate.opsForValue().getAndDelete(key(token));
        if (userId == null) {
            return Optional.empty();
        }
        try {
            return Optional.of(Long.valueOf(userId));
        } catch (NumberFormatException e) {
            // 值不是合法数字说明 Redis 中的数据被外部改动或格式不兼容。
            // 返回空（视作无效令牌）而不是抛异常：认证失败本就该返回 401，
            // 抛异常会变成 500，把一个安全问题伪装成服务故障。
            return Optional.empty();
        }
    }

    @Override
    public void revoke(String token) {
        redisTemplate.delete(key(token));
    }

    private static String key(String token) {
        return KEY_PREFIX + sha256Hex(token);
    }

    private static String sha256Hex(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 是 JDK 必须支持的算法，走到这里说明 JVM 被裁剪过，
            // 属于部署环境问题，应当让应用启动期就失败而不是运行期随机失败
            throw new IllegalStateException("当前 JVM 不支持 SHA-256", e);
        }
    }
}
