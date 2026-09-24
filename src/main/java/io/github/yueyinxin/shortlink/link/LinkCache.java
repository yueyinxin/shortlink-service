package io.github.yueyinxin.shortlink.link;

/**
 * 短链缓存。
 *
 * <p>定义成接口，让 {@code RedirectService} 的单元测试可以注入内存实现，
 * 从而在<b>不启动 Redis 的情况下</b>覆盖跳转的全部路径：命中、未命中、空值缓存、
 * 以及缓存故障时的降级。这些正是最容易出错的路径。
 */
public interface LinkCache {

    /**
     * 查询缓存。
     *
     * <p>返回三态结果而不是 {@code Optional<LinkSnapshot>}，是因为"未命中"与
     * "已知不存在"必须区分开：
     * <ul>
     *   <li>把它俩合并成 {@code Optional.empty()}，就无法实现空值缓存 ——
     *       每次查询一个不存在的短码都会打到数据库，缓存穿透防护失效；</li>
     *   <li>反过来把所有空结果都当成"已知不存在"，则正常的缓存未命中会被误判为 404，
     *       短链明明存在却打不开。</li>
     * </ul>
     * 用三态类型把区分表达在<b>类型系统</b>里，而不是靠文档约定或调用方的记忆。
     */
    Lookup get(String code);

    /**
     * 写入缓存。
     *
     * <p>实现方应在此处施加 TTL 抖动（见 {@code docs/adr/0003-cache-strategy.md}）。
     */
    void put(LinkSnapshot snapshot);

    /**
     * 缓存"该短码不存在"的标记，用于防止缓存穿透。
     *
     * <p>TTL 应显著短于正常缓存：过长的空值缓存会让"刚创建就首次被访问"的短链误报 404。
     */
    void putAbsent(String code);

    /**
     * 删除缓存。
     *
     * <p>更新或删除短链时调用（Cache-Aside 的失效动作）。
     * 应当<b>幂等</b>，且对不存在的键不报错。
     */
    void evict(String code);

    /**
     * 缓存查询结果。
     */
    sealed interface Lookup {

        /** 命中：缓存中有该短链的快照。 */
        record Hit(LinkSnapshot snapshot) implements Lookup {
        }

        /**
         * 已知不存在：缓存中记录了"该短码不存在"的标记。
         *
         * <p>调用方应当直接返回 404，<b>不查询数据库</b> —— 这正是空值缓存的目的。
         */
        record Absent() implements Lookup {
        }

        /**
         * 未命中：缓存中没有相关信息（可能是第一次访问，也可能已过期）。
         *
         * <p>调用方应当回查数据库，并把结果写回缓存。
         */
        record Miss() implements Lookup {
        }
    }
}
