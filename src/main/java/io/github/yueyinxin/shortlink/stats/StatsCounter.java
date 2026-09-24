package io.github.yueyinxin.shortlink.stats;

import java.time.LocalDate;

/**
 * 访问统计计数器。
 *
 * <p>定义成接口，让 {@code RedirectService} 与 {@code StatsService} 的单元测试
 * 可以注入内存实现，从而在不启动 Redis 的情况下覆盖跳转与查询的路径。
 *
 * <h2>PV 与 UV 采用不同的语义，这不是偶然的</h2>
 *
 * <table border="1">
 *   <caption>两种计数语义</caption>
 *   <tr><th></th><th>PV</th><th>UV</th></tr>
 *   <tr><td>语义</td><td><b>增量</b>（取出并清零）</td><td><b>绝对值</b>（当日累计）</td></tr>
 *   <tr><td>Redis 中的形态</td><td>计数器，刷库后删除</td><td>HyperLogLog，当日全程保留</td></tr>
 *   <tr><td>写入数据库的方式</td><td>{@code pv = pv + 增量}</td><td>{@code uv = GREATEST(uv, 新值)}</td></tr>
 *   <tr><td>重复刷库的影响</td><td>由原子的"取出并清零"保证不重复</td><td>取 max 天然幂等，无影响</td></tr>
 * </table>
 *
 * <p><b>为什么 UV 不能用增量语义</b>：HyperLogLog 无法做差集（无法"减去"已计入的元素），
 * 因此若每次刷库都取出估值并累加，同一天内跨多次刷库出现的重复 IP 会被<b>重复计数</b>，
 * 导致 UV 虚高。举例：同一个访客在 10:00 和 11:00 各访问一次，两次刷库各得 UV=1，
 * 累加后当天 UV=2 —— 而正确答案是 1。
 *
 * <p>正确的做法是让 HLL 在当天全程保留，每次刷库读取它的<b>当前完整估值</b>并写入
 * （取 max 保证不会被更早的较小值覆盖）。日期结束后再由刷库任务删除该 HLL。
 * 由于 HLL 始终代表"当天至今的全部独立访客"，重复写入同一个值是无害的。
 *
 * <h2>一致性约定</h2>
 * 所有实现只保证<b>最终一致</b>：记录写入后不会立即出现在数据库里，
 * 而是在刷库周期（默认 60 秒）之后落库。查询侧通过合并未落库的增量对外表现一致
 * （见 {@code StatsService}）。
 */
public interface StatsCounter {

    /**
     * 记录一次访问。
     *
     * <p>实现方<b>不得</b>抛出异常：调用点在跳转的关键路径上，统计失败不应导致跳转失败。
     * 用户在点开链接时期望的是打开网页，而不是看到"统计服务不可用"。
     *
     * @param code     短码
     * @param clientIp 客户端 IP，用于 UV 去重。调用方应保证非空
     * @param date     统计归属日期。显式传入而不是在实现内部取当前时间，
     *                 让跨天边界的处理可测试 —— 测试可以传入任意日期
     */
    void recordAccess(String code, String clientIp, LocalDate date);

    /**
     * 读取当前 PV 增量，<b>不修改</b>任何键。供查询侧合并未落库数据使用。
     */
    long readPv(String code, LocalDate date);

    /**
     * 原子地取出并清零 PV 增量。供刷库任务使用。
     *
     * <p><b>必须是原子操作。</b> 若实现为"先读、再删"两步，两次刷库之间可能读到相同的
     * 计数并重复累加，导致 PV 被重复计算。这类 bug 只在特定时序下出现，
     * 常规测试难以覆盖，且发现时数据已经错了。
     */
    long drainPv(String code, LocalDate date);

    /**
     * 读取某日至今的 UV（绝对值语义）。
     *
     * <p>返回 HyperLogLog 的估算值，存在约 0.81% 的标准误差。
     * 该 HLL 在当日全程保留，因此这个值始终代表"当天至今的全部独立访客"。
     *
     * @return 估算的独立访客数；无数据时返回 0
     */
    long readUv(String code, LocalDate date);

    /**
     * 删除某日的所有计数键。
     *
     * <p>由刷库任务在该日期<b>结束后</b>调用。删除 UV 的 HLL 是必要的：
     * 它的作用已经通过刷库写入数据库而完成，继续保留只会占用内存。
     */
    void clearDay(String code, LocalDate date);
}
