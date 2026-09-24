package io.github.yueyinxin.shortlink.auth;

import java.time.Duration;
import java.util.Optional;

/**
 * 刷新令牌存储。
 *
 * <p>定义成接口而非直接用 Redis 实现，是为了让 {@code AuthService} 的单元测试
 * 可以注入一个内存实现，不需要启动 Redis。这是整个认证模块里唯一需要外部存储的部分，
 * 把它隔离出来之后，登录/刷新/登出的<b>全部业务分支都可以用毫秒级的单元测试覆盖</b>。
 *
 * <p>关于为什么刷新令牌需要服务端存储、而访问令牌不需要，见
 * {@code docs/adr/0005-authentication.md}。
 */
public interface RefreshTokenStore {

    /**
     * 保存刷新令牌与所属用户的对应关系。
     *
     * @param token  刷新令牌明文。实现方<b>不应</b>直接用它作为存储键（见实现说明）
     * @param userId 所属用户
     * @param ttl    有效期
     */
    void store(String token, Long userId, Duration ttl);

    /**
     * 原子地取出并作废刷新令牌（一次性使用）。
     *
     * <p><b>必须是原子操作。</b> "先查询再删除"的两步实现在并发下存在竞态窗口：
     * 两个请求可能都查询到令牌有效，然后都删除并各自签发新的令牌 ——
     * 同一个刷新令牌被使用了两次，正是重放攻击要防的场景。
     *
     * @param token 刷新令牌明文
     * @return 令牌有效时返回所属用户 ID；令牌不存在或已被使用时返回空
     */
    Optional<Long> consume(String token);

    /**
     * 主动作废刷新令牌（登出）。
     *
     * <p>幂等：对不存在或已作废的令牌调用不报错。登出应当是"确保已失效"，
     * 而不是"如果有效才失效"。
     */
    void revoke(String token);
}
