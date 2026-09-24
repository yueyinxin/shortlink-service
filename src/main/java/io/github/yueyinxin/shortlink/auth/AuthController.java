package io.github.yueyinxin.shortlink.auth;

import io.github.yueyinxin.shortlink.auth.dto.LoginRequest;
import io.github.yueyinxin.shortlink.auth.dto.RefreshTokenRequest;
import io.github.yueyinxin.shortlink.auth.dto.RegisterRequest;
import io.github.yueyinxin.shortlink.auth.dto.TokenResponse;
import io.github.yueyinxin.shortlink.common.response.ApiResponse;
import io.github.yueyinxin.shortlink.user.dto.UserResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * 认证接口。
 *
 * <p>控制器只做三件事：协议适配、参数校验、调用服务。业务逻辑一律不写在这里 ——
 * 控制器是最难做单元测试的一层（需要构造 HTTP 请求上下文），
 * 把逻辑放进来会显著抬高测试成本，进而导致这一层被跳过测试。
 */
@RestController
@RequestMapping("/api/v1/auth")
@Tag(name = "认证", description = "注册、登录、令牌刷新与登出")
public class AuthController {

    private final AuthService authService;

    public AuthController(AuthService authService) {
        this.authService = authService;
    }

    @PostMapping("/register")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "注册新用户",
            description = "注册成功后需要单独调用登录接口获取令牌。注册接口不直接返回令牌，"
                    + "使「创建账号」与「获取凭证」两件事彼此独立。")
    public ApiResponse<UserResponse> register(@Valid @RequestBody RegisterRequest request) {
        return ApiResponse.ok(authService.register(request));
    }

    @PostMapping("/login")
    @Operation(summary = "登录并获取令牌对",
            description = "usernameOrEmail 可传用户名或邮箱。用户不存在与密码错误返回相同的错误码，"
                    + "防止用户名枚举。")
    public ApiResponse<TokenResponse> login(@Valid @RequestBody LoginRequest request) {
        return ApiResponse.ok(authService.login(request));
    }

    @PostMapping("/refresh")
    @Operation(summary = "用刷新令牌换取新的令牌对",
            description = "刷新令牌一次性使用：刷新成功后旧令牌立即失效，"
                    + "重复使用同一个令牌会返回 401。")
    public ApiResponse<TokenResponse> refresh(@Valid @RequestBody RefreshTokenRequest request) {
        return ApiResponse.ok(authService.refresh(request));
    }

    @PostMapping("/logout")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "登出",
            description = "作废请求体中携带的刷新令牌。幂等：对已失效的令牌调用也返回成功，"
                    + "因为登出的目标是「确保该令牌不可用」而非「该令牌曾经有效」。"
                    + "注意已签发的 access token 在剩余有效期内仍然可用。")
    public void logout(@Valid @RequestBody RefreshTokenRequest request) {
        authService.logout(request.refreshToken());
    }
}
