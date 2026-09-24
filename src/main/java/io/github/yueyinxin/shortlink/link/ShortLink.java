package io.github.yueyinxin.shortlink.link;

import io.github.yueyinxin.shortlink.common.persistence.BaseEntity;
import io.github.yueyinxin.shortlink.common.util.Base62;
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

import java.time.LocalDateTime;
import java.util.Objects;

/**
 * 短链实体，本项目的核心领域对象。
 *
 * <p>刻意让这个类承载业务规则（访问判定、状态流转），而不是做成一个只有字段和
 * getter/setter 的"贫血模型"。这样做的直接收益是：<b>核心逻辑可以在没有任何
 * Spring 上下文、没有任何数据库的情况下被单元测试覆盖</b>，每个分支的验证成本是毫秒级。
 */
@Entity
@Table(name = "short_link")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ShortLink extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * 短码。
     *
     * <p>列上使用 {@code utf8mb4_bin} 大小写敏感排序规则（见 V2 迁移脚本）。
     * 若用 MySQL 默认的大小写不敏感排序规则，{@code aB3x9K} 与 {@code Ab3X9k}
     * 会被唯一索引判为重复，短码空间会从 62 个字符退化为 36 个。
     */
    @Column(nullable = false, length = 10)
    private String code;

    @Column(name = "original_url", nullable = false, length = 2048)
    private String originalUrl;

    /**
     * 所属用户 ID。
     *
     * <p>存 ID 而不是 {@code @ManyToOne User user} 关联对象，是一个有意的选择：
     * <ul>
     *   <li>跳转链路只需要短链本身，不需要用户信息。用关联对象会让每次加载短链
     *       都面临懒加载的诱惑，而这个场景里一次多余查询就是性能损失。</li>
     *   <li>数据隔离的查询条件 {@code userId} 直接作为标量字段参与 SQL 的 WHERE 子句，
     *       比通过关联路径 {@code user.id} 更直接，也让索引的可用性一目了然。</li>
     * </ul>
     * 代价是丢失了数据库层面的外键导航能力（外键约束仍然存在），
     * 以及无法用 {@code link.getUser().getUsername()} 这样的链式访问。
     * 在本项目中，后者从来不是需求。
     */
    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private LinkStatus status;

    /** 过期时间。{@code null} 表示永不过期。 */
    @Column(name = "expires_at")
    private LocalDateTime expiresAt;

    /**
     * 创建一个尚未分配最终短码的短链。
     *
     * <p><b>为什么先给一个占位短码</b>：短码由数据库自增主键经 Feistel 置换得到
     * （见 ADR-0002），因此必须先 {@code INSERT} 拿到主键，才能计算并回填短码。
     * 而 {@code code} 列是 {@code NOT NULL} + 唯一索引，插入时不能为空 ——
     * 占位值就是用来满足这两个约束的。
     *
     * <p>占位值以 {@code ~} 开头，该字符不在 Base62 字母表中，因此
     * <b>任何真实短码都不可能与之冲突</b>；{@link #isCodeAssigned()} 也据此判断。
     * 占位值只在同一个事务内短暂存在，提交前必定已被真实短码替换。
     *
     * @param userId      所属用户
     * @param originalUrl 目标地址，调用前必须已通过格式与协议白名单校验
     * @param expiresAt   过期时间，{@code null} 表示永不过期
     */
    public static ShortLink create(Long userId, String originalUrl, LocalDateTime expiresAt) {
        ShortLink link = new ShortLink();
        link.userId = Objects.requireNonNull(userId, "userId");
        link.originalUrl = Objects.requireNonNull(originalUrl, "originalUrl");
        link.expiresAt = expiresAt;
        link.status = LinkStatus.ACTIVE;
        link.code = placeholderCode();
        return link;
    }

    /**
     * 分配最终短码。
     *
     * <p>只允许从未分配状态调用一次。重复调用意味着生成逻辑被走了两遍，
     * 那是一个缺陷，应当立即失败而不是静默覆盖 —— 静默覆盖会让"短码与主键一一对应"
     * 这个不变量失效，而该不变量正是"不同短链必然不同短码"的保证来源。
     *
     * <p>自定义短码与自动生成的短码都通过本方法写入，因此"只能赋值一次"这条约束
     * 对两条创建路径同样生效。
     *
     * @throws IllegalStateException 短码已被分配过
     */
    public void assignCode(ShortCode shortCode) {
        Objects.requireNonNull(shortCode, "shortCode");
        if (isCodeAssigned()) {
            throw new IllegalStateException("短码已分配，不能重复分配: " + this.code);
        }
        this.code = shortCode.value();
    }

    /**
     * 判断短码是否已分配。
     *
     * <p>占位短码含 {@code ~} 字符而真实短码只含字母数字，据此区分。
     */
    public boolean isCodeAssigned() {
        return code != null && !code.startsWith(PLACEHOLDER_PREFIX);
    }

    /**
     * 判定本次访问应当如何处理。
     *
     * <p>实现委托给 {@link LinkSnapshot}，而不是在本类里再写一遍判断。
     * 跳转链路有两条路径（命中缓存 / 回查数据库），它们必须给出完全一致的判定结果。
     * 如果这里和 {@code LinkSnapshot} 各写一份，两处的边界条件（{@code before} 还是
     * {@code !isAfter}）、优先级（禁用与过期同时成立时的取舍）迟早会出现分歧，
     * 表现为"缓存未命中时行为正常、命中时返回错误状态"这类难以复现的问题。
     *
     * <p>判定规则本身见 {@link LinkSnapshot#accessState(LocalDateTime)}。
     */
    public LinkAccessState accessState(LocalDateTime now) {
        return toSnapshot().accessState(now);
    }

    /** 转换为跳转链路使用的轻量快照。 */
    public LinkSnapshot toSnapshot() {
        return LinkSnapshot.from(this);
    }

    // ------------------------------------------------------------ 状态流转

    /**
     * 禁用。幂等：对已禁用的链接再次调用不产生任何变化。
     *
     * <p>幂等是有意设计的。如果不做幂等检查，重复调用虽然在数据上无害，
     * 但会刷新 {@code updated_at}，让"最后修改时间"失去意义 ——
     * 运维想通过它判断链接何时被改动时会得到错误结论。
     */
    public void disable() {
        this.status = LinkStatus.DISABLED;
    }

    /** 启用。幂等，理由同 {@link #disable()}。 */
    public void enable() {
        this.status = LinkStatus.ACTIVE;
    }

    /**
     * 修改过期时间。
     *
     * @param expiresAt 新的过期时间，{@code null} 表示永不过期
     */
    public void changeExpiry(LocalDateTime expiresAt) {
        this.expiresAt = expiresAt;
    }

    /**
     * 判断归属。所有涉及单个短链的操作都必须先通过这一检查。
     *
     * <p><b>注意：这只是防御性的第二道关卡。</b> 真正的隔离应当体现在查询条件里
     * （{@code WHERE id = ? AND user_id = ?}），让不具备权限的记录根本查不出来。
     * 依赖"先查出来再判断归属"是危险的：只要有一处忘记判断，就产生越权漏洞。
     * 保留这个方法是为了在代码中显式表达归属概念，不作为唯一的防线。
     */
    public boolean isOwnedBy(Long candidateUserId) {
        return candidateUserId != null && candidateUserId.equals(userId);
    }

    // ------------------------------------------------------------ 内部

    /**
     * 占位短码前缀。
     *
     * <p>使用 {@code ~} —— 该字符不在 Base62 字母表（{@code 0-9A-Za-z}）中，
     * 因此任何真实短码都不可能包含它，占位值与真实值永远不会混淆。
     * 若数据库中出现了以 {@code ~} 开头的短码，一眼就能判断这是事务失败后的残留。
     */
    static final String PLACEHOLDER_PREFIX = "~";

    /**
     * 占位短码中随机部分的长度。
     *
     * <p>取 9 使占位值总长恰好 10 位，与 {@code code} 列的宽度（{@code VARCHAR(10)}）一致。
     * 初版实现曾用 {@code "~pending~" + 序列号}，长度随序列号增长且很快超过列宽，
     * 导致插入直接失败 —— 这类"看起来能跑"的实现问题只能在真正执行时暴露，
     * 因此集成测试里必须有创建短链的实际路径。
     */
    private static final int PLACEHOLDER_RANDOM_LENGTH = 9;

    /**
     * 生成占位短码。
     *
     * <p>占位值只需满足两点：符合列宽约束，且不被任何其他行占用（唯一索引会校验）。
     * 随机 9 位 Base62 的取值空间为 {@code 62^9 ≈ 1.35e16}，碰撞概率可忽略；
     * 即便极端情况下碰撞，唯一索引会拒绝这次插入，而不会产生错误数据。
     */
    public static String placeholderCode() {
        return PLACEHOLDER_PREFIX + Base62.randomString(PLACEHOLDER_RANDOM_LENGTH);
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof ShortLink link)) {
            return false;
        }
        return id != null && id.equals(link.id);
    }

    @Override
    public int hashCode() {
        return ShortLink.class.hashCode();
    }

    @Override
    public String toString() {
        return "ShortLink{id=%d, code='%s', status=%s, expiresAt=%s}"
                .formatted(id, code, status, expiresAt);
    }
}
