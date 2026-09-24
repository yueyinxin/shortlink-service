package io.github.yueyinxin.shortlink.link;

import io.github.yueyinxin.shortlink.common.util.Base62;
import io.github.yueyinxin.shortlink.config.properties.ShortLinkProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Duration;
import java.util.HashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link ShortCodeGenerator} 的单元测试。
 *
 * <p>这是全项目最重要的单元测试：短码生成的正确性是"无冲突"这一硬性要求的基础，
 * 一旦有缺陷会导致插入失败或数据错乱，且难以在生产环境中复现。
 *
 * <p>测试分为三层：
 * <ol>
 *   <li><b>双射性</b>——在小空间上穷举验证，这是对数学性质的直接证明；</li>
 *   <li><b>不可枚举性</b>——把"相邻输出差值恒为常数"这个已否决方案的缺陷固化为回归测试；</li>
 *   <li><b>确定性与格式</b>——长度、字符集、越界、可重现性。</li>
 * </ol>
 */
class ShortCodeGeneratorTest {

    private static final String SECRET = "test-secret-do-not-use-in-production";

    private static ShortCodeGenerator generator(int length) {
        return generator(length, SECRET);
    }

    private static ShortCodeGenerator generator(int length, String secret) {
        ShortLinkProperties properties = new ShortLinkProperties(
                "http://localhost:8080",
                new ShortLinkProperties.Code(secret, length),
                new ShortLinkProperties.Cache(Duration.ofHours(24), Duration.ofSeconds(60), 0.1),
                new ShortLinkProperties.RateLimit(300, 30),
                new ShortLinkProperties.Stats(true, 60_000L, 7, 90));
        return new ShortCodeGenerator(properties);
    }

    private static long numeric(String code) {
        return Base62.decode(code);
    }

    @Nested
    @DisplayName("双射性 —— 无冲突的数学保证")
    class Bijectivity {

        /**
         * 在小空间上<b>穷举</b>验证双射性。
         *
         * <p>4 位短码的空间为 {@code 62^4 = 14,776,336}，可以完整遍历。这比"抽样检查不重复"
         * 强得多：它证明了映射确实是整个空间上的置换，不存在任何被重复覆盖或从未被覆盖的短码。
         *
         * <p>如果生成器换成了任何非双射的实现（例如"随机 + 查重"），这条测试会失败。
         */
        @Test
        @DisplayName("4 位空间穷举 14,776,336 个序列号：短码全部唯一且无遗漏")
        void exhaustiveOverSmallSpace() {
            ShortCodeGenerator generator = generator(4);
            long space = generator.space();
            assertThat(space).isEqualTo(14_776_336L);

            boolean[] covered = new boolean[(int) space];
            for (long sequence = 0; sequence < space; sequence++) {
                long mapped = numeric(generator.generate(sequence));

                assertThat(mapped)
                        .as("序列号 %d 映射到了有效区间之外", sequence)
                        .isBetween(0L, space - 1);

                assertThat(covered[(int) mapped])
                        .as("序列号 %d 与之前的某个序列号映射到了同一位置", sequence)
                        .isFalse();
                covered[(int) mapped] = true;
            }

            for (int position = 0; position < covered.length; position++) {
                assertThat(covered[position])
                        .as("短码空间位置 %d 从未被映射到，说明存在遗漏", position)
                        .isTrue();
            }
        }

        @Test
        @DisplayName("6 位空间：连续 100 万个序列号无重复")
        void noCollisionOverMillionSequences() {
            ShortCodeGenerator generator = generator(6);
            Set<String> codes = new HashSet<>(2_000_000);

            for (long sequence = 0; sequence < 1_000_000; sequence++) {
                assertThat(codes.add(generator.generate(sequence)))
                        .as("序列号 %d 产生了重复短码", sequence)
                        .isTrue();
            }
            assertThat(codes).hasSize(1_000_000);
        }

        @Test
        @DisplayName("6 位空间：首尾边界序列号可用且互不相同")
        void boundarySequences() {
            ShortCodeGenerator generator = generator(6);
            long space = generator.space();

            assertThat(generator.generate(0)).hasSize(6);
            assertThat(generator.generate(space - 1)).hasSize(6);
            assertThat(generator.generate(0)).isNotEqualTo(generator.generate(space - 1));
        }
    }

    @Nested
    @DisplayName("不可枚举性")
    class NonEnumerability {

        /**
         * 回归测试：相邻短码的数值差<b>不能</b>是常数。
         *
         * <p>这条测试守护的是一个曾经被否决的方案。最初的实现使用线性同余映射
         * {@code (seq × A + B) mod M}，它的相邻输出差值恒为 {@code A}，攻击者连续创建
         * 几个短链、把短码解码后相邻相减即可解出参数，进而还原整个映射并枚举全站短码。
         *
         * <p>如果把生成器换回任何形式的线性变换，这条测试会立刻失败。
         */
        @Test
        @DisplayName("相邻短码的数值差不是常数（守护已否决的线性同余方案）")
        void adjacentDifferencesAreNotConstant() {
            ShortCodeGenerator generator = generator(6);
            long space = generator.space();

            int sampleSize = 1_000;
            Set<Long> distinctDifferences = new HashSet<>(sampleSize);

            long previous = numeric(generator.generate(0));
            for (long sequence = 1; sequence < sampleSize; sequence++) {
                long current = numeric(generator.generate(sequence));
                // 使用模空间上的环距离：cycle-walking 会让映射在空间内"绕行"，
                // 直接相减会出现负数，规范化后才是可直接比较的差值
                distinctDifferences.add(Math.floorMod(current - previous, space));
                previous = current;
            }

            // 线性同余方案下这里只会得到 1 个不同的值
            assertThat(distinctDifferences)
                    .as("相邻短码差值大量重复，映射可能退化为线性变换")
                    .hasSizeGreaterThan(sampleSize / 2);
        }

        @Test
        @DisplayName("相邻短码的数值跳跃幅度分散，不呈单调或等差")
        void jumpsAreSpreadOut() {
            ShortCodeGenerator generator = generator(6);

            long previous = numeric(generator.generate(0));
            int increasing = 0;
            int decreasing = 0;
            for (long sequence = 1; sequence < 2_000; sequence++) {
                long current = numeric(generator.generate(sequence));
                if (current > previous) {
                    increasing++;
                } else {
                    decreasing++;
                }
                previous = current;
            }

            // 单调递增（如自增 ID 直接编码）会让其中一侧为 0
            assertThat(increasing).isGreaterThan(400);
            assertThat(decreasing).isGreaterThan(400);
        }

        @Test
        @DisplayName("更换密钥后短码映射几乎完全不同")
        void differentSecretsProduceDifferentMappings() {
            ShortCodeGenerator alpha = generator(6, "secret-alpha");
            ShortCodeGenerator beta = generator(6, "secret-beta");

            long differences = 0;
            for (long sequence = 0; sequence < 200; sequence++) {
                if (!alpha.generate(sequence).equals(beta.generate(sequence))) {
                    differences++;
                }
            }

            assertThat(differences).isGreaterThan(190);
        }
    }

    @Nested
    @DisplayName("确定性与格式")
    class DeterminismAndFormat {

        @Test
        @DisplayName("同一序列号在任何实例上都产生相同短码")
        void deterministic() {
            ShortCodeGenerator first = generator(6);
            ShortCodeGenerator second = generator(6);

            for (long sequence = 0; sequence < 1_000; sequence++) {
                assertThat(first.generate(sequence)).isEqualTo(second.generate(sequence));
            }
        }

        @ParameterizedTest
        @ValueSource(ints = {4, 5, 6, 7, 8})
        @DisplayName("所有合法配置长度下，短码长度与字符集均符合约定")
        void formatForAllConfiguredLengths(int length) {
            ShortCodeGenerator generator = generator(length);

            assertThat(generator.codeLength()).isEqualTo(length);
            for (long sequence = 0; sequence < 500; sequence++) {
                String code = generator.generate(sequence);
                assertThat(code).hasSize(length);
                assertThat(Base62.isBase62(code)).isTrue();
            }
        }

        @Test
        @DisplayName("7 位短码（42 位分组）无冲突 —— 验证位宽推导逻辑")
        void lengthSevenUsesWiderBlock() {
            // 62^7 ≈ 3.52e12，需要 42 位分组。若位宽推导有误，
            // cycle-walking 会因空间不足而抛异常或产生冲突。
            ShortCodeGenerator generator = generator(7);

            Set<String> codes = new HashSet<>();
            for (long sequence = 0; sequence < 200_000; sequence++) {
                assertThat(codes.add(generator.generate(sequence)))
                        .as("序列号 %d 产生重复", sequence)
                        .isTrue();
            }
        }

        @Test
        @DisplayName("序列号越界时抛出明确异常")
        void rejectsOutOfRangeSequence() {
            ShortCodeGenerator generator = generator(6);

            assertThatThrownBy(() -> generator.generate(-1))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("超出短码空间");

            assertThatThrownBy(() -> generator.generate(generator.space()))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("超出短码空间");
        }
    }
}
