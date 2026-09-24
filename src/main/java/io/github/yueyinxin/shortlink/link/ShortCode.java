package io.github.yueyinxin.shortlink.link;

import java.util.regex.Pattern;

/**
 * 短码值对象。
 *
 * <p>构造即校验：只要拿到一个 {@code ShortCode} 实例，就可以确信它的格式是合法的。
 * 这避免了"在多层之间反复判断 code 是否合法"的传染性代码 —— 校验只做一次，在边界处。
 *
 * <p>注意这里区分两类规则：
 * <ul>
 *   <li><b>格式规则</b>（长度、字符集）—— 在构造函数中强制，非法即抛异常。这是本类的职责。</li>
 *   <li><b>保留字规则</b> —— 通过 {@link #isReserved()} 暴露为查询，不阻断构造。
 *       原因是保留字集合会随路由变化（新增一个接口路径就可能需要新增保留字），
 *       把它塞进构造函数会让值对象的语义变得依赖运行时配置。是否拒绝由调用方决定。</li>
 * </ul>
 */
public record ShortCode(String value) {

    public static final int MIN_LENGTH = 4;
    public static final int MAX_LENGTH = 10;

    private static final Pattern ALLOWED_CHARS = Pattern.compile("^[0-9A-Za-z]+$");

    public ShortCode {
        if (value == null) {
            throw new IllegalArgumentException("短码不能为 null");
        }
        if (value.length() < MIN_LENGTH || value.length() > MAX_LENGTH) {
            throw new IllegalArgumentException(
                    "短码长度必须在 %d 到 %d 之间，实际为 %d".formatted(MIN_LENGTH, MAX_LENGTH, value.length()));
        }
        if (!ALLOWED_CHARS.matcher(value).matches()) {
            // 不回显非法的短码内容本身：它可能来自不可信输入，回显会让日志被注入污染
            throw new IllegalArgumentException("短码只能包含字母与数字");
        }
    }

    /**
     * 校验并创建。与构造函数等价，用于语义更清晰的调用点。
     *
     * @throws IllegalArgumentException 格式非法
     */
    public static ShortCode of(String raw) {
        return new ShortCode(raw);
    }

    /**
     * 判断是否与系统保留路径冲突。
     *
     * <p>大小写不敏感：不同 Web 容器与反向代理对路径大小写的处理不完全一致，
     * 只拦截小写的保留字等于留下一个大小写变体的绕过口子。
     */
    public boolean isReserved() {
        return ReservedCodes.isReserved(value);
    }

    /** 校验是否为合法短码格式，不抛异常。用于需要"判断"而非"断言"的场景。 */
    public static boolean isValidFormat(String raw) {
        return raw != null
                && raw.length() >= MIN_LENGTH
                && raw.length() <= MAX_LENGTH
                && ALLOWED_CHARS.matcher(raw).matches();
    }

    @Override
    public String toString() {
        return value;
    }
}
