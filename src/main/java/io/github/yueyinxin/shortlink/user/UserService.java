package io.github.yueyinxin.shortlink.user;

import io.github.yueyinxin.shortlink.common.exception.BusinessException;
import io.github.yueyinxin.shortlink.common.exception.ErrorCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

/**
 * 用户查询与持久化。
 *
 * <p>本服务只负责用户的存取，不涉及认证流程（那是 {@code AuthService} 的职责）。
 * 这个边界让 {@code AuthService} 的单元测试可以 mock 掉本类，
 * 不需要准备数据库。
 */
@Service
public class UserService {

    private static final Logger log = LoggerFactory.getLogger(UserService.class);

    /**
     * 唯一索引名，用于在并发冲突时判定是哪个字段被占用。
     *
     * <p>这两个名字必须与 Flyway 脚本 {@code V1__create_users.sql} 中定义的一致。
     */
    private static final String UK_USERNAME = "uk_users_username";
    private static final String UK_EMAIL = "uk_users_email";

    private final UserRepository userRepository;

    public UserService(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    @Transactional(readOnly = true)
    public Optional<User> findById(Long id) {
        return userRepository.findById(id);
    }

    @Transactional(readOnly = true)
    public Optional<User> findByUsernameOrEmail(String identifier) {
        // 同一个输入同时作为用户名与邮箱去匹配。表上 username 与 email 使用默认的
        // ai_ci 排序规则（大小写不敏感），因此这里无需再做大小写归一化。
        return userRepository.findByUsernameOrEmail(identifier, identifier);
    }

    @Transactional(readOnly = true)
    public User getByIdOrThrow(Long id) {
        return userRepository.findById(id)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "用户不存在"));
    }

    /**
     * 保存并立即执行 INSERT。
     *
     * <p>用 {@code saveAndFlush} 而非 {@code save}：{@code save} 只是把实体纳入持久化上下文，
     * 真正的 INSERT 可能被推迟到事务提交时才发出。那时调用方已经离开 try 块，
     * 唯一约束冲突无法被捕获并转换为友好的 409 响应。
     */
    @Transactional
    public User saveAndFlush(User user) {
        return userRepository.saveAndFlush(user);
    }

    // ------------------------------------------------------------------ 唯一性检查

    /**
     * 预检查用户名是否可用。
     *
     * <p>这个检查<b>不保证</b>后续插入一定成功 —— 检查与插入之间的窗口里，
     * 另一个请求可能抢先占用了同一个用户名。唯一性最终由数据库唯一索引保证。
     * 预检查的价值在于：绝大多数情况下能给出精确、友好的错误提示，
     * 而不必等到数据库抛异常后再去猜测原因。
     */
    @Transactional(readOnly = true)
    public void checkUsernameAvailable(String username) {
        if (userRepository.existsByUsername(username)) {
            throw new BusinessException(ErrorCode.USERNAME_TAKEN);
        }
    }

    /** 预检查邮箱是否可用。语义与 {@link #checkUsernameAvailable} 相同。 */
    @Transactional(readOnly = true)
    public void checkEmailAvailable(String email) {
        if (userRepository.existsByEmail(email)) {
            throw new BusinessException(ErrorCode.EMAIL_TAKEN);
        }
    }

    /**
     * 把并发的唯一约束冲突转换为带有具体字段信息的业务异常。
     *
     * <h2>为什么通过索引名判断，而不是重新查询数据库</h2>
     * 最直觉的做法是"捕获异常后再查一次 existsByUsername 看是不是用户名重复了"。
     * 但这条路走不通：{@code DataIntegrityViolationException} 抛出后，
     * <b>当前事务已被标记为 rollback-only</b>，在它内部继续执行的查询即便能返回结果，
     * 其所在的整个事务也注定回滚。更糟的是，此时 Hibernate 的 Session 可能已处于
     * 不一致状态，后续操作的行为取决于具体驱动，不稳定。
     *
     * <p>真正要在新事务里重新查询，必须把那个方法放到<b>另一个 Bean</b> 上。
     * 原因是 Spring 的事务基于代理：从本类内部调用自己的 {@code @Transactional} 方法
     * 不会经过代理，{@code REQUIRES_NEW} 根本不会生效 —— 这是 Spring 最著名的陷阱之一。
     *
     * <p>相比之下，MySQL 在唯一约束冲突时会返回
     * {@code Duplicate entry 'xxx' for key 'users.uk_users_username'}，
     * 其中包含我们自己在建表时定义的索引名。索引名是我们控制的、稳定的标识，
     * 用它来分类既准确又不需要额外的事务。
     *
     * <p>若无法从消息中识别出索引名（例如换用了其他数据库或驱动），
     * 则返回一个涵盖两种可能的冲突错误，而不是把原始异常抛出去 ——
     * 后者会让用户看到一个 500，而实际上这是一次正常的并发竞争。
     */
    public BusinessException translateDuplicateViolation(String username, String email,
                                                        DataIntegrityViolationException cause) {
        String message = rootMessage(cause);

        if (message.contains(UK_USERNAME)) {
            log.info("并发注册冲突：用户名已被占用, username={}", username);
            return new BusinessException(ErrorCode.USERNAME_TAKEN);
        }
        if (message.contains(UK_EMAIL)) {
            log.info("并发注册冲突：邮箱已被占用, email 已脱敏");
            return new BusinessException(ErrorCode.EMAIL_TAKEN);
        }

        log.warn("无法识别的唯一约束冲突，返回通用冲突响应", cause);
        return new BusinessException(ErrorCode.USERNAME_TAKEN, "用户名或邮箱已被占用");
    }

    /**
     * 提取最底层异常的消息。
     *
     * <p>Spring 的 {@code DataIntegrityViolationException} 会把驱动的原始异常包在
     * 因果链的深处，顶层消息只有 "could not execute statement" 这类无信息量的内容。
     * 索引名在最底层。同时返回整个因果链的消息拼接，避免因某一层的消息为空而漏判。
     */
    private static String rootMessage(Throwable throwable) {
        StringBuilder builder = new StringBuilder();
        Throwable current = throwable;
        int depth = 0;
        // 限制深度：异常因果链理论上可能成环（虽不合规范），加个上限避免死循环
        while (current != null && depth++ < 10) {
            if (current.getMessage() != null) {
                builder.append(current.getMessage()).append(' ');
            }
            current = current.getCause();
        }
        return builder.toString();
    }
}
