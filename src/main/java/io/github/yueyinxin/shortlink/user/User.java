package io.github.yueyinxin.shortlink.user;

import io.github.yueyinxin.shortlink.common.persistence.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.util.Objects;

/**
 * 用户实体。
 *
 * <h2>关于 Lombok 的使用</h2>
 * 本类只用 {@code @Getter}，<b>没有</b>使用 {@code @Data} 或 {@code @Setter}。这是刻意的：
 * <ul>
 *   <li>{@code @Data} 会生成覆盖所有字段的 {@code equals}/{@code hashCode}。
 *       对 JPA 实体而言这会引入两个问题：一是实体在持久化前后（id 从 null 变为具体值）
 *       hashCode 会变化，放进 HashSet 后会"消失"；二是懒加载的关联字段被访问会触发
 *       意外的数据库查询。</li>
 *   <li>{@code @Setter} 会让实体的每一个字段都可以被外部随意修改，
 *       业务规则将无处安放。字段的修改应该通过有语义的方法（如 {@code rename}）进行，
 *       而不是 {@code setUsername()}。</li>
 * </ul>
 * 本类的 {@code equals}/{@code hashCode} 基于主键，且对未持久化的实体做了明确处理。
 */
@Entity
@Table(name = "users")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class User extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 32)
    private String username;

    @Column(nullable = false, length = 128)
    private String email;

    /**
     * BCrypt 哈希，固定 60 字符。
     *
     * <p>命名为 {@code passwordHash} 而不是 {@code password} 是有意的：它让
     * 「这里存的是哈希，不是明文」在每一次读写代码时都显而易见，
     * 从而降低误把哈希当明文使用的概率。
     */
    @Column(name = "password_hash", nullable = false, length = 60)
    private String passwordHash;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private UserStatus status;

    /**
     * 创建新用户。
     *
     * <p>使用静态工厂而非公开构造函数的理由：
     * <ol>
     *   <li>调用点读起来是"注册一个用户"，而不是"new 一个 User 并传三个字符串"，意图更清楚；</li>
     *   <li>构造过程可以包含不变量检查（例如密码哈希必须非空），
     *       而这些检查不应该由调用方负责；</li>
     *   <li>新增字段时只需改工厂方法，调用点不受影响。</li>
     * </ol>
     *
     * @param passwordHash 已加密的密码哈希。<b>绝不接受明文</b> —— 参数名已经说明了它的来源，
     *                     传明文进来会直接导致明文入库。
     */
    public static User register(String username, String email, String passwordHash) {
        User user = new User();
        user.username = Objects.requireNonNull(username, "username");
        user.email = Objects.requireNonNull(email, "email");
        user.passwordHash = Objects.requireNonNull(passwordHash, "passwordHash");
        user.status = UserStatus.ACTIVE;
        return user;
    }

    /** 是否可以登录。把状态判断收进领域对象，避免调用方分散地写 {@code status == ACTIVE}。 */
    public boolean canLogin() {
        return status != null && status.allowsLogin();
    }

    /**
     * 主键相等即视为同一实体。
     *
     * <p>未持久化（{@code id == null}）的两个实例一律不相等 —— 它们代表两个不同的新用户，
     * 而不是同一个用户。这里的实现遵循 JPA 的惯例：只有同一持久化上下文中的同一行才是同一对象。
     */
    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof User user)) {
            return false;
        }
        return id != null && id.equals(user.id);
    }

    @Override
    public int hashCode() {
        // 返回类级别的常量而非基于 id 的哈希：
        // 实体在 persist 前后 id 会从 null 变为具体值，若 hashCode 依赖 id，
        // 放入 HashSet 后就会因为哈希值变化而无法被找到。
        return User.class.hashCode();
    }

    @Override
    public String toString() {
        // 不输出 passwordHash 与 email：toString 常被用于日志，
        // 让它带上敏感字段会制造不必要的泄露面。只保留可安全打印的标识信息。
        return "User{id=%d, username='%s', status=%s}".formatted(id, username, status);
    }
}
