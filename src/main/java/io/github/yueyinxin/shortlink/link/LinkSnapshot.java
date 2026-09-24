package io.github.yueyinxin.shortlink.link;

import java.time.LocalDateTime;
import java.util.Objects;

/**
 * 短链快照。
 *
 * <p>这个类型承担两个职责，而它们共享同一个动机：<b>跳转链路只需要短链的一小部分字段，
 * 不应该被迫加载整个实体。</b>
 *
 * <ol>
 *   <li><b>缓存载荷</b> —— 序列化后存入 Redis。相比缓存实体，快照只含跳转判定所需的
 *       字段，体积小且结构稳定（实体字段会随开发变动，直接缓存序列化的实体会在
 *       字段增删后读到无法反序列化的旧数据）。</li>
 *   <li><b>领域判定的输入</b> —— 缓存里取出的不是实体，但判定逻辑必须与查库时完全一致。
 *       把判定放在快照上，两条路径共用同一份实现，不可能出现"走缓存和走数据库
 *       判定结果不同"的不一致。</li>
 * </ol>
 *
 * <p><b>为什么不用实体本身</b>：从缓存反序列化出一个 JPA 实体是危险的 ——
 * 它看起来像受管理的对象，实际脱离持久化上下文。对它调用任何加载方法都可能抛出
 * {@code LazyInitializationException}，而把它 {@code merge} 回去则可能意外覆盖数据库数据。
 * 用一个明确的、不含持久化语义的值类型，可以从根本上排除这类误用。
 *
 * @param id          短链主键，统计写入时需要
 * @param code        短码
 * @param originalUrl 目标地址
 * @param status      状态
 * @param expiresAt   过期时间，{@code null} 表示永不过期
 */
public record LinkSnapshot(
        Long id,
        String code,
        String originalUrl,
        LinkStatus status,
        LocalDateTime expiresAt
) {

    /**
     * 判定本次访问应当如何处理。
     *
     * <p>判定顺序：先看禁用，再看过期。两者可能同时成立，让"禁用"优先是因为它是人的显式操作，
     * 意图比时间流逝更明确；反过来会让创建者看到"已过期"，误以为自己没禁用它。
     *
     * <p>时间比较用 {@code !isAfter(now)} 让过期时刻本身也算过期（边界闭合），
     * 避免在 {@code expiresAt} 那一微秒上出现"时而过期时而不"的不确定行为。
     */
    public LinkAccessState accessState(LocalDateTime now) {
        Objects.requireNonNull(now, "now");
        if (status == LinkStatus.DISABLED) {
            return LinkAccessState.DISABLED;
        }
        if (expiresAt != null && !expiresAt.isAfter(now)) {
            return LinkAccessState.EXPIRED;
        }
        return LinkAccessState.ACCESSIBLE;
    }

    public static LinkSnapshot from(ShortLink link) {
        return new LinkSnapshot(
                link.getId(),
                link.getCode(),
                link.getOriginalUrl(),
                link.getStatus(),
                link.getExpiresAt()
        );
    }
}
