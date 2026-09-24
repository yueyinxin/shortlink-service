package io.github.yueyinxin.shortlink.common.ratelimit;

import java.time.Duration;

/**
 * 限流器。
 *
 * <p>定义成接口是为了让 {@code RateLimitFilter} 的测试可以注入一个可控的实现，
 * 不需要启动 Redis 就能验证"超限时返回 429 且带 Retry-After 头"这类行为。
 */
public interface RateLimiter {

    /**
     * 尝试获取一个配额。
     *
     * @param key    限流维度标识，例如 {@code "redirect:203.0.113.7"} 或 {@code "create:42"}
     * @param limit  窗口内允许的最大次数
     * @param window 窗口长度
     * @return 判定结果，包含是否放行与剩余配额
     */
    RateLimitResult tryAcquire(String key, int limit, Duration window);
}
