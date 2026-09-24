package io.github.yueyinxin.shortlink;

import io.github.yueyinxin.shortlink.link.LinkService;
import io.github.yueyinxin.shortlink.link.ShortCode;
import io.github.yueyinxin.shortlink.link.ShortLink;
import io.github.yueyinxin.shortlink.link.ShortLinkRepository;
import io.github.yueyinxin.shortlink.stats.LinkStat;
import io.github.yueyinxin.shortlink.stats.infrastructure.LinkStatRepository;
import io.github.yueyinxin.shortlink.user.User;
import io.github.yueyinxin.shortlink.user.UserRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;

import java.time.LocalDate;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 持久层的集成测试 —— 用真实 MySQL 验证，不用 H2。
 *
 * <h2>为什么这些测试必须是集成测试</h2>
 * 本类中的每一条断言都依赖 <b>MySQL 的特定行为</b>，在 H2 上要么无法表达、
 * 要么会用不同的语义"通过"，从而给出虚假的信心：
 *
 * <ul>
 *   <li><b>短码列的大小写敏感性</b> —— 依赖 {@code utf8mb4_bin} 排序规则。
 *       H2 的字符串比较默认就区分大小写，因此该断言在 H2 上<b>无论建表是否正确
 *       都会通过</b>，完全失去检出能力。而若这条规则配错，短码空间会从 62 个字符
 *       退化为 36 个，且两个视觉上不同的短链会互相冲突。</li>
 *   <li><b>统计的 UPSERT 语义</b> —— 依赖 {@code INSERT ... ON DUPLICATE KEY UPDATE}
 *       与 {@code GREATEST()}。H2 不支持该语法，需用 {@code MERGE} 替代，
 *       而两者的并发行为并不相同。</li>
 *   <li><b>Flyway 迁移 + Hibernate 结构校验</b> —— 依赖 {@code DATETIME(6)} 精度、
 *       索引定义、外键与 CHECK 约束在 MySQL 中的实际落地结果。</li>
 * </ul>
 *
 * <p>这正是本项目拒绝用 H2 做集成测试的原因：那些差异不会让测试失败，
 * 而是让测试<b>失去意义</b> —— 它退化成一条永远为真的断言。
 *
 * <h2>运行前提</h2>
 * 需要 Docker。通过 {@code mvn verify} 执行（{@code *IT.java} 由 failsafe 插件运行），
 * 不属于 {@code mvn test} 的范围，因此不影响本地开发的秒级反馈。
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@DisplayName("持久层集成测试（真实 MySQL）")
class PersistenceIT {

    /**
     * 测试内唯一的序号来源。
     *
     * <p>用递增计数器而不是时间戳：同一测试方法内多次调用时，
     * 纳秒时间戳有概率返回相同值（尤其在时钟精度较低的平台上），
     * 导致本该唯一的名称产生冲突，让测试变得不稳定。
     */
    private static final AtomicInteger SEQUENCE = new AtomicInteger();

    /** 一个合法但无意义的 BCrypt 哈希。本类只验证持久化行为，不涉及真实密码校验。 */
    private static final String DUMMY_HASH =
            "$2a$10$N9qo8uLOickgx2ZMRZoMyeIjZAgcfl7p92ldGxad68LJZdL17lhWy";

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private ShortLinkRepository linkRepository;

    @Autowired
    private LinkStatRepository linkStatRepository;

    @Autowired
    private LinkService linkService;

    /**
     * 上下文能启动本身就证明了两件事：
     * <ol>
     *   <li>三个 Flyway 迁移脚本在真实 MySQL 上执行成功；</li>
     *   <li>{@code spring.jpa.hibernate.ddl-auto=validate} 通过 ——
     *       实体映射与迁移脚本建出的表结构完全一致。</li>
     * </ol>
     * 第 2 点尤其重要：它让"改了实体忘了写迁移脚本"这类错误在启动时被拦住，
     * 而不是等线上某个查询报 "Unknown column" 才发现。
     */
    @Test
    @DisplayName("Flyway 迁移执行成功，且实体映射与表结构校验通过")
    void migrationsApplyAndSchemaValidates() {
        assertThat(userRepository.count()).isGreaterThanOrEqualTo(0L);
    }

    /**
     * 短码列的大小写敏感唯一索引。
     *
     * <p>这是本项目最容易出错的建表细节。MySQL 默认的 {@code utf8mb4_0900_ai_ci}
     * 大小写不敏感，若 {@code code} 列沿用默认值，{@code aB3x9K} 与 {@code Ab3X9k}
     * 会被唯一索引判为重复。
     *
     * <p><b>这条断言在 H2 上无法检出问题</b>：H2 的字符串比较本就区分大小写，
     * 因此无论建表语句是否正确它都会通过。只有真实 MySQL 才能验证。
     */
    @Test
    @DisplayName("短码唯一索引区分大小写：aB3x9K 与 Ab3X9k 可以共存")
    void shortCodeIndexIsCaseSensitive() {
        Long userId = createUser("casesensitive");

        persistLink(userId, "https://example.com/upper", "aB3x9K");

        // 若 code 列是大小写不敏感的排序规则，这里会抛出唯一约束冲突
        assertThatCode(() -> persistLink(userId, "https://example.com/lower", "Ab3X9k"))
                .as("大小写不同的短码必须能共存，否则短码空间会退化")
                .doesNotThrowAnyException();

        assertThat(linkRepository.findByCode("aB3x9K")).isPresent();
        assertThat(linkRepository.findByCode("Ab3X9k")).isPresent();
        // 大小写敏感意味着全小写查不到（列存的是 aB3x9K）
        assertThat(linkRepository.findByCode("ab3x9k")).isEmpty();
    }

    /**
     * 统计的 UPSERT 语义。
     *
     * <p>PV 是增量，多次写入应当累加；UV 是绝对值，多次写入应当取最大值。
     * 这个差异不是随意的：HyperLogLog 无法做差集，如果 UV 也累加，
     * 同一访客在多次刷库中会被重复计数。
     *
     * <p>同时验证 {@code GREATEST} 带来的幂等性：重复写入同一个 UV 值不产生变化。
     * 这一点很重要 —— 刷库任务在键被提前清理、任务重复执行等情况下会重复写入，
     * 必须保证结果不受影响。
     */
    @Test
    @DisplayName("统计 UPSERT：PV 累加、UV 取最大值、重复写入幂等")
    void statsUpsertAddsPvAndTakesMaxUv() {
        Long userId = createUser("statsupsert");
        Long linkId = persistLink(userId, "https://example.com/stats", "uP5q2W");
        LocalDate today = LocalDate.now();

        // 第一次刷库：pv=10, uv=8
        linkStatRepository.upsert(linkId, today, 10L, 8L);

        // 第二次刷库：pv 增量 5（应累加为 15），uv 估值 12（应取 12）
        linkStatRepository.upsert(linkId, today, 5L, 12L);

        LinkStat afterSecondFlush = requireStat(linkId, today);
        assertThat(afterSecondFlush.getPv()).as("PV 是增量语义，应当累加").isEqualTo(15L);
        assertThat(afterSecondFlush.getUv()).as("UV 是绝对值语义，应当取最大值").isEqualTo(12L);

        // 模拟刷库重试：增量为 0，UV 与当前值相同。两者都不应产生变化。
        linkStatRepository.upsert(linkId, today, 0L, 12L);

        LinkStat afterRetry = requireStat(linkId, today);
        assertThat(afterRetry.getPv()).as("增量为 0 时 PV 不变").isEqualTo(15L);
        assertThat(afterRetry.getUv()).as("UV 取 max 是幂等的").isEqualTo(12L);
    }

    /**
     * 删除短链时统计数据级联删除。
     *
     * <p>依赖外键的 {@code ON DELETE CASCADE}。这是物理删除方案的组成部分 ——
     * 短链都不存在了，孤立的统计行没有意义，且会阻碍短码复用
     * （见 {@code docs/03-database-design.md §3.1}）。
     */
    @Test
    @DisplayName("删除短链时统计数据级联删除")
    void deletingLinkCascadesStats() {
        Long userId = createUser("cascade");
        Long linkId = persistLink(userId, "https://example.com/cascade", "cAsc9Z");
        LocalDate today = LocalDate.now();

        linkStatRepository.upsert(linkId, today, 7L, 3L);
        assertThat(linkStatRepository.findByLinkIdAndStatDate(linkId, today)).isPresent();

        linkRepository.deleteById(linkId);
        linkRepository.flush();

        assertThat(linkStatRepository.findByLinkIdAndStatDate(linkId, today))
                .as("短链删除后其统计应一并消失")
                .isEmpty();
    }

    /**
     * 用户表的大小写不敏感唯一索引。
     *
     * <p>与短码列相反，{@code username} 使用 MySQL 默认的 {@code ai_ci} 排序规则 ——
     * 这是刻意的：「Alice」与「alice」应视为同一用户名，
     * 避免用户注册出视觉上无法区分的两个账号。
     */
    @Test
    @DisplayName("用户名唯一索引不区分大小写：Alice 与 alice 视为同一用户")
    void usernameIndexIsCaseInsensitive() {
        String username = "CaseTest" + SEQUENCE.incrementAndGet();
        saveUser(username);

        User duplicate = User.register(
                username.toLowerCase(java.util.Locale.ROOT),   // 仅大小写不同
                "other-" + SEQUENCE.incrementAndGet() + "@example.com",
                DUMMY_HASH);

        assertThatThrownBy(() -> userRepository.saveAndFlush(duplicate))
                .as("仅大小写不同的用户名应被唯一索引拒绝")
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    /**
     * 关键字搜索中的 LIKE 通配符转义。
     *
     * <p>{@code %} 与 {@code _} 在 SQL 的 {@code LIKE} 中是通配符。若不转义：
     * <ul>
     *   <li>搜索 {@code %} 会变成 {@code LIKE '%%%'}，匹配<b>全部</b>记录；</li>
     *   <li>搜索 {@code _} 会变成 {@code LIKE '%_%'}，匹配任意至少一个字符的记录，
     *       同样等于匹配全部。</li>
     * </ul>
     * 用户搜索一个包含这些符号的字符串时会得到大量无关结果，并认为搜索功能有 bug。
     *
     * <p>构造方式说明：不能通过"创建一条 URL 里含字面量 % 的记录，再搜索它"来验证 ——
     * 裸 {@code %} 后跟非十六进制字符在 URI 中是非法转义，会被
     * {@code HttpUrl} 校验拦下。因此这里反过来验证：搜索通配符本身应当
     * <b>匹配不到任何记录</b>，而不是匹配全部。若转义被移除，这条测试会立刻失败。
     */
    @Test
    @DisplayName("搜索关键字中的 % 和 _ 被转义，不匹配全部记录")
    void searchEscapesLikeWildcards() {
        Long userId = createUser("searchescape");
        persistLink(userId, "https://example.com/alpha-page", "se1a2b");
        persistLink(userId, "https://example.com/beta-page", "se3c4d");

        // 先确认无关键字时能查到这两条 —— 否则下面的断言会因为"本来就查不到"而假通过
        assertThat(linkService.list(userId, null, 0, 20).content())
                .as("前置条件：该用户应有 2 条短链")
                .hasSize(2);

        assertThat(linkService.list(userId, "%", 0, 20).content())
                .as("%% 应当作为字面量搜索，匹配不到任何记录")
                .isEmpty();

        assertThat(linkService.list(userId, "_", 0, 20).content())
                .as("下划线应当作为字面量搜索，匹配不到任何记录")
                .isEmpty();

        // 正常关键字仍然能命中
        assertThat(linkService.list(userId, "alpha", 0, 20).content())
                .as("普通关键字应正常命中")
                .hasSize(1);
    }

    /**
     * 分页排序的稳定性。
     *
     * <p>排序键是 {@code (createdAt DESC, id DESC)}。加上 {@code id} 作为次级排序键
     * 是为了处理"同一时刻创建多条"的情况：如果只按 {@code createdAt} 排序，
     * 数据库可能返回任意顺序，导致同一条记录在第 1 页和第 2 页都出现、
     * 或某条记录完全看不到。这里连续插入多条记录后翻页验证。
     */
    @Test
    @DisplayName("分页排序稳定：翻页时记录不重复、不丢失")
    void paginationIsStable() {
        Long userId = createUser("pagination");

        List<Long> created = new java.util.ArrayList<>();
        for (int i = 0; i < 25; i++) {
            created.add(persistLink(userId,
                    "https://example.com/page/" + i,
                    "pg%04d".formatted(i)));
        }

        Sort sort = Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("id"));

        List<Long> firstPage = ids(linkRepository
                .searchByUser(userId, "%", PageRequest.of(0, 10, sort)));
        List<Long> secondPage = ids(linkRepository
                .searchByUser(userId, "%", PageRequest.of(1, 10, sort)));
        List<Long> thirdPage = ids(linkRepository
                .searchByUser(userId, "%", PageRequest.of(2, 10, sort)));

        assertThat(firstPage).hasSize(10).doesNotHaveDuplicates();
        assertThat(secondPage).hasSize(10).doesNotHaveDuplicates();
        assertThat(thirdPage).hasSize(5).doesNotHaveDuplicates();

        assertThat(firstPage)
                .as("相邻两页不应有重叠记录，否则会有记录被翻页跳过")
                .doesNotContainAnyElementsOf(secondPage);
        assertThat(secondPage).doesNotContainAnyElementsOf(thirdPage);

        // 三页合起来恰好是全部 25 条，无重复无遗漏
        List<Long> allPaged = new java.util.ArrayList<>(firstPage);
        allPaged.addAll(secondPage);
        allPaged.addAll(thirdPage);
        assertThat(allPaged)
                .as("分页结果的并集应与创建的记录完全一致")
                .containsExactlyInAnyOrderElementsOf(created);
    }

    // ------------------------------------------------------------------ 辅助

    /**
     * 创建一个测试用户并返回其 ID。
     *
     * <p>用户名用"前缀 + 递增序号"构造，长度控制在 {@code VARCHAR(32)} 之内 ——
     * 早先的版本用完整类名做前缀，加上序号后超过了列宽，
     * 表现为插入失败而不是断言失败，排查时容易误以为是业务问题。
     */
    private Long createUser(String prefix) {
        String suffix = Integer.toString(SEQUENCE.incrementAndGet(), 36);
        String username = prefix + "-" + suffix;
        assertThat(username.length())
                .as("测试用户名的长度必须不超过列宽 32")
                .isLessThanOrEqualTo(32);
        return saveUser(username).getId();
    }

    private User saveUser(String username) {
        return userRepository.saveAndFlush(User.register(
                username,
                username + "@example.com",
                DUMMY_HASH));
    }

    /** 创建一条短链并返回其 ID。短码由调用方保证唯一。 */
    private Long persistLink(Long userId, String originalUrl, String code) {
        ShortLink link = ShortLink.create(userId, originalUrl, null);
        link.assignCode(ShortCode.of(code));
        return linkRepository.saveAndFlush(link).getId();
    }

    private LinkStat requireStat(Long linkId, LocalDate date) {
        return linkStatRepository.findByLinkIdAndStatDate(linkId, date)
                .orElseThrow(() -> new AssertionError("未找到统计数据: linkId=" + linkId + ", date=" + date));
    }

    private static List<Long> ids(org.springframework.data.domain.Page<ShortLink> page) {
        return page.map(ShortLink::getId).getContent();
    }
}
