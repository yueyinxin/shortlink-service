package io.github.yueyinxin.shortlink.common.util;

import io.github.yueyinxin.shortlink.config.properties.SecurityProperties;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Component;

/**
 * 客户端真实 IP 提取。
 *
 * <p>用于限流（按 IP 计数）与 UV 统计（按 IP 去重）。
 *
 * <h2>安全警告：X-Forwarded-For 可以被伪造</h2>
 * 任何客户端都可以自行构造 {@code X-Forwarded-For: 1.2.3.4} 头发送过来。
 * 如果服务端无条件采信这个头，攻击者每次请求换一个伪造 IP 就能完全绕过限流 ——
 * <b>限流形同虚设</b>。
 *
 * <p>因此本类默认<b>不信任</b>任何转发头，直接使用 TCP 连接的对端地址
 * （{@code getRemoteAddr()}）。只有在明确部署于受控反向代理之后时才应开启信任，
 * 且必须满足：<b>边缘代理必须覆盖（而非追加）客户端传入的 X-Forwarded-For</b>。
 * Nginx 的 {@code proxy_set_header X-Forwarded-For $remote_addr;} 就是这个语义
 * （注意不是 {@code $proxy_add_x_forwarded_for}，后者会保留客户端的伪造值）。
 *
 * <p>当信任开启时，取列表的<b>最左</b>值 —— 在被正确覆盖的前提下，第一个条目就是
 * 代理记录的真实客户端地址。
 */
@Component
public class ClientIpResolver {

    private static final String HEADER_X_FORWARDED_FOR = "X-Forwarded-For";
    private static final String HEADER_X_REAL_IP = "X-Real-IP";
    private static final String UNKNOWN = "unknown";
    private static final int MAX_IP_LENGTH = 45; // IPv6 最长 45 字符

    private final boolean trustForwardedHeaders;

    public ClientIpResolver(SecurityProperties properties) {
        this.trustForwardedHeaders = properties.trustForwardedHeaders();
    }

    /**
     * 解析客户端 IP，永不返回 {@code null}。
     *
     * <p>无法解析时返回 {@code "unknown"} 而不是 null：调用方（如 UV 的 HyperLogLog）
     * 需要一个确定的字符串参与去重，null 会让每个无法解析的请求都被视为不同的访客，
     * 从而虚增 UV。
     */
    public String resolve(HttpServletRequest request) {
        if (trustForwardedHeaders) {
            String forwarded = firstValidAddress(request.getHeader(HEADER_X_FORWARDED_FOR));
            if (forwarded != null) {
                return forwarded;
            }
            String realIp = sanitize(request.getHeader(HEADER_X_REAL_IP));
            if (realIp != null) {
                return realIp;
            }
        }

        String remote = sanitize(request.getRemoteAddr());
        return remote != null ? remote : UNKNOWN;
    }

    /**
     * 取逗号分隔列表中的第一个有效地址。
     *
     * <p>形如 {@code "203.0.113.7, 70.41.3.18, 150.172.238.178"}，依次是
     * [客户端, 代理1, 代理2]。取最左值。
     */
    private static String firstValidAddress(String headerValue) {
        if (headerValue == null || headerValue.isBlank()) {
            return null;
        }
        int comma = headerValue.indexOf(',');
        String first = (comma >= 0) ? headerValue.substring(0, comma) : headerValue;
        return sanitize(first);
    }

    /**
     * 净化单个地址值。
     *
     * <p>去掉空白与 {@code ip:port} 形式的端口部分，并限制长度。拒绝 {@code "unknown"}
     * —— 某些代理在无法获取地址时会写入这个字面量，把它当作真实 IP 会让所有这类请求
     * 被视为同一个访客，既不准确（UV 被低估）也不安全（限流把它们算作同一个人）。
     */
    private static String sanitize(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        if (trimmed.isEmpty() || UNKNOWN.equalsIgnoreCase(trimmed)) {
            return null;
        }
        if (trimmed.length() > MAX_IP_LENGTH) {
            return null;
        }

        // IPv4 的 "ip:port" 形式。IPv6 含冒号，不能简单按冒号切分，
        // 但方括号形式的 "[::1]:8080" 可以处理。
        if (trimmed.charAt(0) == '[') {
            int closing = trimmed.indexOf(']');
            if (closing > 0) {
                return trimmed.substring(1, closing);
            }
        } else {
            int colon = trimmed.indexOf(':');
            if (colon > 0 && trimmed.indexOf(':', colon + 1) < 0) {
                return trimmed.substring(0, colon);
            }
        }
        return trimmed;
    }
}
