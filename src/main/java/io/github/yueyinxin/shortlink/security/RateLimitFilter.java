package io.github.yueyinxin.shortlink.security;

import io.github.yueyinxin.shortlink.auth.jwt.AuthenticatedUser;
import io.github.yueyinxin.shortlink.common.exception.ErrorCode;
import io.github.yueyinxin.shortlink.common.ratelimit.RateLimitResult;
import io.github.yueyinxin.shortlink.common.ratelimit.RateLimiter;
import io.github.yueyinxin.shortlink.common.response.ApiErrorWriter;
import io.github.yueyinxin.shortlink.common.util.ClientIpResolver;
import io.github.yueyinxin.shortlink.config.properties.ShortLinkProperties;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpMethod;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.Duration;

/**
 * 限流过滤器。
 *
 * <p><b>本类位于 {@code security} 包而不是 {@code common.ratelimit} 包</b>，
 * 原因是它需要读取认证上下文中的用户身份（创建接口按用户维度限流）。
 * {@code common} 包被所有功能域依赖，一旦它反过来依赖 {@code auth} 就形成了循环依赖。
 *
 * <p>这个位置不是一开始就对的 —— 架构测试 {@code ArchitectureTest} 捕获了
 * {@code common → auth} 的反向依赖。它揭示的事实是：本类并非"通用工具"，
 * 而是一个依赖于安全上下文的安全组件。把它和纯粹的限流原语
 * （{@code RateLimiter} / {@code RedisRateLimiter}，它们确实与业务无关）
 * 分开，才是正确的划分。
 *
 * <p>限流放在过滤器而不是控制器或服务里，关键原因是<b>被拒绝的请求不应触碰任何业务代码</b>。
 * 如果放在服务层，超限的请求依然会走完参数解析、鉴权、事务开启等流程，
 * 消耗的正是我们想要保护的资源。
 *
 * <h2>两个限流维度</h2>
 * <table border="1">
 *   <caption>限流规则</caption>
 *   <tr><th>端点</th><th>维度</th><th>默认限额</th><th>理由</th></tr>
 *   <tr>
 *     <td>{@code GET /{code}}</td><td>客户端 IP</td><td>300 次/分钟</td>
 *     <td>匿名接口，只有 IP 可作标识。限额较高，因为正常浏览会连续产生跳转请求</td>
 *   </tr>
 *   <tr>
 *     <td>{@code POST /api/v1/links}</td><td>用户 ID</td><td>30 次/分钟</td>
 *     <td>创建是低频写操作，按用户维度限流比按 IP 更准确（同一办公网出口的多个用户不会互相影响）</td>
 *   </tr>
 * </table>
 *
 * <p>本过滤器注册在 {@code JwtAuthenticationFilter} <b>之后</b>，因此创建接口能拿到
 * 已认证的 {@code userId}。跳转接口是匿名的，此时安全上下文中没有身份，按 IP 计数。
 */
@Component
public class RateLimitFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(RateLimitFilter.class);

    private static final Duration WINDOW = Duration.ofMinutes(1);
    private static final String RETRY_AFTER_HEADER = "Retry-After";
    private static final String API_PREFIX = "/api/";
    private static final String ACTUATOR_PREFIX = "/actuator/";
    private static final String CREATE_LINK_PATH = "/api/v1/links";
    private static final String ANONYMOUS_IP_SUBJECT = "anonymous";

    private final RateLimiter rateLimiter;
    private final ClientIpResolver clientIpResolver;
    private final ApiErrorWriter errorWriter;
    private final ShortLinkProperties properties;

    public RateLimitFilter(RateLimiter rateLimiter,
                           ClientIpResolver clientIpResolver,
                           ApiErrorWriter errorWriter,
                           ShortLinkProperties properties) {
        this.rateLimiter = rateLimiter;
        this.clientIpResolver = clientIpResolver;
        this.errorWriter = errorWriter;
        this.properties = properties;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {

        LimitRule rule = resolveRule(request);
        if (rule == null) {
            filterChain.doFilter(request, response);
            return;
        }

        String subject = resolveSubject(request, rule);
        RateLimitResult result = rateLimiter.tryAcquire(rule.keyPrefix() + ":" + subject, rule.limit(), WINDOW);

        if (!result.allowed()) {
            log.warn("请求被限流: 维度={}, 主体={}, 当前计数={}, 上限={}",
                    rule.keyPrefix(), subject, result.currentCount(), rule.limit());

            // Retry-After 是 HTTP 标准头，让客户端的重试逻辑有依据，
            // 而不是盲目地立即重试（那只会继续被拒绝）。
            // 固定窗口下最长等待时间为一个完整窗口，因此返回窗口长度是安全的建议值。
            response.setHeader(RETRY_AFTER_HEADER, String.valueOf(WINDOW.toSeconds()));
            errorWriter.write(response, ErrorCode.RATE_LIMITED, ErrorCode.RATE_LIMITED.defaultMessage());
            return;
        }

        filterChain.doFilter(request, response);
    }

    /**
     * 解析本次请求适用的限流规则。
     *
     * @return 需要限流时返回规则，否则返回 {@code null} 表示直接放行
     */
    private LimitRule resolveRule(HttpServletRequest request) {
        String path = request.getRequestURI();

        // 健康检查与探针必须完全不受限流影响：它们由编排系统按固定频率调用，
        // 一旦被限流，编排系统会认为服务不可用并重启容器，形成"限流导致重启、
        // 重启导致更多探针请求"的恶性循环。
        if (path.startsWith(ACTUATOR_PREFIX) || path.equals("/actuator")) {
            return null;
        }

        // 创建短链：按用户限流
        if (HttpMethod.POST.matches(request.getMethod()) && CREATE_LINK_PATH.equals(path)) {
            return new LimitRule("create",
                    properties.rateLimit().createPerMinutePerUser(),
                    SubjectType.USER);
        }

        // 短链跳转：GET + 单段路径 + 不是 API 路径。
        // 用"单段路径"来识别而不是用正则匹配短码，是因为短码格式属于领域知识，
        // 过滤器只应关心"这条请求走的是跳转入口"这一事实。非法短码交给控制器返回 404。
        if (HttpMethod.GET.matches(request.getMethod()) && isSingleSegmentPath(path)) {
            return new LimitRule("redirect",
                    properties.rateLimit().redirectPerMinutePerIp(),
                    SubjectType.CLIENT_IP);
        }

        return null;
    }

    /**
     * 判断路径是否为单段，即形如 {@code /abc123} 且不属于预留前缀。
     *
     * <p>{@code /} 本身（根路径）不算 —— 它是一个无意义的请求，不该消耗跳转配额。
     */
    private static boolean isSingleSegmentPath(String path) {
        if (path == null || path.length() < 2 || path.charAt(0) != '/') {
            return false;
        }
        // 排除已知的功能前缀，避免把接口文档、静态资源等纳入跳转限流
        if (path.startsWith(API_PREFIX) || path.startsWith(ACTUATOR_PREFIX)
                || path.startsWith("/swagger-ui") || path.startsWith("/v3/")) {
            return false;
        }
        // 单段路径：去掉开头的 / 之后不再含 /
        return path.indexOf('/', 1) < 0;
    }

    private String resolveSubject(HttpServletRequest request, LimitRule rule) {
        if (rule.subjectType() == SubjectType.USER) {
            AuthenticatedUser user = currentUser();
            if (user != null) {
                return String.valueOf(user.userId());
            }
            // 理论上不会走到这里：/api/** 需要认证，而本过滤器在鉴权之后执行。
            // 但若真的发生（例如后来有人调整了过滤器的相对顺序），
            // 退回按 IP 限流比"不限流"安全 —— 后者会让接口完全失去保护。
            log.debug("创建接口未取到认证用户，回退为按 IP 限流");
        }
        String ip = clientIpResolver.resolve(request);
        return ip != null ? ip : ANONYMOUS_IP_SUBJECT;
    }

    private static AuthenticatedUser currentUser() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null && authentication.getPrincipal() instanceof AuthenticatedUser user) {
            return user;
        }
        return null;
    }

    /** 限流维度。 */
    private enum SubjectType {
        USER,
        CLIENT_IP
    }

    /**
     * @param keyPrefix   Redis 键前缀，同时用于日志与监控区分限流来源
     * @param limit       窗口内允许的请求数
     * @param subjectType 计数主体的类型
     */
    private record LimitRule(String keyPrefix, int limit, SubjectType subjectType) {
    }
}
