package io.github.yueyinxin.shortlink.config.properties;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * 安全相关配置。
 *
 * @param trustForwardedHeaders 是否信任 {@code X-Forwarded-For} 等转发头来识别客户端 IP。
 *                              <p><b>默认必须为 false。</b>开启的前提是服务部署在受控反向代理之后，
 *                              且边缘代理会<b>覆盖</b>（而非追加）客户端传入的这些头。
 *                              否则任何客户端都能伪造 IP，使按 IP 的限流完全失效。
 * @param publicActuatorEndpoints 是否公开全部 Actuator 端点。
 *                                <p><b>默认必须为 false</b>，此时仅 {@code /actuator/health} 可匿名访问。
 *                                Actuator 是信息泄露重灾区：{@code /actuator/env} 会暴露配置项、
 *                                {@code /actuator/beans} 会暴露内部组件结构、
 *                                {@code /actuator/heapdump} 甚至能导出包含密码的内存快照。
 */
@Validated
@ConfigurationProperties(prefix = "shortlink.security")
public record SecurityProperties(
        boolean trustForwardedHeaders,
        boolean publicActuatorEndpoints
) {
}
