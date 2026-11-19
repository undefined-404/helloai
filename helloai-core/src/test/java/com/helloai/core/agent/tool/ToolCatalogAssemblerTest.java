package com.helloai.core.agent.tool;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link ToolCatalogAssembler} 单测（MA-5 第一步「目录 + 按需取」）。
 *
 * <p>验证目录装配的纯函数语义：不漏不重（元素数与顺序）、摘要封顶（码点口径 + 省略标记）、
 * 词边界回退（不截在词中间）、白名单完整描述、降级口径（null / 空入参 / maxLength&lt;=0）。</p>
 */
@DisplayName("ToolCatalogAssembler 目录装配（MA-5 第一步）")
class ToolCatalogAssemblerTest {

    @Test
    @DisplayName("不漏不重：元素数 = 入参非空元素数，顺序与 name 逐字不变")
    void shouldKeepAllEntriesAndOrder() {
        List<ToolDefinition> catalog = ToolCatalogAssembler.assemble(List.of(
                new ToolDefinition("internal_a", "desc a"),
                new ToolDefinition("internal_b", "desc b"),
                new ToolDefinition("internal_c", "desc c")), 160, Set.of());

        assertThat(catalog).hasSize(3);
        assertThat(catalog.stream().map(ToolDefinition::name).toList())
                .containsExactly("internal_a", "internal_b", "internal_c");
    }

    @Test
    @DisplayName("null 元素跳过、null/空入参 ⇒ 空列表（best-effort 降级）")
    void shouldSkipNullAndHandleEmptyInput() {
        assertThat(ToolCatalogAssembler.assemble(null, 160, Set.of())).isEmpty();
        assertThat(ToolCatalogAssembler.assemble(List.of(), 160, Set.of())).isEmpty();
        // List.of 拒绝 null 元素 ⇒ 用 Arrays.asList 构造含 null 的入参
        List<ToolDefinition> definitionWithNull = Arrays.asList(new ToolDefinition("a", "x"), null);
        assertThat(ToolCatalogAssembler.assemble(definitionWithNull, 160, Set.of()))
                .hasSize(1);
    }

    @Test
    @DisplayName("短描述（≤封顶）原样透传，不追加省略标记")
    void shouldKeepShortDescriptionUntouched() {
        List<ToolDefinition> catalog = ToolCatalogAssembler.assemble(
                List.of(new ToolDefinition("internal_a", "短描述")), 160, Set.of());

        assertThat(catalog.get(0).description()).isEqualTo("短描述");
    }

    @Test
    @DisplayName("超长描述截断到 160 码点 + 省略标记（英文长句）")
    void shouldTruncateLongDescription() {
        String longDesc = "verify the generated document against the acceptance criteria and record evidence ";
        // 确保超过 160 码点
        String padded = longDesc + longDesc + longDesc + "trailing";
        List<ToolDefinition> catalog = ToolCatalogAssembler.assemble(
                List.of(new ToolDefinition("internal_a", padded)), 160, Set.of());

        String output = catalog.get(0).description();
        assertThat(output).endsWith("…");
        // 截断后「码点数 ≤ 160 + 1（省略号）」——用码点计避免 char/码点混淆
        assertThat(output.codePointCount(0, output.length())).isLessThanOrEqualTo(161);
        assertThat(output).doesNotContain("trailing");
    }

    @Test
    @DisplayName("词边界回退：截断点两侧都是词字符时，回退到最近分隔符之后（不截在词中间）")
    void shouldFallBackToWordBoundary() {
        // 150 个 a + 分隔符 '-' + 60 个 b：160 码点处落在 b 段中间 ⇒ 回退到 '-' 之后
        String desc = "a".repeat(150) + "-" + "b".repeat(60);
        List<ToolDefinition> catalog = ToolCatalogAssembler.assemble(
                List.of(new ToolDefinition("internal_a", desc)), 160, Set.of());

        assertThat(catalog.get(0).description()).endsWith("-…");
        assertThat(catalog.get(0).description()).doesNotContain("b");
    }

    @Test
    @DisplayName("白名单工具保留完整描述（即使远超封顶）")
    void shouldKeepWhitelistedToolFullDescription() {
        String longDesc = "x".repeat(500);
        List<ToolDefinition> catalog = ToolCatalogAssembler.assemble(
                List.of(new ToolDefinition("internal_run_in_sandbox", longDesc)), 160,
                Set.of("internal_run_in_sandbox"));

        assertThat(catalog.get(0).description()).isEqualTo(longDesc);
    }

    @Test
    @DisplayName("白名单为 null ⇒ 视为空集，全部按封顶摘要")
    void shouldTreatNullWhitelistAsEmpty() {
        String longDesc = "y".repeat(300);
        List<ToolDefinition> catalog = ToolCatalogAssembler.assemble(
                List.of(new ToolDefinition("internal_a", longDesc)), 160, null);

        assertThat(catalog.get(0).description()).endsWith("…");
    }

    @Test
    @DisplayName("maxLength<=0 视为不截断（配置缺省语义：全部保留完整描述）")
    void shouldNotTruncateWhenMaxLengthNonPositive() {
        String longDesc = "z".repeat(300);
        List<ToolDefinition> catalog = ToolCatalogAssembler.assemble(
                List.of(new ToolDefinition("internal_a", longDesc)), 0, Set.of());

        assertThat(catalog.get(0).description()).isEqualTo(longDesc);
    }

    @Test
    @DisplayName("代理对（emoji）安全：截断不产生半个码点，结果以省略标记收尾")
    void shouldBeSafeForSurrogatePairs() {
        String emojiDesc = "\uD83D\uDE00".repeat(200);   // 200 个 😀 = 400 char
        List<ToolDefinition> catalog = ToolCatalogAssembler.assemble(
                List.of(new ToolDefinition("internal_a", emojiDesc)), 160, Set.of());

        String output = catalog.get(0).description();
        assertThat(output).endsWith("…");
        // 160 个完整 emoji（320 char）+ 省略号 ⇒ 总 char 数 <= 321；且不以半个代理对结尾
        assertThat(output.length()).isLessThanOrEqualTo(321);
        assertThat(Character.isHighSurrogate(output.charAt(output.length() - 2))).isFalse();
    }

    @Test
    @DisplayName("中文长串接受硬截断（无自然词边界，省略标记提示详情可按需取）")
    void shouldHardTruncateChineseLongString() {
        String chinese = "中".repeat(200);
        List<ToolDefinition> catalog = ToolCatalogAssembler.assemble(
                List.of(new ToolDefinition("internal_a", chinese)), 160, Set.of());

        String output = catalog.get(0).description();
        assertThat(output).endsWith("…");
        assertThat(output.codePointCount(0, output.length())).isLessThanOrEqualTo(161);
        assertThat(output).startsWith("中".repeat(160));
    }
}