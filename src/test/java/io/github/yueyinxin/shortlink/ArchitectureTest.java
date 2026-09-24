package io.github.yueyinxin.shortlink;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchRule;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/**
 * 架构约束的自动校验。
 *
 * <h2>为什么架构约束必须被自动验证</h2>
 * 分层依赖是一条"人人都认同、但总会被违反"的规则。它的违反几乎从不是有意的 ——
 * 通常是某次赶功能时，为了少写一个 DTO 转换方法，在控制器里直接注入了 Repository；
 * 或者为了图方便，在领域类上加了 {@code @Service} 注解。
 *
 * <p>这类改动在代码评审时很容易被忽略（diff 里只是多了一行 import），
 * 但它会让架构逐月腐化，直到某天想给领域逻辑补测试时发现必须启动整个 Spring 上下文。
 *
 * <p><b>因此架构约束必须能被自动验证，否则一定会被违反。</b>
 * 本测试把这些规则写成可执行的断言，违反时 CI 立即失败。
 *
 * <h2>为什么用核心 API 而不是 archunit-junit5</h2>
 * 集成包需要与 JUnit 平台版本对齐，而核心 API 在一个普通 {@code @Test} 方法里
 * 足够表达全部断言，少一层版本耦合。依赖升级时不会因为 ArchUnit 尚未适配
 * 新版 JUnit 而卡住。
 */
class ArchitectureTest {

    private static JavaClasses productionClasses;

    @BeforeAll
    static void importClasses() {
        productionClasses = new ClassFileImporter()
                // 只导入生产代码。测试类不受分层规则约束 ——
                // 测试本来就需要跨越各层来组装被测对象。
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("io.github.yueyinxin.shortlink");
    }

    /**
     * 领域层不依赖任何框架。
     *
     * <p>这是整个分层架构里最重要的一条约束，也是收益最直接的一条：
     * 只要领域类不依赖 Spring / JPA / Redis，它们的测试就不需要启动容器、
     * 不需要数据库，可以在毫秒级完成。本项目 {@code ShortLink}、
     * {@code LinkSnapshot}、{@code ShortCodeGenerator} 的测试全部是纯 JVM 测试，
     * 正是这条约束带来的。
     *
     * <p>允许的例外是 Lombok —— 它在编译期生成代码，运行时不产生依赖，
     * 且 {@code @Getter} 这类注解不引入任何行为语义。
     */
    @Test
    @DisplayName("领域模型不依赖 Spring、JPA、Redis")
    void domainDoesNotDependOnFrameworks() {
        ArchRule rule = noClasses()
                .that().resideInAPackage("..link..")
                .and().haveSimpleNameEndingWith("Snapshot")
                .should().dependOnClassesThat().resideInAnyPackage(
                        "org.springframework..",
                        "jakarta.persistence..",
                        "org.hibernate..",
                        "io.lettuce..",
                        "redis.clients.."
                )
                .because("领域对象必须能被纯单元测试验证，不启动任何容器");

        rule.check(productionClasses);
    }

    /**
     * 控制器不得直接访问仓储层。
     *
     * <p>控制器跨越应用层直接调用 Repository 会绕过两样东西：
     * 事务边界（写在应用层）与归属校验（写在服务层）。这类绕过的后果是
     * 某些接口的事务行为与其他接口不一致，且越权检查被遗漏。
     */
    @Test
    @DisplayName("控制器不直接依赖 Repository")
    void controllersDoNotAccessRepositories() {
        ArchRule rule = noClasses()
                .that().haveSimpleNameEndingWith("Controller")
                .should().dependOnClassesThat().haveSimpleNameEndingWith("Repository")
                .because("控制器必须经过服务层，否则会绕过事务边界与归属校验");

        rule.check(productionClasses);
    }

    /**
     * 通用工具类不得依赖业务包。
     *
     * <p>{@code common} 包被所有功能域依赖。一旦它反过来依赖某个功能域，
     * 就形成了循环依赖 —— 此时 {@code common} 已经不再是通用的，
     * 而"改动 common 影响面很大"这个认知会让它逐渐无人敢动。
     */
    @Test
    @DisplayName("common 包不依赖任何功能域")
    void commonDoesNotDependOnFeatures() {
        ArchRule rule = noClasses()
                .that().resideInAPackage("..shortlink.common..")
                .should().dependOnClassesThat().resideInAnyPackage(
                        "..shortlink.link..",
                        "..shortlink.auth..",
                        "..shortlink.stats..",
                        "..shortlink.user.."
                )
                .because("common 被所有功能域依赖，反向依赖会形成循环");

        rule.check(productionClasses);
    }

    /**
     * 配置属性类不得依赖业务代码。
     *
     * <p>{@code @ConfigurationProperties} 记录类是纯数据载体，
     * 它们被各个功能域读取。若它们反过来依赖业务类，配置的加载顺序就会
     * 与业务 Bean 的创建顺序纠缠在一起，产生难以诊断的启动失败。
     */
    @Test
    @DisplayName("配置属性类只依赖 JDK 与校验注解")
    void propertiesArePureData() {
        ArchRule rule = noClasses()
                .that().resideInAPackage("..config.properties..")
                .should().dependOnClassesThat().resideInAnyPackage(
                        "..shortlink.link..",
                        "..shortlink.auth..",
                        "..shortlink.stats..",
                        "..shortlink.user..",
                        "org.springframework.data.."
                )
                .because("配置属性应当是纯数据，不参与业务逻辑");

        rule.check(productionClasses);
    }

    /**
     * 防止领域层出现反向依赖：功能域的 domain 类不得依赖自身的 infrastructure。
     *
     * <p>依赖方向必须是 {@code infrastructure → domain}，而不是反过来。
     * 反向依赖一旦出现，"换掉 Redis 实现"就需要同时改动领域类。
     */
    @Test
    @DisplayName("领域类不依赖基础设施实现")
    void domainDoesNotDependOnInfrastructure() {
        ArchRule rule = noClasses()
                .that().resideOutsideOfPackage("..infrastructure..")
                .and().resideInAnyPackage("..shortlink.link..", "..shortlink.auth..", "..shortlink.stats..")
                .and().haveSimpleNameEndingWith("Repository")
                .should().dependOnClassesThat().resideInAPackage("..infrastructure..")
                .because("依赖方向必须是 infrastructure → domain，反向依赖会让实现无法替换");

        rule.check(productionClasses);
    }
}
