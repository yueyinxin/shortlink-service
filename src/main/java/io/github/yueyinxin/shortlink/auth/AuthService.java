package io.github.yueyinxin.shortlink.auth;

import io.github.yueyinxin.shortlink.auth.dto.LoginRequest;
import io.github.yueyinxin.shortlink.auth.dto.RefreshTokenRequest;
import io.github.yueyinxin.shortlink.auth.dto.RegisterRequest;
import io.github.yueyinxin.shortlink.auth.dto.TokenResponse;
import io.github.yueyinxin.shortlink.auth.jwt.JwtTokenProvider;
import io.github.yueyinxin.shortlink.common.exception.BusinessException;
import io.github.yueyinxin.shortlink.common.exception.ErrorCode;
import io.github.yueyinxin.shortlink.common.exception.FieldViolation;
import io.github.yueyinxin.shortlink.user.User;
import io.github.yueyinxin.shortlink.user.UserService;
import io.github.yueyinxin.shortlink.user.dto.UserResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

/**
 * 认证服务：注册、登录、刷新、登出。
 *
 * <p>本服务是唯一接触密码明文与刷新令牌明文的组件。这两类数据都不得进入日志。
 */
@Service
public class AuthService {

    private static final Logger log = LoggerFactory.getLogger(AuthService.class);

    /** BCrypt 只使用前 72 字节，超过这个长度的密码会在用户不知情的情况下被截断。 */
    private static final int BCRYPT_MAX_PASSWORD_BYTES = 72;

    /**
     * 用于抵御计时攻击的占位哈希。
     *
     * <p>当用户名不存在时，如果直接返回"认证失败"而不做密码比对，响应会明显快于
     * 用户存在的情况（省去了一次约 100ms 的 BCrypt 运算）。攻击者通过测量响应时间
     * 就能判断某个用户名是否已注册 —— 这正是"用户名枚举"。
     *
     * <p>因此无论用户是否存在，都执行一次密码比对：用户不存在时拿这个占位哈希去比，
     * 让两条路径的耗时接近。这样做的代价是每次失败登录都要付一次 BCrypt 的成本，
     * 这个成本本身也是一种保护（暴力破解更慢）。
     *
     * <p>该哈希对应一个随机生成的、无人知晓的密码，因此永远不可能被匹配成功。
     */
    private static final String TIMING_ATTACK_DUMMY_HASH =
            "$2a$10$N9qo8uLOickgx2ZMRZoMyeIjZAgcfl7p92ldGxad68LJZdL17lhWy";

    private final UserService userService;
    private final PasswordEncoder passwordEncoder;
    private final JwtTokenProvider tokenProvider;
    private final RefreshTokenStore refreshTokenStore;

    public AuthService(UserService userService,
                       PasswordEncoder passwordEncoder,
                       JwtTokenProvider tokenProvider,
                       RefreshTokenStore refreshTokenStore) {
        this.userService = userService;
        this.passwordEncoder = passwordEncoder;
        this.tokenProvider = tokenProvider;
        this.refreshTokenStore = refreshTokenStore;
    }

    // ------------------------------------------------------------------ 注册

    /**
     * 注册新用户。
     *
     * <p>返回 {@link UserResponse} 而不是令牌：注册成功后让客户端显式调用登录接口，
     * 可以让"注册"与"获取凭证"两件事彼此独立。若注册直接返回令牌，
     * 任何能触发注册的路径都会同时获得凭证，扩大攻击面。
     */
    @Transactional
    public UserResponse register(RegisterRequest request) {
        validatePasswordForBcrypt(request.password());
        userService.checkUsernameAvailable(request.username());
        userService.checkEmailAvailable(request.email());

        User user = User.register(
                request.username(),
                request.email(),
                passwordEncoder.encode(request.password())
        );

        try {
            // 使用 saveAndFlush 而非 save：save 只是把实体放入持久化上下文，
            // INSERT 可能被推迟到事务提交时才执行 —— 那时已经离开了这个 try 块，
            // 唯一约束冲突将无法在这里被捕获和处理。
            User saved = userService.saveAndFlush(user);
            log.info("用户注册成功: userId={}, username={}", saved.getId(), saved.getUsername());
            return UserResponse.from(saved);
        } catch (DataIntegrityViolationException e) {
            // 走到这里说明前面的可用性检查通过之后，有并发请求抢先插入了相同的
            // 用户名或邮箱。这不是异常情况，而是并发下的正常竞争 ——
            // 唯一性由数据库唯一索引保证，应用层的检查只是为了给出更友好的错误提示。
            throw userService.translateDuplicateViolation(request.username(), request.email(), e);
        }
    }

    // ------------------------------------------------------------------ 登录

    /**
     * 登录并签发令牌对。
     *
     * <p><b>用户不存在与密码错误返回完全相同的错误码和文案</b>（AC-02.2 / AC-02.3）。
     * 如果区分开来，攻击者可以先用任意密码试探，根据错误信息的差异筛出已注册的用户名。
     */
    @Transactional(readOnly = true)
    public TokenResponse login(LoginRequest request) {
        String identifier = request.usernameOrEmail();

        User user = userService.findByUsernameOrEmail(identifier).orElse(null);

        if (user == null) {
            // 用户不存在也要执行一次比对，抹平与"密码错误"之间的计时差异。
            // 详见 TIMING_ATTACK_DUMMY_HASH 的说明。
            passwordEncoder.matches(request.password(), TIMING_ATTACK_DUMMY_HASH);
            log.debug("登录失败：账号不存在");
            throw new BusinessException(ErrorCode.INVALID_CREDENTIALS);
        }

        if (!passwordEncoder.matches(request.password(), user.getPasswordHash())) {
            log.debug("登录失败：密码错误, userId={}", user.getId());
            throw new BusinessException(ErrorCode.INVALID_CREDENTIALS);
        }

        if (!user.canLogin()) {
            // 状态检查放在密码验证之后：如果先检查状态，攻击者无需知道密码
            // 就能通过"账号已被禁用"这个提示确认账号存在
            log.debug("登录失败：账号状态不允许登录, userId={}, status={}", user.getId(), user.getStatus());
            throw new BusinessException(ErrorCode.INVALID_CREDENTIALS);
        }

        log.info("用户登录成功: userId={}", user.getId());
        return issueTokenPair(user);
    }

    // ------------------------------------------------------------------ 刷新

    /**
     * 用刷新令牌换取新的令牌对。
     *
     * <p>刷新令牌<b>一次性使用</b>（AC-03.2）：{@code consume} 是原子操作，
     * 成功后旧令牌立即失效。若客户端重新使用同一个刷新令牌（重放），返回 401。
     */
    @Transactional(readOnly = true)
    public TokenResponse refresh(RefreshTokenRequest request) {
        Long userId = refreshTokenStore.consume(request.refreshToken())
                .orElseThrow(() -> {
                    log.debug("刷新失败：令牌不存在或已被使用");
                    return new BusinessException(ErrorCode.TOKEN_INVALID);
                });

        User user = userService.findById(userId)
                .orElseThrow(() -> {
                    // 用户已被删除但令牌仍在有效期内 —— 数据不一致的一种可能来源
                    log.warn("刷新失败：令牌有效但用户不存在, userId={}", userId);
                    return new BusinessException(ErrorCode.TOKEN_INVALID);
                });

        if (!user.canLogin()) {
            log.debug("刷新失败：账号状态不允许登录, userId={}", userId);
            throw new BusinessException(ErrorCode.TOKEN_INVALID);
        }

        return issueTokenPair(user);
    }

    // ------------------------------------------------------------------ 登出

    /**
     * 登出：作废指定的刷新令牌。
     *
     * <p><b>不存在的令牌也返回成功。</b> 登出的语义是"确保该令牌不再可用"，
     * 而不是"该令牌曾经存在"。若对无效令牌报错，客户端在令牌已过期时登出会收到错误，
     * 而它的目标（让令牌失效）实际上已经达成。
     *
     * <p><b>注意</b>：登出只作废刷新令牌。已签发的 access token 在剩余有效期内
     * （最长 30 分钟）仍然可用 —— 这是无状态 JWT 的固有代价，见 ADR-0005。
     */
    public void logout(String refreshToken) {
        refreshTokenStore.revoke(refreshToken);
        log.debug("已作废刷新令牌");
    }

    // ------------------------------------------------------------------ 内部

    /**
     * 签发一对新令牌。
     *
     * <p>每次签发都生成新的刷新令牌并作废旧的，这样刷新操作总是"前进"的：
     * 令牌泄露后，只要合法客户端先完成一次刷新，攻击者手中的令牌就失效了。
     */
    private TokenResponse issueTokenPair(User user) {
        String accessToken = tokenProvider.createAccessToken(user.getId(), user.getUsername());

        // UUID.randomUUID 使用 SecureRandom 生成，122 位熵，足以作为不可猜测的凭证。
        // 这里不用 JWT：刷新令牌必须支持服务端撤销，而自包含的 JWT 做不到这一点。
        String refreshToken = UUID.randomUUID().toString();
        refreshTokenStore.store(refreshToken, user.getId(), tokenProvider.refreshTokenTtl());

        return TokenResponse.bearer(
                accessToken,
                refreshToken,
                tokenProvider.accessTokenTtl().toSeconds()
        );
    }

    /**
     * 校验密码的 UTF-8 字节长度不超过 BCrypt 的处理上限。
     *
     * <p>{@code @Size(max = 64)} 限制的是<b>字符</b>数。在 UTF-8 下，一个中文字符占 3 字节，
     * 一个 emoji 最多占 4 字节，因此 64 个字符最多可能是 256 字节。
     * 而 BCrypt 只使用前 72 字节 —— 超出部分被静默丢弃。
     *
     * <p>后果是：用户设置了一个 64 位中文密码，其中第 25 个字符之后的内容实际不参与校验，
     * 而这些字符不同的两个密码会被判定为同一个。用户不会收到任何提示。
     * 显式拒绝超长字节数，把这个隐式行为变成明确的错误。
     */
    private static void validatePasswordForBcrypt(String password) {
        int bytes = password.getBytes(StandardCharsets.UTF_8).length;
        if (bytes > BCRYPT_MAX_PASSWORD_BYTES) {
            throw BusinessException.of(ErrorCode.VALIDATION_FAILED, List.of(
                    new FieldViolation("password",
                            "密码过长（UTF-8 编码后 %d 字节，上限 %d 字节）"
                                    .formatted(bytes, BCRYPT_MAX_PASSWORD_BYTES))));
        }
    }
}
