package com.helloai.core.planner;

import com.helloai.common.base.BizException;
import com.helloai.common.constant.TaskArchetype;
import com.helloai.core.planner.context.ArchetypeDimensionsSection;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 拆解提示词的原型分支契约（`TD-3`）。
 *
 * <p><b>这个测试在守什么</b>：`TD-3` 是本计划的**生效开关**——`TD-1` / `TD-2` 只加了两个常量列，
 * 提示词不分档就等于什么都没变。而提示词分档最容易出的问题是<b>静默失效</b>：
 * 标记写错、块被误删、占位符漏替换，三种情况都<b>不会报错</b>，模型照样吐出一份看起来正常的
 * JSON，只是拆分维度没了。所以这里用静态断言把它们钉死在测试里。</p>
 *
 * <p><b>BUILD 零行为变化的可验证形式</b>：`TD-3` 的验收原文是「同一输入跑两版产物 diff 为空」，
 * 这在 LLM 上不可判定（同一输入两次输出本来就不同）。可判定的等价口径是两条：
 * ① 改造前「拆解原则」的<b>每一条通用规则逐字都在</b>（下面的 {@code buildKeepsAllOriginalPrinciples}）；
 * ② BUILD 的契约先行规则也逐字在位。只要这两条成立，BUILD 的规则集合没被改动过，
 * 剩余差异只来自模型随机性与 `TD-6` 的快照段（后者对六种原型一视同仁，不属 `TD-3` 的回归面）。</p>
 */
@DisplayName("拆解提示词原型分支契约（TD-3）")
class ArchetypeDimensionsSectionTest {

    private static final Pattern PLACEHOLDER = Pattern.compile("\\{\\{([A-Z_]+)}}");

    private static String loadTemplate() throws IOException {
        try (InputStream in = ArchetypeDimensionsSectionTest.class.getClassLoader()
                .getResourceAsStream("prompts/planner-decompose.md")) {
            assertThat(in).as("模板资源必须存在").isNotNull();
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    // ══════════════════════════════════════════════════════════════
    //  择一器行为
    // ══════════════════════════════════════════════════════════════

    @Test
    @DisplayName("择一：每个原型只保留自己的块，其余块连标记一起剔除")
    void selectKeepsOnlyTargetBlock() throws IOException {
        String template = loadTemplate();
        for (TaskArchetype archetype : TaskArchetype.values()) {
            String rendered = ArchetypeDimensionsSection.select(template, archetype);
            // 模板头部注释里有一处「示意用」的 <!-- ARCHETYPE:原型名 -->，故只断言六个真实标记不残留
            // 模板头部注释里不再出现标记字面量，故可以严格断言「一个标记都不残留」
            assertThat(rendered)
                    .as(archetype + "：不得残留任何块标记")
                    .doesNotContain("<!-- ARCHETYPE:")
                    .doesNotContain("/ARCHETYPE");
            assertThat(rendered)
                    .as(archetype + "：块剔除后不得留成片空行（连续 3 个换行 = 2 个空行）")
                    .doesNotContain("\n\n\n");
            // 选中的块内必有该原型的抬头；其余原型的抬头必须消失
            assertThat(rendered).as(archetype + "：选中块应在位").contains("**`" + archetype.name() + "`");
            for (TaskArchetype other : TaskArchetype.values()) {
                if (other == archetype) {
                    continue;
                }
                assertThat(rendered)
                        .as(archetype + " 渲染结果不得包含 " + other + " 块")
                        .doesNotContain("**`" + other.name() + "`");
            }
        }
    }

    @Test
    @DisplayName("择一：null 原型按 BUILD 处理（回落即零行为变化）")
    void nullArchetypeFallsBackToBuild() throws IOException {
        String template = loadTemplate();
        assertThat(ArchetypeDimensionsSection.select(template, null))
                .isEqualTo(ArchetypeDimensionsSection.select(template, TaskArchetype.DEFAULT()));
    }

    @Test
    @DisplayName("择一：模板缺目标原型块时 fail-close（拒绝以无拆分维度的提示词拆解）")
    void missingBlockFailsClosed() {
        String onlyBuild = "正文\n<!-- ARCHETYPE:BUILD -->\n- x\n<!-- /ARCHETYPE -->\n尾";
        assertThatThrownBy(() -> ArchetypeDimensionsSection.select(onlyBuild, TaskArchetype.DOCUMENT))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("DOCUMENT")
                .hasMessageContaining("拆分维度");
    }

    @Test
    @DisplayName("择一：错拼的块名不计入「已存在」（parse 会静默回落 BUILD，这正是要避开的坑）")
    void unknownMarkerDoesNotCountAsBuild() {
        String typo = "<!-- ARCHETYPE:BUILDX -->\n- x\n<!-- /ARCHETYPE -->";
        assertThatThrownBy(() -> ArchetypeDimensionsSection.select(typo, TaskArchetype.BUILD))
                .isInstanceOf(BizException.class);
    }

    // ══════════════════════════════════════════════════════════════
    //  BUILD 零行为变化
    // ══════════════════════════════════════════════════════════════

    @Test
    @DisplayName("BUILD：改造前「拆解原则」的通用条目逐字在位（零行为变化的可验证口径）")
    void buildKeepsAllOriginalPrinciples() throws IOException {
        String rendered = ArchetypeDimensionsSection.select(loadTemplate(), TaskArchetype.BUILD);
        assertThat(rendered)
                .contains("每个子任务应当能由一名执行者独立完成，粒度控制在一次专注工作可交付的范围。")
                .contains("按建议执行顺序排列（前置依赖在前）：dependsOn 只能引用比当前序号更小的元素。")
                .contains("依赖关系不得成环，也不得引用不存在的序号或自身。")
                .contains("每条验收标准必须至少含一个可观察验证点（判定动作 + 预期结果），与 VERIFICATION 围栏形成拆解侧 / 审查侧闭环。")
                .contains("不要生成\"测试一下\"\"收尾\"这类无具体交付物的空泛子任务。")
                .contains("**汇总/整合类子任务引用上游产出，不重传副本**")
                .contains("**边界硬约束（G-011）**")
                .contains("**任务级验收覆盖（P1-1）**");
    }

    @Test
    @DisplayName("BUILD：契约先行规则逐字保留且在 BUILD 块内（其余原型不强制契约子任务）")
    void buildKeepsContractFirstRule() throws IOException {
        String rendered = ArchetypeDimensionsSection.select(loadTemplate(), TaskArchetype.BUILD);
        assertThat(rendered)
                .contains("**契约先行**：任务涉及多模块接口 / 多组件协作")
                .contains("\"contract\": true")
                .contains("普通单模块任务不需要契约子任务");
        String document = ArchetypeDimensionsSection.select(loadTemplate(), TaskArchetype.DOCUMENT);
        assertThat(document).as("DOCUMENT 不再渲染契约先行正文").doesNotContain("**契约先行**：任务涉及多模块接口");
    }

    @Test
    @DisplayName("数量规则：未钉死数量时渲染改造前原句（默认路径零变化）")
    void subtaskCountRuleKeepsOriginalWording() throws IOException {
        assertThat(loadTemplate())
                .as("通用原则里的数量条目由占位符承载，默认由服务端渲染为原句")
                .contains("- {{SUBTASK_COUNT_RULE}}");
    }

    // ══════════════════════════════════════════════════════════════
    //  变量闭包
    // ══════════════════════════════════════════════════════════════

    @Test
    @DisplayName("占位符闭包：模板里的占位符必须全部在渲染器支持集合内（漏替换 = 模型收到字面 {{X}}）")
    void allPlaceholdersAreRendered() throws IOException {
        Set<String> supported = Set.of(
                "TASK_TITLE", "TASK_DESCRIPTION", "TASK_ARCHETYPE", "TASK_REQUIRED_SKILLS",
                "SKILL_CATALOG", "EXECUTOR_PROFILE", "TASK_DIFFICULTY", "GRANULARITY",
                "REQUIREMENT_PACKAGE", "TASK_CONTEXT_SNAPSHOT", "SUBTASK_COUNT_RULE");
        Set<String> found = new LinkedHashSet<>();
        Matcher matcher = PLACEHOLDER.matcher(loadTemplate());
        while (matcher.find()) {
            found.add(matcher.group(1));
        }
        assertThat(supported)
                .as("渲染器支持集合必须覆盖模板实际用到的全部占位符")
                .containsAll(found);
        assertThat(found)
                .as("模板应恰好用满 11 项；少了说明有段内容漏注入，多了说明渲染器没跟上")
                .hasSize(supported.size());
    }

    @Test
    @DisplayName("输出契约：acceptanceKind 字段与四值 + kind↔写法匹配铁律 + 非 BUILD 原型 contract 恒 false")
    void outputContractCarriesAcceptanceKind() throws IOException {
        String template = loadTemplate();
        assertThat(template)
                .contains("\"acceptanceKind\": \"AUTOMATED\"")
                .contains("AUTOMATED / STRUCTURAL / REVIEWED / ATTESTED")
                .contains("铁律：kind 与文本写法必须匹配")
                .contains("仅 BUILD 原型可为 true");
    }
}
