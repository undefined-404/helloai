package com.helloai.core.shared.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link SecretMask} 单元测试（`M-8`）。
 *
 * <p>口径来自 magpie {@code internal/provider/provider.go:1501-1506} 的 {@code Mask}，
 * 故用**逐字对照的固定向量**断言，而不是「自己算一遍再比对」（后者是同义反复）。</p>
 */
@DisplayName("SecretMask 密钥脱敏（M-8，对齐 magpie Mask）")
class SecretMaskTest {

    @Nested
    @DisplayName("保留两侧（长度 > 8）")
    class KeepEdges {

        @Test
        @DisplayName("典型 API Key：保留前 4 与后 4，中间省略号")
        void shouldKeepEdgesForTypicalKey() {
            // magpie: s[:4] + "…" + s[len(s)-4:]
            assertThat(SecretMask.mask("sk-abcdefgh1234")).isEqualTo("sk-a…1234");
        }

        @Test
        @DisplayName("恰好 9 位（最短的保留两侧情形）⇒ 前 4 + … + 后 4，中间仅 1 个字符被遮蔽")
        void shouldKeepEdgesAtNineChars() {
            assertThat(SecretMask.mask("123456789")).isEqualTo("1234…6789");
        }

        @Test
        @DisplayName("长度 12 的十六进制串（形如指纹，但脱敏的是明文）")
        void shouldKeepEdgesForHexLikeValue() {
            assertThat(SecretMask.mask("abcdef123456")).isEqualTo("abcd…3456");
        }

        @Test
        @DisplayName("不改变原串（纯函数，无副作用）")
        void shouldNotMutateInput() {
            String original = "sk-abcdefgh1234";
            SecretMask.mask(original);
            assertThat(original).isEqualTo("sk-abcdefgh1234");
        }
    }

    @Nested
    @DisplayName("整串遮蔽（长度 ≤ 8）")
    class FullMask {

        @Test
        @DisplayName("恰好 8 位 ⇒ 不做保留两侧，整串点号（边界值，magpie 用 <= 8）")
        void shouldFullyMaskAtEightChars() {
            assertThat(SecretMask.mask("12345678")).isEqualTo("••••••••");
        }

        @Test
        @DisplayName("5 位 ⇒ 5 个点号")
        void shouldFullyMaskShortValue() {
            assertThat(SecretMask.mask("12345")).isEqualTo("•••••");
        }

        @Test
        @DisplayName("1 位 ⇒ 1 个点号（不因太短而泄露唯一字符）")
        void shouldFullyMaskSingleChar() {
            assertThat(SecretMask.mask("x")).isEqualTo("•");
        }
    }

    @Nested
    @DisplayName("空值边界")
    class EmptyCases {

        @Test
        @DisplayName("null 原样返回 null（避免把「未配置」渲染成点号串）")
        void shouldPassThroughNull() {
            assertThat(SecretMask.mask(null)).isNull();
        }

        @Test
        @DisplayName("空串原样返回空串（同上；调用点负责把空归一为「未配置」）")
        void shouldPassThroughEmpty() {
            assertThat(SecretMask.mask("")).isEmpty();
        }
    }
}
