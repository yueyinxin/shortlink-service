package io.github.yueyinxin.shortlink;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.mysql.MySQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * 集成测试的基础设施配置。
 *
 * <h2>为什么用 Testcontainers 而不是 H2</h2>
 * H2 与 MySQL 存在大量行为差异：{@code ON DUPLICATE KEY UPDATE} 语法、
 * {@code LIMIT} 的分页写法、字符集与排序规则、{@code DATETIME} 精度、大小写敏感性。
 * 在 H2 上通过的测试<b>不能保证</b>在 MySQL 上也能通过 —— 这类"假绿"测试比没有测试
 * 更危险，因为它会让人对结论产生错误的信心。
 *
 * <p>本项目最关键的几处逻辑（短码列的大小写敏感唯一索引、统计的 UPSERT 语义、
 * Flyway 迁移脚本）都依赖 MySQL 的特定行为，必须用真实 MySQL 验证。
 *
 * <p>代价是首次运行需要拉取镜像（约 600MB）并等待容器启动。因此这些测试放在
 * {@code *IT.java} 中由 {@code mvn verify} 执行，而 {@code mvn test} 只跑不需要
 * 外部依赖的单元测试 —— 让编写代码时的快速反馈循环不必等待容器。
 *
 * <h2>关于镜像版本</h2>
 * 固定到具体次版本（{@code mysql:8.0}）而不是 {@code mysql:latest}。
 * {@code latest} 会在某天变成 9.x，届时字符集默认值、SQL 模式都可能变化，
 * 让"昨天还能跑"的测试突然失败，而失败原因与被测代码毫无关系。
 */
@TestConfiguration(proxyBeanMethods = false)
public class TestcontainersConfiguration {

    private static final String MYSQL_IMAGE = "mysql:8.0";
    private static final String REDIS_IMAGE = "redis:7-alpine";

    /**
     * {@code @ServiceConnection} 让 Spring Boot 自动把容器的连接信息注入到
     * {@code spring.datasource.*} 配置中，无需手写 {@code @DynamicPropertySource}。
     * 容器使用随机端口，因此不会与开发者本机已运行的 MySQL 实例产生端口冲突。
     *
     * <p><b>注意</b>：Testcontainers 2.x 起 {@code MySQLContainer} 不再是泛型类
     * （1.x 中形如 {@code MySQLContainer<SELF>}）。升级依赖版本时这里会编译失败 ——
     * 这是好事，比运行时才暴露不兼容要好。
     */
    @Bean
    @ServiceConnection
    MySQLContainer mysqlContainer() {
        return new MySQLContainer(DockerImageName.parse(MYSQL_IMAGE))
                // 字符集、排序规则、时区必须显式指定，与 compose.yaml 中的配置保持一致。
                // 容器默认值可能与生产部署不同，那会让测试通过而生产出问题。
                .withCommand(
                        "--character-set-server=utf8mb4",
                        "--collation-server=utf8mb4_0900_ai_ci",
                        "--default-time-zone=+08:00"
                );
    }

    @Bean
    @ServiceConnection(name = "redis")
    GenericContainer<?> redisContainer() {
        return new GenericContainer<>(DockerImageName.parse(REDIS_IMAGE))
                .withExposedPorts(6379);
    }
}
