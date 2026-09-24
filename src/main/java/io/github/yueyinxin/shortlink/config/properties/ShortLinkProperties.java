package io.github.yueyinxin.shortlink.config.properties;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

/**
 * 短链服务的核心配置。
 *
 * <p>使用 {@code record} 做构造绑定，配合 {@code @Validated} 与 Jakarta 校验注解：
 * 配置缺失或非法时应用<b>启动即失败</b>，并给出明确的字段名。
 * 这比"跑到某个功能时才抛 NPE"好得多 —— 配置错误的暴露时机应该在最前面。
 *
 * <p>所有字段无默认值，默认值统一定义在 {@code application.yml}。
 * 这样"默认配置长什么样"只有一个来源，不会出现"代码里有默认值、配置文件里也有"的双份维护。
 */
@Validated
@ConfigurationProperties(prefix = "shortlink")
public record ShortLinkProperties(

        /** 短链的对外基础地址，用于拼接返回给客户端的完整短链。 */
        @NotBlank
        String baseUrl,

        @Valid
        @NotNull
        Code code,

        @Valid
        @NotNull
        Cache cache,

        @Valid
        @NotNull
        RateLimit rateLimit,

        @Valid
        @NotNull
        Stats stats

) {

    /**
     * 短码生成配置。
     *
     * @param secret 置换密钥。参与 Feistel 轮密钥的派生，
     *               决定 {@code 序列号 → 短码} 的映射关系。
     *               <p><b>密钥必须稳定且保密。</b>更换密钥不会使已有短链失效
     *               （短链按 code 存储与查询，从不反向解码），但会改变新短码的映射，
     *               理论上存在新短码与历史短码碰撞的极小概率。
     * @param length 短码长度，6 位对应 568 亿容量
     */
    public record Code(
            @NotBlank
            String secret,

            @Min(4)
            @Max(10)
            int length
    ) {
    }

    /**
     * 跳转缓存配置。
     *
     * @param ttl         正常缓存的存活时间
     * @param nullTtl     空值缓存（防穿透）的存活时间。显著短于 {@code ttl}，
     *                    因为过长的空值缓存会让"刚创建就访问"的短链误报 404
     * @param jitterRatio TTL 随机抖动比例，取值 {@code [0, 0.5]}。
     *                    实际 TTL 为 {@code ttl × (1 ± jitterRatio)}，用于防缓存雪崩
     */
    public record Cache(
            @NotNull
            Duration ttl,

            @NotNull
            Duration nullTtl,

            @DecimalMin("0.0")
            @DecimalMax("0.5")
            double jitterRatio
    ) {
    }

    /**
     * 限流配置。
     *
     * @param redirectPerMinutePerIp    单 IP 每分钟允许的跳转次数。
     *                                  阈值较高，因为正常用户浏览行为会连续产生跳转请求
     * @param createPerMinutePerUser    单用户每分钟允许创建短链的次数。
     *                                  阈值较低，创建是低频写操作
     */
    public record RateLimit(
            @Positive
            int redirectPerMinutePerIp,

            @Positive
            int createPerMinutePerUser
    ) {
    }

    /**
     * 访问统计配置。
     *
     * @param enabled            是否启用统计刷库任务。
     *                           <p>这个开关存在的意义不只是"关掉功能"：它是为
     *                           <b>多实例部署</b>准备的。当前 {@code @Scheduled} 任务在
     *                           多实例下会重复执行（虽然 PV 的原子取出保证了不会翻倍，
     *                           但仍是无效开销）。引入分布式锁之前，可以通过指向一个
     *                           专用实例（{@code enabled=true}）而其余实例关闭来规避。
     * @param flushIntervalMillis 刷库周期（毫秒）。它决定了统计数据"最多滞后多久"，
     *                            也就是用户在后台看到的数据可能有多旧。
     *                            <p>用毫秒数而不是 {@code Duration}：这个值需要被
     *                            {@code @Scheduled(fixedDelayString = ...)} 直接引用，
     *                            而不同 Spring 版本对 {@code Duration} 字符串
     *                            （{@code "60s"} / {@code "PT60S"}）的支持程度不一致。
     *                            用一个纯数字消除了这个版本差异风险，源也只有一个。
     * @param defaultQueryDays    统计查询的默认天数（未传 {@code days} 参数时）
     * @param maxQueryDays        统计查询的最大天数。上限防止一次查询拉出过长时间区间
     */
    public record Stats(
            boolean enabled,

            @Positive
            long flushIntervalMillis,

            @Positive
            int defaultQueryDays,

            @Positive
            int maxQueryDays
    ) {
    }
}
