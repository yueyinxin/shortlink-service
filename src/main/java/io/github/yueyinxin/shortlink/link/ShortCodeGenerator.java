package io.github.yueyinxin.shortlink.link;

import io.github.yueyinxin.shortlink.common.util.Base62;
import io.github.yueyinxin.shortlink.config.properties.ShortLinkProperties;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;

/**
 * 短码生成器：把数据库自增序列号映射为不可枚举的固定长度短码。
 *
 * <h2>映射链路</h2>
 * <pre>
 *   自增序列 seq ∈ [0, 62^length)
 *        │
 *        ▼  Feistel 网络（36 位分组、8 轮、密钥化轮函数）
 *   permuted ∈ [0, 2^blockBits)
 *        │
 *        ▼  Cycle-Walking：若 permuted ≥ 62^length 则再次置换，直到落入有效区间
 *   mapped ∈ [0, 62^length)
 *        │
 *        ▼  Base62 编码并左侧补零
 *   短码 "aB3x9K"
 * </pre>
 *
 * <h2>为什么是 Feistel 而不是简单的线性变换</h2>
 * 线性同余映射 {@code (seq × A + B) mod M} 同样能构造双射，实现只要一行。但它的相邻输出
 * 差值恒为 {@code A}：攻击者连续创建几个短链，把短码解码后相邻相减即可解出 {@code A} 与 {@code B}，
 * 从而还原整个映射并枚举全站短码。<b>"看起来乱"不等于"不可枚举"。</b>
 *
 * <p>Feistel 网络的安全性来自迭代结构本身：即使轮函数 {@code F} 完全公开且不可逆，
 * 8 轮的交替变换也使得从输出反推输入需要求解一个难以处理的方程组。密钥参与轮函数，
 * 不公开。详见 {@code docs/adr/0002-short-code-generation.md}。
 *
 * <h2>为什么无冲突</h2>
 * Feistel 结构的一个关键性质是：只要每轮的左右交换被保留，整个网络<b>必然是双射</b>，
 * 与轮函数 {@code F} 的具体形式无关。Cycle-Walking 把双射限制在子集
 * {@code [0, 62^length)} 上，得到的仍是双射。因此不同的序列号必然产生不同的短码 ——
 * 这是数学保证，不是概率保证，不需要查重也不存在并发冲突。
 *
 * <p>本类无状态且所有字段为 {@code final}，线程安全。
 */
@Component
public class ShortCodeGenerator {

    /** 轮数。8 轮足以抵抗差分与线性密码分析，同时保持极低的计算开销。 */
    private static final int ROUNDS = 8;

    /**
     * Cycle-Walking 的最大迭代次数。
     *
     * <p>数学上必然终止（Feistel 是置换，沿着轮换走一定会回到起点，而起点在有效区间内），
     * 期望迭代次数为 {@code 2^blockBits / 62^length}，6 位短码时约为 1.21 次。
     * 这个上界只是一个防御性的哨兵：若被触发，说明实现有 bug，应当明确报错而不是死循环。
     */
    private static final int MAX_CYCLE_WALK = 1_000;

    /** 轮密钥派生用的 splitmix64 增量常量。 */
    private static final long GOLDEN_GAMMA = 0x9E3779B97F4A7C15L;

    /** FNV-1a 64 位偏移基数与质数，用于把任意长度的密钥字符串压成 64 位种子。 */
    private static final long FNV_OFFSET_BASIS = 0xCBF29CE484222325L;
    private static final long FNV_PRIME = 0x100000001B3L;

    private final long space;
    private final int codeLength;
    private final int blockBits;
    private final int halfBits;
    private final long halfMask;
    private final int[] roundKeys;

    public ShortCodeGenerator(ShortLinkProperties properties) {
        this.codeLength = properties.code().length();
        this.space = pow(Base62.BASE, codeLength);
        this.blockBits = computeBlockBits(this.space);
        this.halfBits = this.blockBits / 2;
        this.halfMask = (1L << this.halfBits) - 1;
        this.roundKeys = deriveRoundKeys(properties.code().secret(), this.halfBits);
    }

    /**
     * 将序列号映射为短码。
     *
     * <p>纯函数：相同的序列号在任何时刻、任何实例上都产生相同的短码。
     *
     * @param sequence 数据库自增序列号，取值 {@code [0, space())}
     * @throws IllegalArgumentException 序列号越界
     */
    public String generate(long sequence) {
        if (sequence < 0 || sequence >= space) {
            throw new IllegalArgumentException(
                    "序列号 %d 超出短码空间 [0, %d)".formatted(sequence, space));
        }
        return Base62.encode(permute(sequence), codeLength);
    }

    /** 短码容量上限，即 {@code 62^codeLength}。用于水位监控。 */
    public long space() {
        return space;
    }

    /** 短码长度。 */
    public int codeLength() {
        return codeLength;
    }

    /**
     * Feistel 置换 + Cycle-Walking。
     *
     * <p>自增序列号是外层调用者给的，天然落在有效区间内，因此循环至少会执行一次。
     */
    private long permute(long sequence) {
        long value = sequence;
        for (int attempt = 0; attempt < MAX_CYCLE_WALK; attempt++) {
            value = feistel(value);
            if (value < space) {
                return value;
            }
        }
        throw new IllegalStateException(
                "Cycle-Walking 迭代 %d 次仍未落入有效区间，序列号=%d，这不应发生，实现存在缺陷"
                        .formatted(MAX_CYCLE_WALK, sequence));
    }

    /**
     * 一轮 Feistel 结构：{@code (L, R) ← (R, L ⊕ F(R, i))}。
     *
     * <p>左右交换是双射性的来源：它保证信息在两个半块之间交替流转，不会有任何一位被丢弃。
     *
     * @param block 取值 {@code [0, 2^blockBits)}
     * @return 同区间内的置换结果
     */
    private long feistel(long block) {
        int left = (int) (block >>> halfBits);
        int right = (int) (block & halfMask);

        for (int round = 0; round < ROUNDS; round++) {
            int nextLeft = right;
            int nextRight = left ^ roundFunction(right, roundKeys[round]);
            left = nextLeft;
            right = nextRight;
        }

        // 两个半块都保持 halfBits 宽，合并不会越界
        return ((long) (left & halfMask) << halfBits) | (right & halfMask);
    }

    /**
     * 轮函数。无需可逆 —— Feistel 结构本身保证了整体双射。
     *
     * <p>使用 MurmurHash3 的终结器（finalizer）作为混淆核心：异或移位、奇数乘法、循环左移
     * 的组合能产生良好的雪崩效应（输入改一位，输出约一半的位发生变化）。
     */
    private int roundFunction(int half, int roundKey) {
        int x = half ^ roundKey;
        x = Integer.rotateLeft(x * 0x85EBCA6B, 13);
        x ^= x >>> 15;
        x *= 0xC2B2AE35;
        x ^= x >>> 13;
        return x & (int) halfMask;
    }

    /**
     * 由密钥派生轮密钥。
     *
     * <p>两步：先用 FNV-1a 把任意长度的密钥字符串压成 64 位种子（与密钥长度无关，
     * 且短密钥不会因长度不足而导致轮密钥相关性），再用 splitmix64 展开成 8 个轮密钥。
     *
     * <p>不同长度的密钥产生完全不同的轮密钥序列，因此不存在"密钥长度不足导致弱化"的问题。
     */
    private static int[] deriveRoundKeys(String secret, int halfBits) {
        long seed = FNV_OFFSET_BASIS;
        for (byte b : secret.getBytes(StandardCharsets.UTF_8)) {
            seed ^= (b & 0xFF);
            seed *= FNV_PRIME;
        }

        int mask = (int) ((1L << halfBits) - 1);
        int[] keys = new int[ROUNDS];
        long state = seed;
        for (int i = 0; i < ROUNDS; i++) {
            state += GOLDEN_GAMMA;
            long z = state;
            z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
            z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
            z ^= (z >>> 31);
            // 轮密钥截断到半块宽度：轮函数的输出会被再次掩码，
            // 高位参与运算不会损失双射性，但截断后语义更清晰
            keys[i] = (int) z & mask;
        }
        return keys;
    }

    /**
     * 计算容纳 {@code space} 个取值所需的最小偶数位宽。
     *
     * <p>必须是偶数，才能均分为两个等宽的半块。例如 6 位短码的空间为
     * {@code 62^6 = 56,800,235,584}，最小编码位宽为 36 位，半块各 18 位。
     *
     * <p>取偶数意味着分组宽度可能略大于空间所需，这正好是 Cycle-Walking 发挥作用的前提：
     * 置换在 {@code [0, 2^blockBits)} 上进行，再把结果收拢回 {@code [0, space)}。
     */
    private static int computeBlockBits(long space) {
        // 位宽 = ceil(log2(space))。当 space 不是 2 的幂时，它等于 (space - 1) 的二进制位长
        int bits = Long.SIZE - Long.numberOfLeadingZeros(space - 1);
        return (bits % 2 == 0) ? bits : bits + 1;
    }

    private static long pow(long base, int exponent) {
        long result = 1L;
        for (int i = 0; i < exponent; i++) {
            result = Math.multiplyExact(result, base);
        }
        return result;
    }
}
