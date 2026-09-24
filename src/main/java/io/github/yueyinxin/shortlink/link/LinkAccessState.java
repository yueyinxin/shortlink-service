package io.github.yueyinxin.shortlink.link;

import io.github.yueyinxin.shortlink.common.exception.ErrorCode;

/**
 * 短链的访问判定结果。
 *
 * <p>这是领域层对"这次访问应该怎么响应"的完整回答。把判定结果做成一个类型而不是
 * 让调用方去查 {@code status} 和 {@code expiresAt} 两个字段，有两个好处：
 *
 * <ol>
 *   <li><b>判定逻辑只有一份</b>。如果调用方各自判断
 *       {@code if (status == DISABLED) ... else if (expiresAt.before(now)) ...}，
 *       那么两处代码的先后顺序、边界条件（{@code before} 还是 {@code !isAfter}）
 *       迟早会不一致，产生"列表显示正常、点击却报过期"这类难以排查的问题。</li>
 *   <li><b>每个结果自带对应的错误码</b>。调用方不需要再做一次 code → HTTP 状态的映射。</li>
 * </ol>
 */
public enum LinkAccessState {

    /** 可以跳转。 */
    ACCESSIBLE(null, null),

    /** 已被禁用 —— 人的操作。 */
    DISABLED(ErrorCode.LINK_DISABLED, "短链已被禁用"),

    /** 已过期 —— 时间流逝的结果。 */
    EXPIRED(ErrorCode.LINK_EXPIRED, "短链已过期");

    private final ErrorCode errorCode;
    private final String reason;

    LinkAccessState(ErrorCode errorCode, String reason) {
        this.errorCode = errorCode;
        this.reason = reason;
    }

    public boolean isAccessible() {
        return this == ACCESSIBLE;
    }

    /** 不可访问时对应的错误码；可访问时为 {@code null}。 */
    public ErrorCode errorCode() {
        return errorCode;
    }

    /** 不可访问的原因描述；可访问时为 {@code null}。 */
    public String reason() {
        return reason;
    }
}
