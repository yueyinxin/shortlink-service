package io.github.yueyinxin.shortlink.user;

import io.github.yueyinxin.shortlink.auth.jwt.AuthenticatedUser;
import io.github.yueyinxin.shortlink.common.response.ApiResponse;
import io.github.yueyinxin.shortlink.user.dto.UserResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 用户接口。
 */
@RestController
@RequestMapping("/api/v1/users")
@Tag(name = "用户", description = "当前用户信息")
public class UserController {

    private final UserService userService;

    public UserController(UserService userService) {
        this.userService = userService;
    }

    /**
     * 获取当前登录用户信息。
     *
     * <p>刻意<b>不</b>从令牌里读取用户名直接返回，而是用令牌中的 {@code userId}
     * 回查数据库。原因是令牌是签发时刻的快照：如果用户在两次登录之间修改了邮箱或用户名，
     * 令牌中的值已经过时。用快照数据做展示会让用户看到"自己改过的信息又变回去了"，
     * 这类问题很难排查，因为它在令牌刷新后又会自行消失。
     *
     * <p>令牌中只保留 {@code userId} 这个权威标识，其余信息一律实时查询 ——
     * 这是避免"用了过期快照"的通用做法。
     */
    @GetMapping("/me")
    @SecurityRequirement(name = "bearerAuth")
    @Operation(summary = "获取当前登录用户信息")
    public ApiResponse<UserResponse> currentUser(@AuthenticationPrincipal AuthenticatedUser principal) {
        User user = userService.getByIdOrThrow(principal.userId());
        return ApiResponse.ok(UserResponse.from(user));
    }
}
