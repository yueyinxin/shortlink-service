package io.github.yueyinxin.shortlink.user;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

/**
 * 用户仓储。
 */
@Repository
public interface UserRepository extends JpaRepository<User, Long> {

    /**
     * 按用户名或邮箱查找。
     *
     * <p>登录接口允许用户输入用户名或邮箱（AC-02.1 未限制登录标识的形式）。
     * 用一次查询而不是"先判断输入像不像邮箱、再选一个字段查"，是因为后者需要
     * 一个永远不够准确的格式猜测，且会引入两条代码路径。
     *
     * <p>比较是大小写不敏感的：{@code users} 表的 username / email 列使用 MySQL 默认的
     * {@code utf8mb4_0900_ai_ci} 排序规则，因此这里的等值比较天然忽略大小写，
     * 不需要也不能再套一层 {@code LOWER()}（后者会让索引失效）。
     */
    Optional<User> findByUsernameOrEmail(String username, String email);

    boolean existsByUsername(String username);

    boolean existsByEmail(String email);
}
