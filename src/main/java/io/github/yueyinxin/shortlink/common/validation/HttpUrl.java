package io.github.yueyinxin.shortlink.common.validation;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 校验一个字符串是可用于跳转的安全绝对 URL。
 *
 * <h2>这是一条安全约束，不是格式约束</h2>
 * 短链服务的核心行为是"把访问者重定向到用户提供的地址"。如果不限制协议，
 * 攻击者可以创建指向 {@code javascript:alert(document.cookie)} 的短链并投放到任何地方。
 * 由于跳转发生在<b>本服务的域名下</b>，浏览器会认为这是可信来源 ——
 * 这让本服务变成 XSS 与钓鱼的跳板，且受害者看到的域名是我们的。
 *
 * <p>因此，协议白名单（仅 {@code http} / {@code https}）是本注解存在的<b>首要原因</b>，
 * 格式合法性只是附带检查。
 *
 * <p>放宽本约束前请确认：是否需要把服务变成任意协议的跳板。
 */
@Documented
@Constraint(validatedBy = HttpUrlValidator.class)
@Target({ElementType.FIELD, ElementType.METHOD, ElementType.PARAMETER,
        ElementType.ANNOTATION_TYPE, ElementType.CONSTRUCTOR, ElementType.RECORD_COMPONENT})
@Retention(RetentionPolicy.RUNTIME)
public @interface HttpUrl {

    String message() default "链接格式不合法，仅支持 http 与 https 协议";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};

    /** 允许的最大长度。默认与数据库列宽一致，避免超长 URL 通过校验后在入库时失败。 */
    int maxLength() default 2048;
}
