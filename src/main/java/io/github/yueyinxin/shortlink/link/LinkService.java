package io.github.yueyinxin.shortlink.link;

import io.github.yueyinxin.shortlink.common.exception.BusinessException;
import io.github.yueyinxin.shortlink.common.exception.ErrorCode;
import io.github.yueyinxin.shortlink.common.response.PageResponse;
import io.github.yueyinxin.shortlink.config.properties.ShortLinkProperties;
import io.github.yueyinxin.shortlink.link.dto.CreateLinkRequest;
import io.github.yueyinxin.shortlink.link.dto.LinkResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Locale;

/**
 * 短链管理服务：创建、查询、启停、删除。
 *
 * <p>本服务不处理跳转（那是 {@code RedirectService} 的职责）。拆开的理由是两者的关注点
 * 截然不同：本服务关心<b>一致性与归属</b>，跳转服务关心<b>延迟与可用性</b>。
 * 混在一个类里，任何为优化跳转性能的改动都会让管理逻辑的读者产生疑虑。
 */
@Service
public class LinkService {

    private static final Logger log = LoggerFactory.getLogger(LinkService.class);

    /**
     * 分页大小上限。
     *
     * <p>没有上限的分页参数是一个<b>拒绝服务漏洞</b>：客户端传 {@code size=1000000}
     * 会一次性把百万行加载进内存，单个请求即可耗尽堆空间。上限必须由服务端强制，
     * 不能指望客户端"合理地"传参。
     */
    private static final int MAX_PAGE_SIZE = 100;

    private static final int DEFAULT_PAGE_SIZE = 20;

    /** LIKE 转义字符。选 {@code !} 而非反斜杠，避免在 Java 与 JPQL 中双重转义。 */
    private static final String LIKE_ESCAPE = "!";

    private final ShortLinkRepository linkRepository;
    private final LinkCache linkCache;
    private final ShortCodeGenerator codeGenerator;
    private final ShortLinkProperties properties;

    public LinkService(ShortLinkRepository linkRepository,
                       LinkCache linkCache,
                       ShortCodeGenerator codeGenerator,
                       ShortLinkProperties properties) {
        this.linkRepository = linkRepository;
        this.linkCache = linkCache;
        this.codeGenerator = codeGenerator;
        this.properties = properties;
    }

    // ------------------------------------------------------------------ 创建

    /**
     * 创建短链。
     *
     * <p>两条路径的差别只在于短码来源：自动生成或用户指定。无论哪一条，
     * 唯一性最终都由数据库的 {@code uk_short_link_code} 唯一索引保证；
     * 应用层的检查只是为了让常见错误有更友好的提示。
     */
    @Transactional
    public LinkResponse create(Long userId, CreateLinkRequest request) {
        ShortLink link = request.hasCustomCode()
                ? createWithCustomCode(userId, request)
                : createWithGeneratedCode(userId, request);

        log.info("短链创建成功: id={}, code={}, userId={}", link.getId(), link.getCode(), userId);
        return LinkResponse.from(link, properties.baseUrl());
    }

    /**
     * 使用自动生成的短码创建。
     *
     * <h2>为什么要分两步：先插入、再回填短码</h2>
     * 短码是通过 Feistel 置换从数据库自增主键推导出来的（见 ADR-0002），
     * 因此<b>必须先拿到主键才能计算短码</b>，而主键只有在 INSERT 之后才存在。
     *
     * <p>两次 {@code saveAndFlush} 在同一事务内完成，中间状态（占位短码）绝不会被提交。
     * 多一次 UPDATE 的代价，换来的是"短码无冲突是数学保证"——
     * 不需要查重、不需要重试、并发下不会失败。
     *
     * <p>用自增主键而不是独立的发号器：主键本身就是唯一且单调递增的，
     * 再维护一个序列表只会增加一次写入而没有收益。
     */
    private ShortLink createWithGeneratedCode(Long userId, CreateLinkRequest request) {
        ShortLink link = ShortLink.create(userId, request.originalUrl(), request.expiresAt());

        // 阶段一：以占位短码插入，取得自增主键
        ShortLink inserted = linkRepository.saveAndFlush(link);

        // 阶段二：由主键推导短码并回填
        String generatedCode = codeGenerator.generate(inserted.getId());
        inserted.assignCode(ShortCode.of(generatedCode));

        return linkRepository.saveAndFlush(inserted);
    }

    /**
     * 使用用户指定的短码创建。
     */
    private ShortLink createWithCustomCode(Long userId, CreateLinkRequest request) {
        ShortCode shortCode = parseCustomCode(request.customCode());

        // 预检查：让"短码已被占用"这个常见情况得到 409 而不是数据库异常。
        // 它不保证后续插入一定成功（检查与插入之间存在窗口），
        // 真正的唯一性由下面的唯一索引兜底。
        if (linkRepository.existsByCode(shortCode.value())) {
            throw new BusinessException(ErrorCode.CODE_TAKEN);
        }

        ShortLink link = ShortLink.create(userId, request.originalUrl(), request.expiresAt());
        link.assignCode(shortCode);

        try {
            return linkRepository.saveAndFlush(link);
        } catch (DataIntegrityViolationException e) {
            // 并发下另一个请求抢先占用了同一短码。返回 409 而不是 500 ——
            // 这是一次正常的竞争，不是服务故障。
            log.info("并发创建冲突：短码已被占用, code={}", shortCode.value());
            throw new BusinessException(ErrorCode.CODE_TAKEN);
        }
    }

    /**
     * 解析并校验自定义短码。
     *
     * <p>保留字检查在这一层而不是 DTO 的注解里，因为保留字集合来自
     * {@link ReservedCodes} 这个领域常量。若把保留字硬编码进正则表达式，
     * 两处定义迟早会不同步。同时保留字冲突有专门的错误码，
     * 能给出比"格式不合法"更准确的提示。
     */
    private static ShortCode parseCustomCode(String raw) {
        ShortCode shortCode;
        try {
            shortCode = ShortCode.of(raw);
        } catch (IllegalArgumentException e) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, e.getMessage());
        }
        if (shortCode.isReserved()) {
            throw new BusinessException(ErrorCode.CODE_RESERVED);
        }
        return shortCode;
    }

    // ------------------------------------------------------------------ 查询

    /**
     * 分页查询当前用户的短链。
     *
     * <p>排序键是 {@code (createdAt DESC, id DESC)}。加上 {@code id} 作为次级排序键
     * 是为了保证<b>分页稳定性</b>：若两条短链的 {@code createdAt} 完全相同
     * （同一微秒内创建），只按 {@code createdAt} 排序时数据库可能返回任意顺序，
     * 导致同一条记录在第 1 页和第 2 页都出现，或某条记录完全看不到。
     */
    @Transactional(readOnly = true)
    public PageResponse<LinkResponse> list(Long userId, String keyword, Integer page, Integer size) {
        Pageable pageable = PageRequest.of(
                page == null ? 0 : Math.max(0, page),
                clampPageSize(size),
                Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("id"))
        );

        Page<ShortLink> result = linkRepository.searchByUser(userId, buildSearchPattern(keyword), pageable);
        return PageResponse.from(result, link -> LinkResponse.from(link, properties.baseUrl()));
    }

    /**
     * 查询单个短链。
     *
     * <p>通过 {@code findByIdAndUserId} 查询，归属校验体现在 SQL 的 {@code WHERE} 子句里。
     * 不属于当前用户的记录<b>根本查不出来</b>，对外返回 404 而非 403 ——
     * 403 会泄露"该短码存在但不属于你"这一信息。
     */
    @Transactional(readOnly = true)
    public LinkResponse get(Long userId, Long linkId) {
        return LinkResponse.from(requireOwnedLink(userId, linkId), properties.baseUrl());
    }

    // ------------------------------------------------------------------ 状态变更

    /**
     * 启用或禁用短链。
     *
     * <p>状态变更后<b>必须清理缓存</b>。这是 Cache-Aside 中最容易遗漏的一步：
     * 跳转链路读的是缓存，若只更新数据库而不清理缓存，被禁用的短链在缓存过期前
     * <b>仍然可以跳转</b>（最长 24 小时）。这不只是数据陈旧，而是一个真实的安全问题。
     *
     * <p>操作是幂等的：对已禁用的链接再次禁用，数据上无变化，因此不会刷新
     * {@code updated_at}（见 {@code ShortLink#disable()}）。
     */
    @Transactional
    public LinkResponse updateStatus(Long userId, Long linkId, boolean enabled) {
        ShortLink link = requireOwnedLink(userId, linkId);

        if (enabled) {
            link.enable();
        } else {
            link.disable();
        }

        ShortLink saved = linkRepository.saveAndFlush(link);
        linkCache.evict(saved.getCode());

        log.info("短链状态已更新: id={}, code={}, enabled={}", saved.getId(), saved.getCode(), enabled);
        return LinkResponse.from(saved, properties.baseUrl());
    }

    /**
     * 删除短链。
     *
     * <p>物理删除，统计数据通过外键 {@code ON DELETE CASCADE} 一并清除。
     * 不采用软删除的理由见 {@code docs/03-database-design.md §3.1} ——
     * 核心原因是 MySQL 唯一索引中 NULL 互不相等，软删除会让未删除记录的
     * 短码唯一性约束完全失效。
     *
     * <p>缓存清理在数据库删除<b>之后</b>：Cache-Aside 的正确顺序是先更新数据库再删缓存。
     * 反过来的话，"删缓存 → 删库"之间若有读请求，会把旧数据重新写回缓存并长期留存。
     */
    @Transactional
    public void delete(Long userId, Long linkId) {
        ShortLink link = requireOwnedLink(userId, linkId);
        String code = link.getCode();

        linkRepository.delete(link);
        linkRepository.flush();

        linkCache.evict(code);

        log.info("短链已删除: id={}, code={}, userId={}", linkId, code, userId);
    }

    // ------------------------------------------------------------------ 内部

    /** 查询并断言归属。查不到或不属于该用户，一律返回 404，不区分两种情况。 */
    private ShortLink requireOwnedLink(Long userId, Long linkId) {
        return linkRepository.findByIdAndUserId(linkId, userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "短链不存在"));
    }

    private static int clampPageSize(Integer size) {
        if (size == null || size < 1) {
            return DEFAULT_PAGE_SIZE;
        }
        return Math.min(size, MAX_PAGE_SIZE);
    }

    /**
     * 构造 LIKE 模式串。
     *
     * <p>转义用户输入中的 {@code %} 与 {@code _}。若不转义，搜索 {@code 100%} 会变成
     * "匹配任意以 100 开头的内容"，返回大量无关结果 —— 用户会认为搜索有 bug，
     * 而这其实是 SQL 通配符的正常行为。
     *
     * <p>关键字为空时返回 {@code "%"}（匹配全部）而不是 {@code null}：
     * JPQL 中 {@code :param IS NULL} 与参数类型推断的交互容易产生意外行为，
     * 而 {@code LIKE '%%'} 语义明确且不含分支。
     *
     * <p>统一转小写，配合查询中的 {@code LOWER(...)} 实现大小写不敏感搜索。
     */
    private static String buildSearchPattern(String keyword) {
        if (keyword == null || keyword.isBlank()) {
            return "%";
        }
        String escaped = keyword.toLowerCase(Locale.ROOT)
                .replace(LIKE_ESCAPE, LIKE_ESCAPE + LIKE_ESCAPE)
                .replace("%", LIKE_ESCAPE + "%")
                .replace("_", LIKE_ESCAPE + "_");
        return "%" + escaped + "%";
    }
}
