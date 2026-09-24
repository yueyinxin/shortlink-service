package io.github.yueyinxin.shortlink.link;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link LinkSnapshot} 的访问判定测试。
 *
 * <p>这是本项目业务上最关键的一处纯逻辑：它决定每一次短链点击是被正常跳转、
 * 还是返回 403（已禁用）或 410（已过期）。由于两条跳转路径（命中缓存 / 回查数据库）
 * 共用这一份实现，这里的测试同时覆盖了两条路径的判定正确性。
 *
 * <p>价值在于边界条件：{@code expiresAt} 恰好等于当前时间时算不算过期？
 * 禁用与过期同时成立时报哪个？这些是线上最容易出现"时好时坏"的地方。
 */
class LinkSnapshotTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 9, 24, 12, 0, 0);

    private static LinkSnapshot active() {
        return new LinkSnapshot(1L, "abc123", "https://example.com", LinkStatus.ACTIVE, null);
    }

    @Nested
    @DisplayName("正常访问")
    class Accessible {

        @Test
        @DisplayName("启用且未设置过期时间：可访问")
        void activeWithoutExpiry() {
            assertThat(active().accessState(NOW)).isEqualTo(LinkAccessState.ACCESSIBLE);
        }

        @Test
        @DisplayName("启用且过期时间在未来：可访问")
        void activeWithFutureExpiry() {
            LinkSnapshot snapshot = new LinkSnapshot(
                    1L, "abc123", "https://example.com",
                    LinkStatus.ACTIVE, NOW.plusSeconds(1));
            assertThat(snapshot.accessState(NOW)).isEqualTo(LinkAccessState.ACCESSIBLE);
        }
    }

    @Nested
    @DisplayName("过期判定")
    class Expiry {

        @Test
        @DisplayName("过期时间已过：返回 EXPIRED")
        void expired() {
            LinkSnapshot snapshot = new LinkSnapshot(
                    1L, "abc123", "https://example.com",
                    LinkStatus.ACTIVE, NOW.minusSeconds(1));
            assertThat(snapshot.accessState(NOW)).isEqualTo(LinkAccessState.EXPIRED);
        }

        /**
         * 边界：{@code expiresAt} 恰好等于当前时间。
         *
         * <p>实现使用 {@code !expiresAt.isAfter(now)} 而非 {@code expiresAt.isBefore(now)}，
         * 使过期时刻本身也算过期（边界闭合）。若用 {@code isBefore}，
         * 在这一微秒上的行为会依赖调用时刻的精确值，表现为"偶尔能打开、偶尔不能"。
         */
        @Test
        @DisplayName("过期时间恰好等于当前时间：判定为已过期（边界闭合）")
        void expiresExactlyAtNowIsExpired() {
            LinkSnapshot snapshot = new LinkSnapshot(
                    1L, "abc123", "https://example.com",
                    LinkStatus.ACTIVE, NOW);
            assertThat(snapshot.accessState(NOW)).isEqualTo(LinkAccessState.EXPIRED);
        }
    }

    @Nested
    @DisplayName("禁用判定")
    class Disabled {

        @Test
        @DisplayName("已禁用：返回 DISABLED")
        void disabled() {
            LinkSnapshot snapshot = new LinkSnapshot(
                    1L, "abc123", "https://example.com", LinkStatus.DISABLED, null);
            assertThat(snapshot.accessState(NOW)).isEqualTo(LinkAccessState.DISABLED);
        }

        /**
         * 优先级：禁用与过期同时成立时，<b>禁用优先</b>。
         *
         * <p>两者可能同时为真（创建者禁用了链接，之后时间又越过了过期点）。
         * 让"禁用"优先，因为它是人的显式操作，意图比时间流逝更明确。
         * 若让过期优先，创建者会看到"已过期"，误以为自己没有禁用它，
         * 进而怀疑禁用功能失效。
         */
        @Test
        @DisplayName("同时禁用且已过期：禁用优先")
        void disabledTakesPrecedenceOverExpired() {
            LinkSnapshot snapshot = new LinkSnapshot(
                    1L, "abc123", "https://example.com",
                    LinkStatus.DISABLED, NOW.minusDays(1));
            assertThat(snapshot.accessState(NOW)).isEqualTo(LinkAccessState.DISABLED);
        }
    }

    @Nested
    @DisplayName("结果与错误码的映射")
    class StateContract {

        @Test
        @DisplayName("每种不可访问状态都带有对应的错误码与原因")
        void everyUnavailableStateCarriesErrorCode() {
            for (LinkAccessState state : LinkAccessState.values()) {
                if (state == LinkAccessState.ACCESSIBLE) {
                    assertThat(state.isAccessible()).isTrue();
                    assertThat(state.errorCode()).as("可访问状态不应有错误码").isNull();
                    assertThat(state.reason()).isNull();
                } else {
                    assertThat(state.isAccessible()).isFalse();
                    assertThat(state.errorCode()).as("%s 必须携带错误码", state).isNotNull();
                    assertThat(state.reason()).as("%s 必须携带原因", state).isNotBlank();
                }
            }
        }

        @Test
        @DisplayName("NOW 为 null 时快速失败")
        void rejectsNullNow() {
            assertThatThrownBy(() -> active().accessState(null))
                    .isInstanceOf(NullPointerException.class);
        }
    }
}
