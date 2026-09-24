package io.github.yueyinxin.shortlink.common.filter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * 为每个请求分配追踪 ID 并写入日志上下文。
 *
 * <p>没有它，排查线上问题只能在时间轴上靠时间戳猜测哪些日志属于同一个请求 ——
 * 在并发环境下这种猜测基本不可靠。
 *
 * <p>该过滤器注册为最高优先级，保证后续所有组件（包括 Spring Security 的过滤器链）
 * 产生的日志都带有 traceId。
 *
 * <h2>为什么不直接信任客户端传来的追踪 ID</h2>
 * 追踪 ID 会被写入日志。若原样接受客户端传入的任意字符串，攻击者可以
 * 构造含换行符的值来伪造日志行（日志注入），或写入超长字符串撑大日志文件。
 * 因此只接受符合预期格式的值，其余一律重新生成。
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class TraceIdFilter extends OncePerRequestFilter {

    public static final String TRACE_ID_MDC_KEY = "traceId";
    public static final String TRACE_ID_HEADER = "X-Trace-Id";

    private static final int MAX_TRACE_ID_LENGTH = 64;

    /** 只允许十六进制与短横线：兼容常见追踪系统的 ID 格式，同时排除控制字符与空白。 */
    private static final Pattern SAFE_TRACE_ID = Pattern.compile("^[0-9a-fA-F-]{8,64}$");

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {

        String traceId = resolveTraceId(request);
        MDC.put(TRACE_ID_MDC_KEY, traceId);
        // 回传给客户端：用户报障时提供这个 ID，就能直接定位到具体请求的全部日志
        response.setHeader(TRACE_ID_HEADER, traceId);

        try {
            filterChain.doFilter(request, response);
        } finally {
            // 必须清理。线程池中的线程会被复用，残留的 MDC 值会污染下一个请求的日志，
            // 导致日志里出现"完全无关的两个请求共用一个 traceId"这种极具误导性的现象。
            MDC.remove(TRACE_ID_MDC_KEY);
        }
    }

    private String resolveTraceId(HttpServletRequest request) {
        String incoming = request.getHeader(TRACE_ID_HEADER);
        if (incoming != null
                && !incoming.isBlank()
                && incoming.length() <= MAX_TRACE_ID_LENGTH
                && SAFE_TRACE_ID.matcher(incoming).matches()) {
            return incoming;
        }
        // 去掉 UUID 中的短横线并截断，得到 32 位十六进制，符合上面的格式约束
        return UUID.randomUUID().toString().replace("-", "");
    }
}
