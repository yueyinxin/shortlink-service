package io.github.yueyinxin.shortlink.common.validation;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link HttpUrlValidator} 的单元测试。
 *
 * <p>这组测试守护的是一条<b>安全控制</b>，而不是普通的格式校验：短链服务会把
 * 存储的地址写入 {@code Location} 响应头，浏览器随即跳转过去。若允许非 http(s) 协议，
 * 攻击者可以创建指向 {@code javascript:} 的短链 —— 代码会在<b>本服务的域名下</b>执行，
 * 让服务成为 XSS 与钓鱼的跳板。
 *
 * <p>因此下面的"拒绝"用例比"接受"用例更重要。任何放宽协议白名单的改动
 * 都应当让这里出现一片红色。
 */
class HttpUrlValidatorTest {

    /**
     * 通过真实的 Bean Validation 工厂校验，而不是直接 {@code new HttpUrlValidator()}。
     *
     * <h2>为什么这一点很重要</h2>
     * 直接实例化校验器会<b>跳过 {@code initialize(annotation)} 回调</b>，
     * 于是注解上的配置值（如 {@code maxLength}）得不到传递，字段保持 Java 默认值。
     * 本测试最初就是直接实例化的：{@code maxLength} 停留在 {@code 0}，
     * 导致<b>每一个非空 URL 都因长度检查而失败</b> —— 表面上有 18 个用例在跑，
     * 实际上"接受合法 URL"的断言全部是假通过，测试没有验证任何东西。
     *
     * <p>通过工厂校验还顺带覆盖了"注解声明与实际校验器是否配对正确"这一层 ——
     * 如果 {@code @Constraint(validatedBy = ...)} 写错了类名，直接实例化的测试
     * 永远不会发现。
     */
    private static final jakarta.validation.Validator VALIDATOR =
            jakarta.validation.Validation.buildDefaultValidatorFactory().getValidator();

    /** 承载待校验值的载体。用 record 让校验目标与请求 DTO 的形态一致。 */
    private record Sample(@HttpUrl String url) {
    }

    private boolean accepts(String value) {
        return VALIDATOR.validate(new Sample(value)).isEmpty();
    }

    @Nested
    @DisplayName("协议白名单 —— 安全控制点")
    class SchemeAllowList {

        @ParameterizedTest
        @ValueSource(strings = {
                "javascript:alert(document.cookie)",
                "JavaScript:alert(1)",
                "data:text/html;base64,PHNjcmlwdD5hbGVydCgxKTwvc2NyaXB0Pg==",
                "vbscript:msgbox(1)",
                "file:///etc/passwd",
                "ftp://example.com/file.txt",
                "chrome://settings",
                "about:blank",
        })
        @DisplayName("拒绝一切非 http(s) 协议的地址")
        void rejectsDangerousSchemes(String dangerous) {
            assertThat(accepts(dangerous))
                    .as("危险协议必须被拒绝: %s", dangerous)
                    .isFalse();
        }

        @Test
        @DisplayName("接受 http 与 https")
        void acceptsHttpSchemes() {
            assertThat(accepts("http://example.com")).isTrue();
            assertThat(accepts("https://example.com")).isTrue();
            assertThat(accepts("HTTPS://EXAMPLE.COM")).isTrue();
            assertThat(accepts("https://example.com/path?a=1&b=2#frag")).isTrue();
        }
    }

    @Nested
    @DisplayName("反钓鱼加固")
    class AntiPhishing {

        /**
         * {@code https://www.bank.com@evil.example/} 这类地址中，{@code @} 之前的部分是
         * <b>凭证</b>而不是主机名，真实目标是 {@code evil.example}。
         * 用户扫一眼很容易误认为这是银行域名 —— 这是经典的钓鱼手法。
         */
        @Test
        @DisplayName("拒绝内嵌凭证的地址（视觉欺骗型钓鱼）")
        void rejectsEmbeddedCredentials() {
            assertThat(accepts("https://www.bank.com@evil.example/")).isFalse();
            assertThat(accepts("https://user:pass@example.com/")).isFalse();
        }
    }

    @Nested
    @DisplayName("结构合法性")
    class Structure {

        @ParameterizedTest
        @ValueSource(strings = {
                "http://",
                "http:///path",
                "/relative/path",
                "example.com",
                "http://exa mple.com",
                "http://example.com/\nX-Injected: value",
        })
        @DisplayName("拒绝缺少主机、相对路径或含非法字符的地址")
        void rejectsMalformed(String malformed) {
            assertThat(accepts(malformed))
                    .as("非法地址必须被拒绝: %s", malformed)
                    .isFalse();
        }

        @Test
        @DisplayName("拒绝首尾带空白的地址")
        void rejectsSurroundingWhitespace() {
            // URI 构造器会静默容忍首尾空白，若放行会产生"看起来合法、
            // 跳转后 404"的短链，让用户困惑
            assertThat(accepts(" https://example.com")).isFalse();
            assertThat(accepts("https://example.com ")).isFalse();
        }

        @Test
        @DisplayName("拒绝超过长度上限的地址")
        void rejectsTooLong() {
            String longUrl = "https://example.com/" + "a".repeat(3000);
            assertThat(accepts(longUrl)).isFalse();
        }

        @Test
        @DisplayName("接受恰好达到长度上限的地址")
        void acceptsExactlyAtLimit() {
            String prefix = "https://example.com/";
            String urlAtLimit = prefix + "a".repeat(2048 - prefix.length());
            assertThat(urlAtLimit).hasSize(2048);
            assertThat(accepts(urlAtLimit)).isTrue();
        }
    }

    @Nested
    @DisplayName("空值处理")
    class NullHandling {

        /**
         * 空值必须<b>放行</b>，由 {@code @NotBlank} 负责拒绝。
         * 若校验器也拒绝空值，同一个字段会同时产生两条错误信息 ——
         * 前端展示时会出现重复提示，且难以判断哪条是真正的原因。
         */
        @Test
        @DisplayName("null 与空白放行，交由 @NotBlank 处理")
        void allowsNullAndBlank() {
            assertThat(accepts(null)).isTrue();
            assertThat(accepts("")).isTrue();
            assertThat(accepts("   ")).isTrue();
        }
    }
}
