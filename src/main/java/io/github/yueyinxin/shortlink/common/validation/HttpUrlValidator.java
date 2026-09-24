package io.github.yueyinxin.shortlink.common.validation;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;
import java.util.Set;

/**
 * {@link HttpUrl} 的校验实现。
 *
 * <p>校验顺序按"从最宽松到最严格"排列，且每一步都短路返回，避免无谓的解析开销：
 * <ol>
 *   <li>空值放行 —— 是否允许为空由 {@code @NotBlank} 决定，本校验器只负责"若存在则必须合法"。
 *       把两者混在一起会导致同一个字段出现两条重复的错误信息。</li>
 *   <li>长度上限 —— 先于解析检查，避免把超长字符串送进 URI 解析器。</li>
 *   <li>协议白名单 —— <b>安全控制点</b>。</li>
 *   <li>主机存在性 —— 拒绝 {@code http:///path} 这类没有主机的地址。</li>
 *   <li>禁止内嵌凭证 —— 反钓鱼加固。</li>
 * </ol>
 */
public class HttpUrlValidator implements ConstraintValidator<HttpUrl, String> {

    private static final Set<String> ALLOWED_SCHEMES = Set.of("http", "https");

    private int maxLength;

    @Override
    public void initialize(HttpUrl annotation) {
        this.maxLength = annotation.maxLength();
    }

    @Override
    public boolean isValid(String value, ConstraintValidatorContext context) {
        // 空值交给 @NotBlank 处理，避免重复报错
        if (value == null || value.isBlank()) {
            return true;
        }

        if (value.length() > maxLength) {
            return false;
        }

        // 含空白或控制字符的地址不是合法 URL。URI 构造器会拒绝大部分此类输入，
        // 但前后空白会被静默容忍，这里显式拒绝以免产生"看起来合法、跳转后 404"的短链。
        if (!value.equals(value.strip())) {
            return false;
        }

        URI uri = parse(value);
        if (uri == null) {
            return false;
        }

        // ---- 安全控制点：协议白名单 ----
        // 拒绝 javascript: / data: / vbscript: / file: 等一切非 http(s) 协议。
        // 少了这一步，本服务就成了 XSS 与钓鱼的跳板。
        String scheme = uri.getScheme();
        if (scheme == null || !ALLOWED_SCHEMES.contains(scheme.toLowerCase(Locale.ROOT))) {
            return false;
        }

        // 必须是绝对地址：没有主机的 http:///path 无法跳转
        String host = uri.getHost();
        if (host == null || host.isBlank()) {
            return false;
        }

        // ---- 反钓鱼加固：禁止内嵌凭证 ----
        // https://www.bank.com@evil.example/ 这类地址中，@ 之前的部分是凭证而非主机，
        // 真实目标是 evil.example。用户扫一眼很容易误认为是银行域名。
        // 短链服务没有任何正当理由需要创建这种跳转。
        if (uri.getUserInfo() != null) {
            return false;
        }

        return true;
    }

    private static URI parse(String value) {
        try {
            return new URI(value);
        } catch (URISyntaxException e) {
            return null;
        }
    }
}
