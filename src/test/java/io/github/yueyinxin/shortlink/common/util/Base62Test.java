package io.github.yueyinxin.shortlink.common.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link Base62} 的单元测试。
 *
 * <p>编解码的往返一致性是短码系统的基础：编码出错会导致短码与序列号的对应关系错乱，
 * 而这类问题在排查时表现为"用户拿到的链接打不开"，很难直接定位到编解码层。
 */
class Base62Test {

    @Test
    @DisplayName("编解码往返一致")
    void roundTrip() {
        for (long value = 0; value < 100_000; value++) {
            String encoded = Base62.encode(value, 0);
            assertThat(Base62.decode(encoded))
                    .as("值 %d 往返后不相等", value)
                    .isEqualTo(value);
        }
    }

    @Test
    @DisplayName("大数值往返一致")
    void roundTripLargeValues() {
        long[] values = {
                1L,
                61L,
                62L,
                3_844L,
                238_328L,
                56_800_235_583L,        // 62^6 - 1，默认短码空间的上界
                3_521_614_606_207L,     // 62^7 - 1
                Long.MAX_VALUE,
        };
        for (long value : values) {
            assertThat(Base62.decode(Base62.encode(value, 0)))
                    .as("值 %d 往返后不相等", value)
                    .isEqualTo(value);
        }
    }

    @ParameterizedTest
    @CsvSource({
            "0,      '0'",
            "1,      '1'",
            "9,      '9'",
            "10,     'A'",
            "35,     'Z'",
            "36,     'a'",
            "61,     'z'",
            "62,     '10'",
            "3843,   'zz'",
            "3844,   '100'",
    })
    @DisplayName("已知数值编码为预期字符串")
    void knownEncodings(long value, String expected) {
        assertThat(Base62.encode(value, 0)).isEqualTo(expected);
    }

    @Test
    @DisplayName("左侧补零到指定宽度")
    void padsToMinimumWidth() {
        assertThat(Base62.encode(1, 6)).isEqualTo("000001");
        assertThat(Base62.encode(0, 6)).isEqualTo("000000");
        assertThat(Base62.encode(61, 6)).isEqualTo("00000z");
        // 已经超过最小宽度时不截断
        assertThat(Base62.encode(3844, 2)).isEqualTo("100");
    }

    @Test
    @DisplayName("字母表为 0-9 后接 A-Z 再 a-z，共 62 个字符")
    void alphabetIsWellFormed() {
        assertThat(Base62.BASE).isEqualTo(62);
        // 逐字符验证：索引 i 的字符编码后应得到 i
        for (int i = 0; i < 62; i++) {
            String encoded = Base62.encode(i, 0);
            assertThat(encoded).hasSize(1);
            assertThat(Base62.decode(encoded)).isEqualTo(i);
            assertThat(Base62.isBase62Char(encoded.charAt(0))).isTrue();
        }
    }

    @Test
    @DisplayName("拒绝负数编码")
    void rejectsNegativeValue() {
        assertThatThrownBy(() -> Base62.encode(-1, 0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("不能为负");
    }

    @Test
    @DisplayName("拒绝负数最小宽度")
    void rejectsNegativeWidth() {
        assertThatThrownBy(() -> Base62.encode(1, -1))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"a b", "abc!", "中文", "a-b", "a_b", "a.b", "a/b", "１２３"})
    @DisplayName("识别非法字符")
    void rejectsInvalidCharacters(String invalid) {
        // 短码只允许 0-9A-Za-z；连字符、下划线、全角字符都不合法
        assertThat(Base62.isBase62(invalid))
                .as("%s 不应被识别为合法 Base62", invalid)
                .isFalse();
    }

    @Test
    @DisplayName("isBase62 对 null 与空串返回 false")
    void isBase62RejectsNullAndEmpty() {
        assertThat(Base62.isBase62(null)).isFalse();
        assertThat(Base62.isBase62("")).isFalse();
    }

    @Test
    @DisplayName("解码非法字符时抛出异常并指出位置")
    void decodeReportsIllegalCharacter() {
        assertThatThrownBy(() -> Base62.decode("ab-c"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("位置 2");
    }

    @Test
    @DisplayName("解码超长字符串时报告溢出而不是静默返回错误值")
    void decodeDetectsOverflow() {
        // 20 个 'z' 远超 long 的范围。静默溢出会返回一个看似正常但错误的值，
        // 那比报错更危险 —— 它会变成一条指向错误短链的跳转。
        assertThatThrownBy(() -> Base62.decode("z".repeat(20)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("超出 long 范围");
    }

    @Test
    @DisplayName("拒绝空字符串解码")
    void decodeRejectsEmpty() {
        assertThatThrownBy(() -> Base62.decode(""))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Base62.decode(null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("随机串长度正确且全部由合法字符组成")
    void randomStringIsWellFormed() {
        for (int length : new int[]{1, 9, 16}) {
            String random = Base62.randomString(length);
            assertThat(random).hasSize(length);
            assertThat(Base62.isBase62(random)).isTrue();
        }
    }

    @Test
    @DisplayName("随机串拒绝了非法长度")
    void randomStringRejectsInvalidLength() {
        assertThatThrownBy(() -> Base62.randomString(0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Base62.randomString(-1))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
