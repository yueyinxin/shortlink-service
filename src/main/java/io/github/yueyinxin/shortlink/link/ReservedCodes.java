package io.github.yueyinxin.shortlink.link;

import java.util.Locale;
import java.util.Set;

/**
 * 系统保留字集合。
 *
 * <p>短链的跳转路径是 {@code GET /{code}}，位于根路径下。任何与根路径下的既有路由
 * 或潜在路由重名的短码都会造成冲突 —— 要么短链永远无法跳转，要么把框架端点覆盖掉。
 * 因此这些名字必须被拒绝。
 *
 * <p>集合的划分依据：
 * <ol>
 *   <li><b>已有路由</b>：{@code api}、{@code actuator}、{@code swagger-ui}、{@code v3}
 *       —— 与现有接口路径的前缀冲突。</li>
 *   <li><b>常见静态资源与浏览器探测</b>：{@code favicon}、{@code robots}、{@code sitemap}、
 *       {@code static}、{@code assets} —— 浏览器或爬虫会主动请求这些路径。
 *       若不保留，爬虫的高频 404 请求会消耗跳转接口的限流配额。</li>
 *   <li><b>运维与安全探测</b>：{@code health}、{@code metrics}、{@code prometheus}、
 *       {@code .well-known}（去点后为 {@code well-known}）—— 保留它们可以避免
 *       把安全扫描流量引导到业务逻辑上。</li>
 *   <li><b>未来可能新增的路由</b>：{@code login}、{@code register}、{@code admin}、
 *       {@code docs}、{@code dashboard} —— 预留。这类"未来会用到"的保留成本几乎为零，
 *       而一旦被用户占用，后续启用该路由时必须做数据迁移。</li>
 * </ol>
 *
 * <p>匹配是<b>大小写不敏感</b>的（见 {@link ShortCode#isReserved()}），
 * 因为不同 Web 容器与反向代理对路径大小写的处理不一致。
 */
public final class ReservedCodes {

    private static final Set<String> RESERVED = Set.of(
            // 已有路由前缀
            "api", "actuator", "swagger-ui", "swagger", "v3", "v2",
            // 静态资源与浏览器探测
            "favicon", "robots", "sitemap", "static", "assets", "images", "css", "js",
            // 运维与安全探测
            "health", "healthz", "readyz", "livez", "metrics", "prometheus", "status",
            "well-known", "version", "info", "env", "beans", "configprops", "heapdump",
            // 未来的路由预留
            "login", "logout", "register", "signup", "signin", "admin", "dashboard",
            "docs", "doc", "help", "about", "terms", "privacy",
            // 常见服务名，避免与潜在的基础设施路径冲突
            "www", "mail", "ftp", "cdn", "app", "root", "null", "undefined"
    );

    private ReservedCodes() {
        throw new AssertionError("工具类不应被实例化");
    }

    /**
     * 判断给定短码是否为保留字。
     *
     * @param code 待判断的短码，可为 {@code null}（返回 {@code false}）
     */
    public static boolean isReserved(String code) {
        if (code == null) {
            return false;
        }
        return RESERVED.contains(code.toLowerCase(Locale.ROOT));
    }

    /** 返回全部保留字，供文档生成与测试使用。 */
    public static Set<String> all() {
        return RESERVED;
    }
}
