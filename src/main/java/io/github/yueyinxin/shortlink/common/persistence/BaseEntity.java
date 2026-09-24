package io.github.yueyinxin.shortlink.common.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.MappedSuperclass;
import lombok.Getter;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.LocalDateTime;

/**
 * 带审计时间的实体基类。
 *
 * <p>时间字段由 Spring Data JPA 的审计机制自动填充，实体自身不需要在构造函数里写
 * {@code this.createdAt = LocalDateTime.now()}。这样做的价值不只是省几行代码：
 * <ul>
 *   <li><b>一致性</b> —— 所有表的时间来自同一处，不会出现某个实体用了错误的时区或格式；</li>
 *   <li><b>不可篡改</b> —— {@code createdAt} 标记为 {@code updatable = false}，
 *       即使某处代码误改了实体字段，也不会污染创建时间；</li>
 *   <li><b>与数据库列对齐</b> —— 列名、可空性、精度集中声明，与 Flyway 脚本一一对应。</li>
 * </ul>
 *
 * <p>启用审计需要在配置类上添加 {@code @EnableJpaAuditing}。
 *
 * <p><b>注意</b>：审计时间来自应用服务器时钟，不是数据库时钟。多实例部署时若各实例时钟
 * 不同步（未配置 NTP），时间戳会出现回退，可能影响基于时间的排序。生产环境应确保
 * 所有节点启用 NTP 时间同步。
 */
@MappedSuperclass
@EntityListeners(AuditingEntityListener.class)
@Getter
public abstract class BaseEntity {

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @LastModifiedDate
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;
}
