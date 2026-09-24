package io.github.yueyinxin.shortlink.config;

import io.github.yueyinxin.shortlink.config.properties.JwtProperties;
import io.github.yueyinxin.shortlink.config.properties.SecurityProperties;
import io.github.yueyinxin.shortlink.config.properties.ShortLinkProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 启动期安全检查。
 *
 * <h2>为什么需要这个类</h2>
 * 配置文件里的密钥使用 {@code ${ENV_VAR:dev-default}} 的形式，好处是本地开发无需准备
 * 任何环境变量即可启动。代价是<b>如果生产部署时忘记设置环境变量，服务会带着开发默认密钥
 * 正常运行</b> —— 没有报错，没有告警，直到密钥被公开的默认值猜中。
 *
 * <p>这类问题无法靠"记得配置"来避免，必须由程序主动检查。本类在启动完成后检查
 * 若干高危配置，发现问题时打印醒目的告警或直接终止启动。
 *
 * <h2>告警还是终止</h2>
 * 区分两类问题：
 * <ul>
 *   <li><b>必然导致安全失效的</b>（生产环境使用开发默认密钥）—— <b>拒绝启动</b>。
 *       继续运行等于对外提供一个密钥公开的服务，比启动失败严重得多。
 *       启动失败会立刻被运维发现，而密钥泄露可能几个月都无人察觉。</li>
 *   <li><b>降低防护但可能是有意选择</b>（信任转发头、公开 Actuator 端点）
 *       —— 只打印 WARN。这些在特定部署拓扑下是合理的配置。</li>
 * </ul>
 *
 * <p>使用 {@link ApplicationReadyEvent} 而不是 {@code @PostConstruct}：
 * 在这个时点上下文已完全就绪，可以安全地读取 {@link Environment}；
 * 且此时终止应用会正常触发关闭流程（而 {@code @PostConstruct} 抛出异常
 * 会留下一个半初始化的上下文）。
 */
@Component
public class StartupSafetyCheck {

    private static final Logger log = LoggerFactory.getLogger(StartupSafetyCheck.class);

    /** 与配置文件中的默认值保持一致。修改默认值时这里必须同步。 */
    private static final String DEV_JWT_SECRET_DEFAULT =
            "dev-only-jwt-secret-do-not-use-in-production-min-32-bytes";
    private static final String DEV_CODE_SECRET_DEFAULT =
            "dev-only-code-secret-do-not-use-in-production";

    private static final String PROD_PROFILE = "prod";

    private final Environment environment;
    private final JwtProperties jwtProperties;
    private final ShortLinkProperties shortLinkProperties;
    private final SecurityProperties securityProperties;

    public StartupSafetyCheck(Environment environment,
                              JwtProperties jwtProperties,
                              ShortLinkProperties shortLinkProperties,
                              SecurityProperties securityProperties) {
        this.environment = environment;
        this.jwtProperties = jwtProperties;
        this.shortLinkProperties = shortLinkProperties;
        this.securityProperties = securityProperties;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void verifyConfiguration() {
        boolean productionProfile = isProductionProfile();

        List<String> fatalProblems = new ArrayList<>();
        List<String> warnings = new ArrayList<>();

        // ---- 致命问题：仅在 prod profile 下判定 ----
        // 开发环境使用默认密钥是预期行为，不应阻碍启动。
        if (productionProfile) {
            if (DEV_JWT_SECRET_DEFAULT.equals(jwtProperties.secret())) {
                fatalProblems.add("JWT 签名密钥仍是开发默认值。"
                        + "请设置环境变量 SHORTLINK_JWT_SECRET（至少 32 字节）");
            }
            if (DEV_CODE_SECRET_DEFAULT.equals(shortLinkProperties.code().secret())) {
                fatalProblems.add("短码置换密钥仍是开发默认值。"
                        + "请设置环境变量 SHORTLINK_CODE_SECRET");
            }
            if (shortLinkProperties.baseUrl().contains("localhost")) {
                warnings.add("shortlink.base-url 仍指向 localhost，"
                        + "返回给客户端的短链将无法被外部访问。请设置 SHORTLINK_BASE_URL");
            }
        }

        // ---- 警告：任何环境下都提示 ----
        if (securityProperties.trustForwardedHeaders() && productionProfile) {
            warnings.add("已开启 trust-forwarded-headers。"
                    + "请确认边缘反向代理会【覆盖】而非追加 X-Forwarded-For 头，"
                    + "否则客户端可伪造 IP 绕过限流");
        }
        if (securityProperties.publicActuatorEndpoints()) {
            warnings.add("已开启 public-actuator-endpoints，"
                    + "全部 Actuator 端点无需认证即可访问，可能泄露配置与内部结构");
        }
        if (productionProfile && !shortLinkProperties.stats().enabled()) {
            warnings.add("统计刷库任务已关闭，访问统计将不会落库");
        }

        warnings.forEach(message -> log.warn("⚠ 配置警告: {}", message));

        if (!fatalProblems.isEmpty()) {
            fatalProblems.forEach(message -> log.error("✗ 配置错误: {}", message));
            throw new IllegalStateException(
                    "生产环境配置检查未通过（共 %d 项），拒绝启动: %s"
                            .formatted(fatalProblems.size(), String.join("; ", fatalProblems)));
        }

        log.info("启动配置检查通过: profile={}, 短码长度={}, 统计刷库={}",
                String.join(",", environment.getActiveProfiles()),
                shortLinkProperties.code().length(),
                shortLinkProperties.stats().enabled() ? "开启" : "关闭");
    }

    private boolean isProductionProfile() {
        for (String profile : environment.getActiveProfiles()) {
            if (PROD_PROFILE.equalsIgnoreCase(profile)) {
                return true;
            }
        }
        return false;
    }
}
