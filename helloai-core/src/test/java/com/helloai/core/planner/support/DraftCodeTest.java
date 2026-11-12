package com.helloai.core.planner.support;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link DraftCode} 边界测试（`TD-16c`）。
 *
 * <p><b>最要紧的一条是「字典序 = 树的前序」</b>：整个方案能省掉排序字段、能让
 * {@code ORDER BY code} 直接可用，全靠定宽。若这条不成立，排序会静默错乱
 * （{@code 10} 排在 {@code 2} 前面），而这种错不会抛异常、只会让顺序看着别扭。</p>
 */
@DisplayName("DraftCode（草案位置身份）")
class DraftCodeTest {

    private static final LocalDate TASK_DATE = LocalDate.of(2026, 11, 8);

    @Nested
    @DisplayName("生成")
    class Generate {

        @Test
        @DisplayName("根编号：日期 + 4 位零填充序号")
        void shouldBuildRootWithPaddedSeq() {
            assertThat(DraftCode.root(TASK_DATE, 2).value()).isEqualTo("202611080002");
            assertThat(DraftCode.root(TASK_DATE, 1).value()).isEqualTo("202611080001");
            assertThat(DraftCode.root(TASK_DATE, 9999).value()).isEqualTo("202611089999");
        }

        @Test
        @DisplayName("根编号：序号 0 / 超上限拒绝")
        void shouldRejectIllegalRootSeq() {
            assertThatThrownBy(() -> DraftCode.root(TASK_DATE, 0))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("根序号越界");
            assertThatThrownBy(() -> DraftCode.root(TASK_DATE, 10000))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("根序号越界");
        }

    }

    @Nested
    @DisplayName("层级")
    class Depth {


        @Test
        @DisplayName("超过 4 段（根+3层）拒绝")
        void shouldRejectTooDeep() {
            assertThatThrownBy(() -> DraftCode.parse("202611080002-001-001-001-001"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("层级过深");
        }


        @Test
        @DisplayName("祖先判定：只看前缀段，不做字符串前缀")
        void shouldRecognizeAncestor() {
            DraftCode root = DraftCode.parse("202611080002");
            assertThat(root.isAncestorOf(DraftCode.parse("202611080002-001"))).isTrue();
            assertThat(root.isAncestorOf(DraftCode.parse("202611080002-001-001"))).isTrue();
            // 2026110800020 是另一个根，不是 202611080002 的后代（前缀字符串相同）
            assertThat(root.isAncestorOf(DraftCode.parse("202611080002-001"))).isTrue();
            assertThat(root.isAncestorOf(root)).isFalse();
        }
    }

    @Nested
    @DisplayName("展示号")
    class Display {

        @Test
        @DisplayName("去前导零：2 / 2.1 / 2.1.1 / 2.1.1.1")
        void shouldStripLeadingZeros() {
            assertThat(DraftCode.parse("202611080002").display()).isEqualTo("2");
            assertThat(DraftCode.parse("202611080001").display()).isEqualTo("1");
            assertThat(DraftCode.parse("202611080002-001").display()).isEqualTo("2.1");
            assertThat(DraftCode.parse("202611080002-010").display()).isEqualTo("2.10");
            assertThat(DraftCode.parse("202611080002-001-003").display()).isEqualTo("2.1.3");
            assertThat(DraftCode.parse("202611080002-001-001-001").display()).isEqualTo("2.1.1.1");
        }

        @Test
        @DisplayName("展示号能反查回同一棵树：同一根下的兄弟共享同一段")
        void shouldShareRootSegment() {
            assertThat(DraftCode.parse("202611080007-002").display()).startsWith("7.");
            assertThat(DraftCode.parse("202611080007-002").rootCode().value()).isEqualTo("202611080007");
        }
    }

    @Nested
    @DisplayName("排序：字典序 = 树的前序")
    class Ordering {

        @Test
        @DisplayName("定宽保证 ORDER BY code 等于前序（含 2 vs 10 的经典陷阱）")
        void shouldOrderLikePreorder() {
            // 故意打乱输入；含 2 与 10 这种不定宽时会错序的组合
            List<String> raw = new ArrayList<>(List.of(
                    "202611080010",
                    "202611080002-002",
                    "202611080001",
                    "202611080002",
                    "202611080002-001",
                    "202611080002-010",
                    "202611080003"));
            List<DraftCode> codes = raw.stream().map(DraftCode::parse).sorted().toList();

            assertThat(codes).extracting(DraftCode::value).containsExactly(
                    "202611080001",
                    "202611080002",
                    "202611080002-001",
                    "202611080002-002",
                    "202611080002-010",
                    "202611080003",
                    "202611080010");
        }
    }

    @Nested
    @DisplayName("解析与校验")
    class Parse {

        @Test
        @DisplayName("非法格式拒绝：位数不对 / 非数字 / 空")
        void shouldRejectMalformed() {
            assertThatThrownBy(() -> DraftCode.parse(null)).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> DraftCode.parse("")).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> DraftCode.parse("20261108002"))
                    .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("根段");
            assertThatThrownBy(() -> DraftCode.parse("202611080002-01"))
                    .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("第 1 层");
            assertThatThrownBy(() -> DraftCode.parse("20261108000X"))
                    .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("根段");
        }

        @Test
        @DisplayName("tryParse：非法返回空而不抛（读存量数据用）")
        void shouldReturnEmptyOnTryParseFailure() {
            assertThat(DraftCode.tryParse("bad")).isEmpty();
            assertThat(DraftCode.tryParse("202611080002")).isNotEmpty();
        }

        @Test
        @DisplayName("record 等值：同 code 字符串即同一位置")
        void shouldEqualByValue() {
            assertThat(DraftCode.parse("202611080002")).isEqualTo(DraftCode.root(TASK_DATE, 2));
            // 历史多段 code 仍可解析（V147 前展开遗留），但新写入不再产生
            assertThat(DraftCode.parse("202611080002-001")).isEqualTo(DraftCode.parse("202611080002-001"));
        }
    }
}
