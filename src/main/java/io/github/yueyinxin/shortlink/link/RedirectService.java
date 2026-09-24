package io.github.yueyinxin.shortlink.link;

import io.github.yueyinxin.shortlink.common.exception.BusinessException;
import io.github.yueyinxin.shortlink.common.exception.ErrorCode;
import io.github.yueyinxin.shortlink.stats.StatsCounter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

/**
 * 短链跳转服务 —— 全系统的核心热路径，承载 99% 以上的流量。
 *
 * <h2>设计目标：把这条路径上的每一步都压到最低成本</h2>
 * <ol>
 *   <li><b>不开启数据库事务。</b> 本类的方法没有 {@code @Transactional}。跳转是单条
 *       只读查询，事务的开启/提交本身就有开销，且会占用连接池资源。
 *       仓库方法自带的只读事务已经足够。</li>
 *   <li><b>命中缓存时零数据库访问。</b> 缓存里存的是包含状态与过期时间的完整快照，
 *       因此状态判定不需要额外的查询（若只缓存 URL 字符串，就必须回查数据库才能
 *       判断是否过期 —— 缓存就失去了意义）。</li>
 *   <li><b>不写数据库。</b> 访问统计只写 Redis，不触碰 MySQL。
 *       这使得跳转的响应时间与统计量无关。</li>
 *   <li><b>缓存故障时降级而非失败。</b> Redis 不可用时退化为直查数据库，
 *       跳转仍然可用，只是变慢（AC-05.6）。</li>
 * </ol>
 *
 * <h2>为什么统计放在返回结果之前</h2>
 * 统计写入（一次 Redis pipeline）在跳转响应之前完成，因此它<b>在关键路径上</b>，
 * 但这与"统计必须异步化"（AC-05.5）并不矛盾：该要求的实质是"跳转响应时间不包含
 * <b>数据库写</b>的耗时"。Redis 写入是内存操作，耗时约 1ms，相对于 50ms 的 P99 目标
 * 可以忽略；而若为了彻底移出关键路径改为内存缓冲，则会在进程重启时丢失统计，
 * 得不偿失。
 */
@Service
public class RedirectService {

    private static final Logger log = LoggerFactory.getLogger(RedirectService.class);

    private final ShortLinkRepository linkRepository;
    private final LinkCache linkCache;
    private final StatsCounter statsCounter;

    public RedirectService(ShortLinkRepository linkRepository,
                           LinkCache linkCache,
                           StatsCounter statsCounter) {
        this.linkRepository = linkRepository;
        this.linkCache = linkCache;
        this.statsCounter = statsCounter;
    }

    /**
     * 解析短码并记录访问。
     *
     * @param code     短码
     * @param clientIp 客户端 IP，用于 UV 统计
     * @return 目标地址
     * @throws BusinessException 短码不存在（404）、已过期（410）或已禁用（403）
     */
    public String resolveAndRecord(String code, String clientIp) {
        LocalDateTime now = LocalDateTime.now();

        LinkSnapshot snapshot = load(code);
        if (snapshot == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "短链不存在");
        }

        LinkAccessState state = snapshot.accessState(now);
        if (!state.isAccessible()) {
            // 不可访问时不记录访问统计。理由是这些请求并不代表一次有效的投放效果 ——
            // 把"用户点了但打不开"的次数计入 PV，会让数据误导投放决策。
            // 若将来需要统计这类"无效访问"，应当单独计数而不是混入 PV。
            log.debug("短链不可访问: code={}, state={}", code, state);
            throw new BusinessException(state.errorCode(), state.reason());
        }

        statsCounter.recordAccess(code, clientIp, now.toLocalDate());

        String target = snapshot.originalUrl();
        assertRedirectable(target, code);
        return target;
    }

    /**
     * 纵深防御：确认目标地址可以被安全地放入 {@code Location} 响应头。
     *
     * <p>创建时已经通过 {@link io.github.yueyinxin.shortlink.common.validation.HttpUrl}
     * 做了协议白名单校验，为什么这里还要再检查一次？
     *
     * <p>因为<b>校验发生在数据入库之前，而这个检查发生在数据出库之后</b>，
     * 中间存在多个校验无法覆盖的路径：历史数据（在本校验规则引入之前创建的记录）、
     * 人工直接修改数据库、数据迁移脚本的错误、以及其他服务写入同一张表。
     * 这些路径产生的数据都会绕过创建时的校验。
     *
     * <p>而这一步的代价极低（一次 {@code startsWith} 前缀比较，纳秒级），
     * 收益是<b>即使数据库被污染，也不会把 {@code javascript:} 这样的地址写进
     * Location 头</b>。Location 头中的危险协议会被浏览器直接执行，
     * 且发生在本服务的域名下 —— 这是最严重的一类漏洞。
     *
     * <p>检查失败时返回 404 而不是 500：这属于数据问题而非服务故障，
     * 对外表现为"该短链不可用"是恰当的，同时记录 ERROR 日志以便排查数据来源。
     */
    private static void assertRedirectable(String target, String code) {
        boolean safe = target != null
                && (target.startsWith("http://") || target.startsWith("https://"));

        if (!safe) {
            log.error("拒绝跳转：数据库中的目标地址协议不在白名单内，数据可能被污染。code={}", code);
            throw new BusinessException(ErrorCode.NOT_FOUND, "短链不存在");
        }
    }

    /**
     * 按 Cache-Aside 模式加载短链。
     *
     * <p>这里用 {@code instanceof} 模式匹配而不是 {@code switch} 的模式匹配语法：
     * 后者在 Java 17 中仍是<b>预览功能</b>，需要 {@code --enable-preview} 才能编译，
     * 而开启预览特性会让字节码绑定到特定的 JDK 版本，无法在更新的 JDK 上运行。
     * 对于需要长期维护的服务，不应该依赖预览特性。
     *
     * <p>三个分支的处理差异正是空值缓存的价值所在：{@code Absent} 直接返回空而
     * <b>不查数据库</b>，把"不存在的短码"这类扫描流量挡在 Redis 层。
     *
     * @return 短链快照；不存在时返回 {@code null}
     */
    private LinkSnapshot load(String code) {
        LinkCache.Lookup lookup = linkCache.get(code);

        // 命中：直接返回，零数据库访问
        if (lookup instanceof LinkCache.Lookup.Hit hit) {
            return hit.snapshot();
        }

        // 空值缓存命中：已知不存在，直接返回空而不查库。
        // 这一步是防缓存穿透的关键 —— 攻击者用大量不存在的短码扫描时，
        // 请求会被挡在 Redis 层，不会触及数据库。
        if (lookup instanceof LinkCache.Lookup.Absent) {
            return null;
        }

        // 未命中：回查数据库并写回缓存
        return loadFromDatabase(code);
    }

    private LinkSnapshot loadFromDatabase(String code) {
        LinkSnapshot snapshot = linkRepository.findByCode(code)
                .map(ShortLink::toSnapshot)
                .orElse(null);

        if (snapshot == null) {
            // 不存在的短码也写入缓存（较短的 TTL），防止重复查询穿透到数据库
            linkCache.putAbsent(code);
        } else {
            linkCache.put(snapshot);
        }
        return snapshot;
    }
}
