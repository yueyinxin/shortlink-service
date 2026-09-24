package io.github.yueyinxin.shortlink.link;

import io.github.yueyinxin.shortlink.common.util.ClientIpResolver;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;

/**
 * 短链跳转接口 —— 服务的主入口，承载 99% 以上的流量。
 *
 * <p>映射在根路径 {@code /{code}} 上，因此需要格外注意与既有路由的冲突：
 * 所有会被占用的路径名都在 {@link ReservedCodes} 中列为保留字，
 * 禁止用户用作自定义短码。
 *
 * <h2>为什么用 302 而不是 301</h2>
 * {@code 301 Moved Permanently} 会被浏览器<b>长期缓存</b>。一旦客户端缓存了跳转关系，
 * 后续访问不再经过本服务，会带来两个后果：
 * <ul>
 *   <li><b>统计失效</b> —— 缓存命中的跳转不会产生任何请求，PV/UV 严重低估；</li>
 *   <li><b>无法撤改</b> —— 创建者禁用或删除了短链，已缓存 301 的客户端仍然会跳转，
 *       且我们无法远程清除缓存。</li>
 * </ul>
 * {@code 302 Found} 表示临时跳转，客户端每次都会回来询问，这正是短链服务需要的行为。
 * 代价是每次跳转都有一次服务端往返，这个代价是必要的。
 */
@RestController
@Tag(name = "跳转", description = "短链跳转（匿名访问）")
public class RedirectController {

    private final RedirectService redirectService;
    private final ClientIpResolver clientIpResolver;

    public RedirectController(RedirectService redirectService,
                              ClientIpResolver clientIpResolver) {
        this.redirectService = redirectService;
        this.clientIpResolver = clientIpResolver;
    }

    /**
     * 跳转到目标地址。
     *
     * <p>客户端 IP 在控制器层解析后传给服务层，而不是让服务层直接依赖
     * {@code HttpServletRequest}。这样服务层保持与 Web 技术无关，
     * 它的单元测试不需要构造请求对象。
     *
     * <p>响应体为空：跳转只需要状态码与 {@code Location} 头，
     * 返回一个 JSON 体对浏览器没有意义，还会浪费带宽 ——
     * 而这是全站流量最大的接口，任何字节都值得省。
     */
    @GetMapping("/{code}")
    @Operation(summary = "短链跳转",
            description = "成功返回 302 与 Location 头。短码不存在返回 404，"
                    + "已过期返回 410，已禁用返回 403。")
    public ResponseEntity<Void> redirect(@PathVariable String code, HttpServletRequest request) {
        String clientIp = clientIpResolver.resolve(request);
        String targetUrl = redirectService.resolveAndRecord(code, clientIp);

        return ResponseEntity.status(HttpStatus.FOUND)
                .location(URI.create(targetUrl))
                .build();
    }
}
