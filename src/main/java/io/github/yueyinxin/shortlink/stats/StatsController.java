package io.github.yueyinxin.shortlink.stats;

import io.github.yueyinxin.shortlink.auth.jwt.AuthenticatedUser;
import io.github.yueyinxin.shortlink.common.response.ApiResponse;
import io.github.yueyinxin.shortlink.stats.dto.StatsResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 访问统计接口。
 *
 * <p>路径嵌套在 {@code /api/v1/links/{linkId}} 之下，表达"统计从属于某个短链"
 * 这一领域关系。相比扁平的 {@code /api/v1/stats?linkId=...}，嵌套路径让
 * 归属校验的位置更明确 —— 必须先定位到那条短链，才谈得上它的统计。
 */
@RestController
@RequestMapping("/api/v1/links/{linkId}/stats")
@SecurityRequirement(name = "bearerAuth")
@Tag(name = "访问统计", description = "短链的 PV / UV 统计")
public class StatsController {

    private final StatsService statsService;

    public StatsController(StatsService statsService) {
        this.statsService = statsService;
    }

    @GetMapping
    @Operation(summary = "查询短链的按天统计",
            description = """
                    返回区间内每天的 PV 与 UV，**包含访问量为 0 的日期**（补零），
                    便于前端直接绘制折线图而不需要自行补齐缺失日期。

                    UV 是 HyperLogLog 的估算值，标准误差约 0.81%，不是精确去重计数。

                    数据可能滞后最多一个刷库周期（默认 60 秒），但查询时会合并 Redis 中
                    尚未落库的增量，因此返回的数值始终是最新的。

                    响应中**不含**区间总 UV：区间独立访客数不等于每日 UV 之和
                    （同一访客跨天访问会被重复计数），提供错误的数值比不提供更糟。
                    """)
    public ApiResponse<StatsResponse> stats(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @PathVariable Long linkId,
            @RequestParam(required = false) Integer days) {
        return ApiResponse.ok(statsService.getStats(principal.userId(), linkId, days));
    }
}
