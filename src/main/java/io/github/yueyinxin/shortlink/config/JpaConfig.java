package io.github.yueyinxin.shortlink.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;

/**
 * JPA 配置。
 *
 * <p>{@code @EnableJpaAuditing} 启用审计功能，让 {@code @CreatedDate} /
 * {@code @LastModifiedDate} 自动填充（见 {@code BaseEntity}）。
 *
 * <p>单独放在一个配置类里而不是加在主应用类上，原因是：审计功能依赖
 * {@code AuditingEntityListener} 这个 JPA 回调 Bean。如果把它加在
 * {@code @SpringBootApplication} 类上，那么在只加载部分上下文的分层测试
 * （例如 {@code @WebMvcTest}）中，主应用类仍会被扫描，导致审计相关的基础设施
 * 被一并加载，拖慢测试且引入不必要的依赖。隔离到独立的配置类后，
 * 不涉及持久化的测试不会加载它。
 */
@Configuration
@EnableJpaAuditing
public class JpaConfig {
}
