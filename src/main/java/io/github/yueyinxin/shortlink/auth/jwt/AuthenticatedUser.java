package io.github.yueyinxin.shortlink.auth.jwt;

/**
 * 已通过认证的调用者身份。
 *
 * <p>这是 access token 中携带的全部信息。刻意保持最小：只有用户 ID 与用户名。
 *
 * <p><b>为什么不在令牌里放角色或权限</b>：权限一旦签发就无法收回，变更后要等令牌
 * 自然过期才生效 —— 这与"JWT 无法撤销"是同一个问题，只是换了字段。
 * 如果未来引入角色，正确做法是在每次请求时按 {@code userId} 查询权限（带缓存），
 * 而不是信任令牌中的声明。详见 {@code docs/adr/0005-authentication.md}。
 *
 * <p><b>为什么 {@code userId} 而不是整个 {@code User} 对象</b>：
 * 令牌是外部输入，其中的 {@code username} 可能是签发时的旧值（用户改名后）。
 * 需要展示用户当前信息时应查数据库，而不是直接使用这里的数据。
 * 把 ID 作为唯一的权威标识，可以避免"用了过期快照"这类难以察觉的问题。
 *
 * @param userId   用户主键
 * @param username 签发令牌时的用户名
 */
public record AuthenticatedUser(Long userId, String username) {
}
