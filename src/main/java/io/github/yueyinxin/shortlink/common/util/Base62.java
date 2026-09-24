package io.github.yueyinxin.shortlink.common.util;

/**
 * Base62 编解码。
 *
 * <p>字母表为 {@code 0-9A-Za-z}，索引 0..61。选择这个顺序而非 {@code A-Za-z0-9} 的原因是
 * 它与十六进制习惯一致（数字在前），且编码后的字符串在字典序上与数值序一致，
 * 便于排查问题时手工比对。
 *
 * <p>本类只做编解码，不涉及短码的业务规则（长度限制、保留字等）—— 那些属于
 * {@code ShortCode} 值对象的职责。保持工具类纯粹，才能被独立测试。
 *
 * <p>实例不可变且线程安全（无状态），所有方法均为静态。
 */
public final class Base62 {

    private static final char[] ALPHABET =
            "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz".toCharArray();

    /** 反向查找表：ASCII 字符 → 数值，非 Base62 字符为 -1。 */
    private static final int[] LOOKUP = new int[128];

    static {
        java.util.Arrays.fill(LOOKUP, -1);
        for (int i = 0; i < ALPHABET.length; i++) {
            LOOKUP[ALPHABET[i]] = i;
        }
    }

    public static final int BASE = ALPHABET.length;

    private Base62() {
        throw new AssertionError("工具类不应被实例化");
    }

    /**
     * 将非负数编码为 Base62 字符串。
     *
     * @param value    待编码的值，必须 &ge; 0
     * @param minWidth 最小宽度，不足时左侧补 {@code '0'}。传 0 表示不补位。
     * @return 编码结果
     */
    public static String encode(long value, int minWidth) {
        if (value < 0) {
            throw new IllegalArgumentException("待编码的值不能为负: " + value);
        }
        if (minWidth < 0) {
            throw new IllegalArgumentException("最小宽度不能为负: " + minWidth);
        }
        if (value == 0) {
            return "0".repeat(Math.max(minWidth, 1));
        }

        // long 最多需要 11 个 Base62 字符（62^11 > 2^63），64 字节缓冲区足够且无需扩容
        char[] buffer = new char[64];
        int position = buffer.length;
        long remaining = value;
        while (remaining > 0) {
            buffer[--position] = ALPHABET[(int) (remaining % BASE)];
            remaining /= BASE;
        }

        String encoded = new String(buffer, position, buffer.length - position);
        if (encoded.length() >= minWidth) {
            return encoded;
        }
        return "0".repeat(minWidth - encoded.length()) + encoded;
    }

    /**
     * 将 Base62 字符串解码为非负数。
     *
     * @throws IllegalArgumentException 含非法字符，或数值超出 {@code long} 范围
     */
    public static long decode(String value) {
        if (value == null || value.isEmpty()) {
            throw new IllegalArgumentException("待解码的字符串不能为空");
        }

        long result = 0L;
        for (int i = 0; i < value.length(); i++) {
            int digit = digitOf(value.charAt(i));
            if (digit < 0) {
                throw new IllegalArgumentException(
                        "非法 Base62 字符 '" + value.charAt(i) + "'，位置 " + i);
            }
            try {
                // 使用精确运算而非静默溢出：溢出说明输入不是一个合法的短码，
                // 应当明确报错，而不是返回一个看起来正常但错误的值。
                result = Math.addExact(Math.multiplyExact(result, BASE), digit);
            } catch (ArithmeticException e) {
                throw new IllegalArgumentException("Base62 数值超出 long 范围: " + value, e);
            }
        }
        return result;
    }

    /** 判断单字符是否为合法 Base62 字符。 */
    public static boolean isBase62Char(char c) {
        return c < 128 && LOOKUP[c] >= 0;
    }

    /**
     * 生成指定长度的随机 Base62 字符串。
     *
     * <p>使用 {@link java.util.concurrent.ThreadLocalRandom}：它是线程安全的，
     * 且避免了多线程下共享 {@code Random} 实例产生的竞争。
     *
     * <p>本方法用于生成<b>临时占位值</b>（见 {@code ShortLink#placeholderCode()}），
     * 不用于生成短码本身 —— 短码必须由 {@code ShortCodeGenerator} 的双射变换产生，
     * 才能保证无冲突。若用于短码，就会退化成"随机生成 + 冲突重试"方案。
     *
     * @param length 输出长度，必须 &ge; 1
     */
    public static String randomString(int length) {
        if (length < 1) {
            throw new IllegalArgumentException("长度必须大于 0: " + length);
        }
        java.util.concurrent.ThreadLocalRandom random = java.util.concurrent.ThreadLocalRandom.current();
        char[] buffer = new char[length];
        for (int i = 0; i < length; i++) {
            buffer[i] = ALPHABET[random.nextInt(BASE)];
        }
        return new String(buffer);
    }

    /** 判断整个字符串是否只由 Base62 字符组成（空串返回 {@code false}）。 */
    public static boolean isBase62(String value) {
        if (value == null || value.isEmpty()) {
            return false;
        }
        for (int i = 0; i < value.length(); i++) {
            if (!isBase62Char(value.charAt(i))) {
                return false;
            }
        }
        return true;
    }

    private static int digitOf(char c) {
        return c < 128 ? LOOKUP[c] : -1;
    }
}
