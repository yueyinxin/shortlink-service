package io.github.yueyinxin.shortlink.link;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * 短链仓储。
 *
 * <h2>设计要点</h2>
 *
 * <p><b>1. 归属校验下沉到查询条件。</b> 所有面向单个短链的操作都提供
 * {@code findByIdAndUserId} 这类"带 userId 条件"的方法，而不是先 {@code findById}
 * 再在代码里判断归属。前者的越权写不出来，后者只要有一处忘记判断就是漏洞。
 *
 * <p><b>2. 跳转查询不带 userId 条件。</b> {@link #findByCode(String)} 是唯一一个
 * 不按用户隔离的查询 —— 因为跳转的访客是匿名的，短链的归属与访问权限无关。
 * 这个例外是刻意的，因此单独列出并注明。
 */
@Repository
public interface ShortLinkRepository extends JpaRepository<ShortLink, Long> {

    /**
     * 按短码查找（跳转链路使用）。
     *
     * <p><b>这是全项目唯一不按用户隔离的查询。</b> 短链的可见性由其"是否启用/是否过期"
     * 决定，与创建者无关 —— 任何拿到短码的人都可以跳转，这正是短链的用途。
     *
     * <p>{@code code} 列是大小写敏感的（{@code utf8mb4_bin}），因此这里的等值比较
     * 区分大小写，与短码空间的语义一致。
     */
    Optional<ShortLink> findByCode(String code);

    /** 按 ID 查找并校验归属。查不到（含不属于该用户）一律返回空，由上层转为 404。 */
    Optional<ShortLink> findByIdAndUserId(Long id, Long userId);

    boolean existsByCode(String code);

    /** 批量按短码查找，供统计刷库时把 code 批量解析为 id 使用。 */
    List<ShortLink> findByCodeIn(Collection<String> codes);

    /**
     * 分页查询某用户的短链，支持按短码或目标地址模糊搜索。
     *
     * <p><b>关于 {@code pattern}</b>：调用方传入已转义并加好通配符的模式串。
     * 当不需要关键字过滤时传 {@code "%"}（匹配全部），而不是传 {@code null} ——
     * JPQL 中 {@code :param IS NULL} 与类型推断的交互容易产生意外行为，
     * 而 {@code LIKE '%%'} 的语义明确且不含分支。
     *
     * <p><b>关于 {@code ESCAPE '!'}</b>：如果用户搜索关键字里带 {@code %} 或 {@code _}，
     * 它们会被 SQL 当作通配符，导致搜索 {@code 100%} 变成"匹配任意以 100 开头的内容"。
     * 用 {@code !} 作为转义符（而不是反斜杠）是因为反斜杠在 Java 字符串与 JPQL
     * 字符串字面量中都要再转义一层，容易写错。转义本身在 {@code LinkService} 中完成。
     *
     * <p><b>性能</b>：{@code LIKE '%kw%'} 无法使用索引，会在该用户的记录集合内做扫描。
     * 查询必定带 {@code userId} 条件，因此扫描范围限于单个用户的数据，实际影响可控。
     * 触发条件与后续方案见 {@code docs/03-database-design.md §3.4}。
     *
     * <p><b>排序</b>：排序由调用方通过 {@link Pageable} 传入
     * （{@code createdAt DESC, id DESC}）。把排序放在分页参数里而不是写死在查询中，
     * 是为了让"排序字段必须与索引匹配"这件事在调用点可见。
     */
    @Query("""
            SELECT l FROM ShortLink l
            WHERE l.userId = :userId
              AND (LOWER(l.originalUrl) LIKE :pattern ESCAPE '!'
                   OR LOWER(l.code) LIKE :pattern ESCAPE '!')
            """)
    Page<ShortLink> searchByUser(@Param("userId") Long userId,
                                 @Param("pattern") String pattern,
                                 Pageable pageable);

    /** 统计某用户的短链总数。用于配额校验（当前版本未启用配额，预留给后续）。 */
    long countByUserId(Long userId);
}
