package com.helloai.core.agent.runtime.sandbox;

import com.helloai.common.config.OpenSandboxProperties;
import com.helloai.core.agent.runtime.sandbox.impl.SkillExecutionSupport;
import com.helloai.core.agent.runtime.sandbox.impl.SkillPackageLoader;
import com.helloai.core.agent.tool.ToolContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * {@link SkillExecutionToolContributor} 单测（`SKX-8` / `SKX-11`，2026-10-26）。
 *
 * <p>验证两条判定<b>刻意不同</b>：{@code internal_run_skill} 要「沙箱启用 + 至少一个包已入库」，
 * {@code internal_read_artifact} / {@code internal_export_artifact} 只要「沙箱启用」——「没包」
 * 不该把回读与导出能力一起摘掉（它们对「手写脚本 + internal_run_in_sandbox」的链路同样有用）。</p>
 */
@DisplayName("SkillExecutionToolContributor 条件可用（SKX-8 / SKX-11 fail-closed）")
class SkillExecutionToolContributorTest {

    private SkillExecutionToolContributor contributor(boolean enabled, boolean hasPackages) {
        OpenSandboxProperties properties = new OpenSandboxProperties();
        properties.setEnabled(enabled);
        SkillPackageLoader loader = mock(SkillPackageLoader.class);
        when(loader.hasBundledSkills()).thenReturn(hasPackages);
        when(loader.bundledSkills()).thenReturn(hasPackages
                ? List.of(new SkillPackageLoader.BundledSkill("archify", "3.1.0"))
                : List.of());
        SkillExecutionSupport support = new SkillExecutionSupport(properties, loader,
                mock(SandboxExecutionService.class));
        return new SkillExecutionToolContributor(support);
    }

    @Test
    @DisplayName("沙箱启用 + 有包：三个工具都可用")
    void shouldExposeAllWhenEnabledWithPackages() {
        Map<String, Predicate<ToolContext>> availability = contributor(true, true).toolAvailability();

        assertThat(availability).containsOnlyKeys(
                SkillExecutionToolContributor.TOOL_INTERNAL_RUN_SKILL,
                SkillExecutionToolContributor.TOOL_INTERNAL_READ_ARTIFACT,
                SkillExecutionToolContributor.TOOL_INTERNAL_EXPORT_ARTIFACT);
        assertThat(availability.get(SkillExecutionToolContributor.TOOL_INTERNAL_RUN_SKILL).test(null)).isTrue();
        assertThat(availability.get(SkillExecutionToolContributor.TOOL_INTERNAL_READ_ARTIFACT).test(null)).isTrue();
        assertThat(availability.get(SkillExecutionToolContributor.TOOL_INTERNAL_EXPORT_ARTIFACT).test(null)).isTrue();
    }

    @Test
    @DisplayName("沙箱启用但无入库包：仅摘除 run_skill，read_artifact / export_artifact 仍可用")
    void shouldHideRunSkillOnlyWhenNoPackages() {
        Map<String, Predicate<ToolContext>> availability = contributor(true, false).toolAvailability();

        assertThat(availability.get(SkillExecutionToolContributor.TOOL_INTERNAL_RUN_SKILL).test(null)).isFalse();
        assertThat(availability.get(SkillExecutionToolContributor.TOOL_INTERNAL_READ_ARTIFACT).test(null)).isTrue();
        assertThat(availability.get(SkillExecutionToolContributor.TOOL_INTERNAL_EXPORT_ARTIFACT).test(null)).isTrue();
    }

    @Test
    @DisplayName("沙箱未启用（默认）：三个工具都摘除")
    void shouldHideAllWhenDisabled() {
        Map<String, Predicate<ToolContext>> availability = contributor(false, true).toolAvailability();

        assertThat(availability.get(SkillExecutionToolContributor.TOOL_INTERNAL_RUN_SKILL).test(null)).isFalse();
        assertThat(availability.get(SkillExecutionToolContributor.TOOL_INTERNAL_READ_ARTIFACT).test(null)).isFalse();
        assertThat(availability.get(SkillExecutionToolContributor.TOOL_INTERNAL_EXPORT_ARTIFACT).test(null)).isFalse();
    }

    @Test
    @DisplayName("纯声明类：toolObject 返回 null（工具本体在 InternalLlmExecutorTools，不进对外 MCP 面）")
    void shouldNotCarryToolObject() {
        assertThat(contributor(true, true).toolObject()).isNull();
    }
}
