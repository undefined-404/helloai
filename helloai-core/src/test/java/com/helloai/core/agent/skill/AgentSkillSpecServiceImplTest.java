package com.helloai.core.agent.skill;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link AgentSkillSpecService} 实现单元测试（Phase 1 Step 1 fix：由 task 域
 * {@code PluginSkillSpecServiceImplTest} 迁域改造，LOG-20260904-009）。
 *
 * <p>纯函数语义：入参即 requiredSkills，不再查 task（§6 依赖方向红线）；命中语义与
 * Prompt 注入事实严格一致（两层过滤：标签命中 + 速览非空），走真实 classpath 资源
 * {@code skills/plugins/*.md}（helloai-core 主资源，不 mock）。</p>
 */
@DisplayName("AgentSkillSpecServiceImpl")
class AgentSkillSpecServiceImplTest {

    private final AgentSkillSpecService service = new AgentSkillSpecServiceImpl();

    private static final String REQUIRED = "任务 required_skills 命中 eng-code-review 时注入执行 Prompt";

    @Test
    @DisplayName("requiredSkills 为 null → 空三字段（best-effort，不抛异常）")
    void shouldReturnEmptyWhenRequiredSkillsNull() {
        AgentSkillSpecService.ResolvedSpec resolved = service.resolve(null);
        assertThat(resolved).isNotNull();
        assertThat(resolved.requiredSkills()).isEmpty();
        assertThat(resolved.matchedLabels()).isEmpty();
        assertThat(resolved.section()).isEmpty();
    }

    @Test
    @DisplayName("requiredSkills 为空列表 → 空三字段")
    void shouldReturnEmptyWhenRequiredSkillsEmpty() {
        AgentSkillSpecService.ResolvedSpec resolved = service.resolve(List.of());
        assertThat(resolved.requiredSkills()).isEmpty();
        assertThat(resolved.matchedLabels()).isEmpty();
        assertThat(resolved.section()).isEmpty();
    }

    @Test
    @DisplayName("未命中未知标签 → matchedLabels 空、section 空（声明原样保留）")
    void shouldIgnoreUnknownSkills() {
        AgentSkillSpecService.ResolvedSpec resolved = service.resolve(List.of("unknown-skill-a"));
        assertThat(resolved.requiredSkills()).containsExactly("unknown-skill-a");
        assertThat(resolved.matchedLabels()).isEmpty();
        assertThat(resolved.section()).isEmpty();
    }

    @Test
    @DisplayName("命中单标签 → 渲染速览段：含速览正文、不含详细规范与 h1 标题")
    void shouldRenderSpeedSummaryOnlyForSingleHit() {
        AgentSkillSpecService.ResolvedSpec resolved = service.resolve(List.of("eng-code-review"));
        assertThat(resolved.matchedLabels()).containsExactly("eng-code-review");
        assertThat(resolved.section())
                // 速览正文（四要素第 1 条首句）必须注入
                .contains(REQUIRED)
                // 速览截断：详细规范部分不得进入渲染段
                .doesNotContain("## 详细规范")
                .doesNotContain("### C1 接口契约")
                // h1 标题行剔除（渲染段自带 ### 标签标题）
                .doesNotContain("# eng-code-review 平台技能规范")
                // 渲染段自带标题层级
                .contains("### eng-code-review");
    }

    @Test
    @DisplayName("多命中按 KNOWN_SPECS 声明顺序渲染（不是 requiredSkills 声明顺序）")
    void shouldRenderMultipleHitsInKnownSpecsOrder() {
        // 输入顺序与声明顺序相反（eng-doc-standard 在前），渲染必须仍按 eng-code-review → eng-doc-standard
        AgentSkillSpecService.ResolvedSpec resolved = service.resolve(
                List.of("eng-doc-standard", "eng-code-review"));
        assertThat(resolved.matchedLabels())
                .containsExactly("eng-code-review", "eng-doc-standard");
        assertThat(resolved.section().indexOf("### eng-code-review"))
                .isLessThan(resolved.section().indexOf("### eng-doc-standard"));
    }

    @Test
    @DisplayName("混合已知 + 未知标签 → 未知忽略，只注入命中项")
    void shouldMixKnownAndUnknown() {
        AgentSkillSpecService.ResolvedSpec resolved = service.resolve(
                List.of("eng-verification", "eng-unknown"));
        assertThat(resolved.matchedLabels()).containsExactly("eng-verification");
        assertThat(resolved.section()).contains("### eng-verification").doesNotContain("eng-unknown");
    }

    @Test
    @DisplayName("ResolvedSpec record 构造器将 null 字段规范化为默认值")
    void shouldNormalizeNullFieldsInRecord() {
        AgentSkillSpecService.ResolvedSpec resolved = new AgentSkillSpecService.ResolvedSpec(null, null, null);
        assertThat(resolved.requiredSkills()).isEmpty();
        assertThat(resolved.matchedLabels()).isEmpty();
        assertThat(resolved.section()).isEmpty();
    }
}