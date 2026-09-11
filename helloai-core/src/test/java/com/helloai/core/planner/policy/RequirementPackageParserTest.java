package com.helloai.core.planner.policy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link RequirementPackageParser} 防御式解析与渲染单测（G-011 S1 数据层）。
 *
 * <p>覆盖键缺失 / 类型异常 / 元素混杂回落空集合、task.context 键空间隔离
 * （requirementPackage 与 runningSpec 共存互不影响）与六字段逐项渲染
 * （空数组字段不渲染、全空占位文案），锚定「无需求包全链行为零变化」。</p>
 */
@DisplayName("RequirementPackageParser")
class RequirementPackageParserTest {

    private Map<String, Object> packageWith(String goal, List<String> scope, List<String> outOfScope,
                                            List<String> acceptanceCriteria, List<String> assumptions,
                                            List<String> openQuestions) {
        Map<String, Object> map = new HashMap<>();
        map.put(RequirementPackageParser.KEY_GOAL, goal);
        map.put(RequirementPackageParser.KEY_SCOPE, scope);
        map.put(RequirementPackageParser.KEY_OUT_OF_SCOPE, outOfScope);
        map.put(RequirementPackageParser.KEY_ACCEPTANCE_CRITERIA, acceptanceCriteria);
        map.put(RequirementPackageParser.KEY_ASSUMPTIONS, assumptions);
        map.put(RequirementPackageParser.KEY_OPEN_QUESTIONS, openQuestions);
        return map;
    }

    @Test
    @DisplayName("六字段全量正常解析：goal 与五数组原样透传")
    void parseFullPackage() {
        RequirementPackage pkg = RequirementPackageParser.parse(packageWith(
                "完成订单模块改造",
                List.of("订单创建", "订单列表"),
                List.of("不做支付渠道接入"),
                List.of("验收条目一：订单创建接口返回 201", "验收条目二：列表分页参数生效"),
                List.of("假设用户环境为 JDK17"),
                List.of("存量调用方数量未确认")));

        assertThat(pkg.goal()).isEqualTo("完成订单模块改造");
        assertThat(pkg.scope()).containsExactly("订单创建", "订单列表");
        assertThat(pkg.outOfScope()).containsExactly("不做支付渠道接入");
        assertThat(pkg.acceptanceCriteria())
                .containsExactly("验收条目一：订单创建接口返回 201", "验收条目二：列表分页参数生效");
        assertThat(pkg.assumptions()).containsExactly("假设用户环境为 JDK17");
        assertThat(pkg.openQuestions()).containsExactly("存量调用方数量未确认");
        assertThat(pkg.isEmpty()).isFalse();
    }

    @Test
    @DisplayName("source 为 null 或空 Map → EMPTY（旧数据零变化）")
    void parseNullOrEmptyReturnsEmpty() {
        assertThat(RequirementPackageParser.parse(null).isEmpty()).isTrue();
        assertThat(RequirementPackageParser.parse(Map.of()).isEmpty()).isTrue();
    }

    @Test
    @DisplayName("键缺失：goal 回落 null、五数组回落空列表（不抛异常）")
    void parseMissingKeysDefensive() {
        RequirementPackage pkg = RequirementPackageParser.parse(Map.of("goal", "只有目标"));

        assertThat(pkg.goal()).isEqualTo("只有目标");
        assertThat(pkg.scope()).isEmpty();
        assertThat(pkg.outOfScope()).isEmpty();
        assertThat(pkg.acceptanceCriteria()).isEmpty();
        assertThat(pkg.assumptions()).isEmpty();
        assertThat(pkg.openQuestions()).isEmpty();
    }

    @Test
    @DisplayName("类型异常：goal 为数字回落 null、scope 为字符串回落空列表（不抛异常）")
    void parseTypeMismatchDefensive() {
        Map<String, Object> source = new HashMap<>();
        source.put(RequirementPackageParser.KEY_GOAL, 123);
        source.put(RequirementPackageParser.KEY_SCOPE, "订单列表");
        source.put(RequirementPackageParser.KEY_OPEN_QUESTIONS, 42);

        RequirementPackage pkg = RequirementPackageParser.parse(source);
        assertThat(pkg.goal()).isNull();
        assertThat(pkg.scope()).isEmpty();
        assertThat(pkg.openQuestions()).isEmpty();
    }

    @Test
    @DisplayName("数组元素混杂：仅保留字符串，数字 / 布尔 / 空白元素丢弃")
    void parseMixedListElementsKeepStringsOnly() {
        Map<String, Object> source = new HashMap<>();
        source.put(RequirementPackageParser.KEY_ASSUMPTIONS,
                List.of("JDK17 假设", 42, true, "  ", "另一条假设"));

        RequirementPackage pkg = RequirementPackageParser.parse(source);
        assertThat(pkg.assumptions()).containsExactly("JDK17 假设", "另一条假设");
    }

    @Test
    @DisplayName("fromContext：context 为 null / 无键 / 值非 Map → EMPTY")
    void fromContextDefensive() {
        assertThat(RequirementPackageParser.fromContext(null).isEmpty()).isTrue();
        assertThat(RequirementPackageParser.fromContext(Map.of()).isEmpty()).isTrue();
        assertThat(RequirementPackageParser.fromContext(Map.of("runningSpec", Map.of())).isEmpty()).isTrue();
        assertThat(RequirementPackageParser.fromContext(
                Map.of(RequirementPackageParser.CONTEXT_KEY_REQUIREMENT_PACKAGE, "非法形态")).isEmpty()).isTrue();
    }

    @Test
    @DisplayName("键空间隔离：requirementPackage 与 runningSpec 共存互不影响")
    void fromContextKeyIsolationWithRunningSpec() {
        Map<String, Object> context = new HashMap<>();
        context.put("runningSpec", Map.of("baseline", "存量 runningSpec 数据"));
        context.put(RequirementPackageParser.CONTEXT_KEY_REQUIREMENT_PACKAGE,
                packageWith("双写需求包目标", List.of("范围条目"), List.of(), List.of(), List.of(), List.of()));

        RequirementPackage pkg = RequirementPackageParser.fromContext(context);
        assertThat(pkg.goal()).isEqualTo("双写需求包目标");
        assertThat(pkg.scope()).containsExactly("范围条目");
        assertThat(pkg.isEmpty()).isFalse();
    }

    @Test
    @DisplayName("render：全空需求包输出占位文案（未走澄清链路形态）")
    void renderEmptyUsesPlaceholder() {
        String rendered = RequirementPackageParser.render(RequirementPackage.EMPTY);
        assertThat(rendered).contains("无结构化需求包");
        assertThat(RequirementPackageParser.render(null)).isEqualTo(rendered);
    }

    @Test
    @DisplayName("render：六字段逐项列表，空数组字段不渲染（防提示词膨胀）")
    void renderSkipsEmptyArrayFields() {
        RequirementPackage pkg = new RequirementPackage(
                "完成订单模块改造",
                List.of("订单创建", "订单列表"),
                List.of(),
                List.of("订单创建接口返回 201"),
                List.of("假设用户环境为 JDK17"),
                List.of());

        String rendered = RequirementPackageParser.render(pkg);
        assertThat(rendered).contains("- 目标：完成订单模块改造");
        assertThat(rendered).contains("- 范围：");
        assertThat(rendered).contains("  - 订单创建");
        assertThat(rendered).contains("  - 订单列表");
        assertThat(rendered).contains("- 任务级验收标准（acceptanceCriteria）：");
        assertThat(rendered).contains("  - 订单创建接口返回 201");
        assertThat(rendered).contains("- 关键假设（推断项，须标注）：");
        assertThat(rendered).contains("  - 假设用户环境为 JDK17");
        // 空数组字段（outOfScope / openQuestions）不渲染
        assertThat(rendered).doesNotContain("outOfScope");
        assertThat(rendered).doesNotContain("待确认事项");
    }

    @Test
    @DisplayName("render：acceptanceCriteria 为空数组时不渲染该行（存量任务零变化）")
    void renderSkipsAcceptanceCriteriaWhenEmpty() {
        RequirementPackage pkg = new RequirementPackage(
                "存量任务目标", List.of(), List.of(), List.of(), List.of(), List.of());

        assertThat(RequirementPackageParser.render(pkg))
                .doesNotContain("acceptanceCriteria")
                .doesNotContain("任务级验收标准");
    }

    @Test
    @DisplayName("acceptanceCriteria 类型异常 / 元素混杂：回落空集合与仅保留字符串")
    void parseAcceptanceCriteriaDefensive() {
        Map<String, Object> wrongType = new HashMap<>();
        wrongType.put(RequirementPackageParser.KEY_ACCEPTANCE_CRITERIA, "验收条目");
        assertThat(RequirementPackageParser.parse(wrongType).acceptanceCriteria()).isEmpty();

        Map<String, Object> mixed = new HashMap<>();
        mixed.put(RequirementPackageParser.KEY_ACCEPTANCE_CRITERIA,
                List.of("有效条目", 7, false, "   "));
        assertThat(RequirementPackageParser.parse(mixed).acceptanceCriteria())
                .containsExactly("有效条目");
    }

    @Test
    @DisplayName("isEmpty：goal 空白且五数组全空判空，任一字段有内容判非空")
    void isEmptySemantics() {
        assertThat(RequirementPackage.EMPTY.isEmpty()).isTrue();
        assertThat(new RequirementPackage("", List.of(), List.of(), List.of(), List.of(), List.of())
                .isEmpty()).isTrue();
        assertThat(new RequirementPackage("目标", List.of(), List.of(), List.of(), List.of(), List.of())
                .isEmpty()).isFalse();
        assertThat(new RequirementPackage(null, List.of("范围"), List.of(), List.of(), List.of(), List.of())
                .isEmpty()).isFalse();
        // 仅有 acceptanceCriteria 时同样判非空（P1 新增字段参与判空）
        assertThat(new RequirementPackage(null, List.of(), List.of(),
                List.of("验收条目"), List.of(), List.of()).isEmpty()).isFalse();
    }
}
