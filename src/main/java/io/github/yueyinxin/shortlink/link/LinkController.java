package io.github.yueyinxin.shortlink.link;

import io.github.yueyinxin.shortlink.auth.jwt.AuthenticatedUser;
import io.github.yueyinxin.shortlink.common.response.ApiResponse;
import io.github.yueyinxin.shortlink.common.response.PageResponse;
import io.github.yueyinxin.shortlink.link.dto.CreateLinkRequest;
import io.github.yueyinxin.shortlink.link.dto.LinkResponse;
import io.github.yueyinxin.shortlink.link.dto.UpdateLinkStatusRequest;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * 短链管理接口。
 *
 * <p>所有方法都需要认证，且<b>每个方法都把当前用户的 {@code userId} 传给服务层</b>。
 * 这不是可选的装饰 —— 服务层的查询以 {@code userId} 作为 SQL 条件的一部分，
 * 因此"忘记传"在类型上就不可能发生（服务层方法的第一个参数就是它）。
 */
@RestController
@RequestMapping("/api/v1/links")
@SecurityRequirement(name = "bearerAuth")
@Tag(name = "短链管理", description = "创建、查询、启停与删除短链")
public class LinkController {

    private final LinkService linkService;

    public LinkController(LinkService linkService) {
        this.linkService = linkService;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "创建短链",
            description = "未指定 customCode 时由系统生成 6 位短码。"
                    + "自定义短码需为 4-10 位字母数字，且不能是系统保留字。")
    public ApiResponse<LinkResponse> create(@AuthenticationPrincipal AuthenticatedUser principal,
                                            @Valid @RequestBody CreateLinkRequest request) {
        return ApiResponse.ok(linkService.create(principal.userId(), request));
    }

    @GetMapping
    @Operation(summary = "分页查询我的短链",
            description = "结果仅包含当前用户的短链，按创建时间倒序。"
                    + "keyword 会对目标地址与短码做模糊匹配（不区分大小写）。"
                    + "size 上限为 100，超出会被截断。")
    public ApiResponse<PageResponse<LinkResponse>> list(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size) {
        return ApiResponse.ok(linkService.list(principal.userId(), keyword, page, size));
    }

    @GetMapping("/{linkId}")
    @Operation(summary = "查询单条短链详情",
            description = "不属于当前用户的短链返回 404 而非 403 —— "
                    + "403 会泄露「该短码存在但不属于你」这一信息。")
    public ApiResponse<LinkResponse> get(@AuthenticationPrincipal AuthenticatedUser principal,
                                        @PathVariable Long linkId) {
        return ApiResponse.ok(linkService.get(principal.userId(), linkId));
    }

    @PatchMapping("/{linkId}/status")
    @Operation(summary = "启用或禁用短链",
            description = "禁用后跳转返回 403。状态变更会立即清理缓存，"
                    + "因此生效是即时的，不需要等待缓存过期。")
    public ApiResponse<LinkResponse> updateStatus(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @PathVariable Long linkId,
            @Valid @RequestBody UpdateLinkStatusRequest request) {
        return ApiResponse.ok(linkService.updateStatus(principal.userId(), linkId, request.enabled()));
    }

    @DeleteMapping("/{linkId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "删除短链",
            description = "物理删除，其统计数据一并清除。删除后该短码可被重新使用。")
    public void delete(@AuthenticationPrincipal AuthenticatedUser principal,
                       @PathVariable Long linkId) {
        linkService.delete(principal.userId(), linkId);
    }
}
