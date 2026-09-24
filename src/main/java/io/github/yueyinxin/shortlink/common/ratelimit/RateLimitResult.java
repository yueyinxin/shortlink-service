package io.github.yueyinxin.shortlink.common.ratelimit;

/**
 * 限流判定结果。
 *
 * @param allowed        是否放行
 * @param currentCount   当前窗口内已累计的请求数
 * @param remaining      剩余配额（不小于 0）
 */
public record RateLimitResult(boolean allowed, long currentCount, long remaining) {

    public static RateLimitResult allowed(long currentCount, int limit) {
        return new RateLimitResult(true, currentCount, Math.max(0, limit - currentCount));
    }

    public static RateLimitResult rejected(long currentCount) {
        return new RateLimitResult(false, currentCount, 0);
    }
}
