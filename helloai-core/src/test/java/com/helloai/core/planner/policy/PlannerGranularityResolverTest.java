package com.helloai.core.planner.policy;

import com.helloai.common.constant.AgentAccessType;
import com.helloai.core.task.policy.TaskAgentPolicy;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link PlannerGranularityResolver} 决策矩阵单测（G-010，rule-based 可回归）。
 *
 * <p>覆盖 4 类执行者画像（空名单 / 全 CLI / 全内部 / 混合）× difficulty 调制，
 * 与 {@code doc/design/Planner_Capability_Awareness.md} §1-D3 决策矩阵一一对应。</p>
 */
@DisplayName("PlannerGranularityResolver")
class PlannerGranularityResolverTest {

    private static final List<AgentAccessType> NO_EXECUTORS = List.of();

    @Test
    @DisplayName("白名单为空（不限定）→ STANDARD，保持现状不默认细拆")
    void whitelistEmptyResolvesStandard() {
        PlannerGranularityResolver.GranularityDecision d =
                PlannerGranularityResolver.resolve(null, NO_EXECUTORS);
        assertThat(d.granularity()).isEqualTo(PlannerGranularity.STANDARD);
        assertThat(d.profile()).contains("不限定");
    }

    @Test
    @DisplayName("全部 CLI_CLIENT（外部强执行者）→ COARSE")
    void allCliClientResolvesCoarse() {
        Map<String, Object> policy = TaskAgentPolicy.build(null, List.of(1L, 2L), null, null, null);
        PlannerGranularityResolver.GranularityDecision d =
                PlannerGranularityResolver.resolve(policy,
                        List.of(AgentAccessType.CLI_CLIENT, AgentAccessType.CLI_CLIENT));
        assertThat(d.granularity()).isEqualTo(PlannerGranularity.COARSE);
        assertThat(d.profile()).contains("外部");
    }

    @Test
    @DisplayName("全部 API_KEY_LLM（内部兜底）→ FINE")
    void allApiKeyLlmResolvesFine() {
        Map<String, Object> policy = TaskAgentPolicy.build(null, List.of(1L), null, null, null);
        PlannerGranularityResolver.GranularityDecision d =
                PlannerGranularityResolver.resolve(policy, List.of(AgentAccessType.API_KEY_LLM));
        assertThat(d.granularity()).isEqualTo(PlannerGranularity.FINE);
    }

    @Test
    @DisplayName("全部 WEB_BROWSER（网页兜底，非 CLI）→ FINE")
    void webBrowserResolvesFine() {
        Map<String, Object> policy = TaskAgentPolicy.build(null, List.of(1L), null, null, null);
        PlannerGranularityResolver.GranularityDecision d =
                PlannerGranularityResolver.resolve(policy, List.of(AgentAccessType.WEB_BROWSER));
        assertThat(d.granularity()).isEqualTo(PlannerGranularity.FINE);
    }

    @Test
    @DisplayName("混合（CLI_CLIENT + API_KEY_LLM 强弱并存）→ FINE（按弱者兜底）")
    void mixedResolvesFine() {
        Map<String, Object> policy = TaskAgentPolicy.build(null, List.of(1L, 2L), null, null, null);
        PlannerGranularityResolver.GranularityDecision d =
                PlannerGranularityResolver.resolve(policy,
                        List.of(AgentAccessType.CLI_CLIENT, AgentAccessType.API_KEY_LLM));
        assertThat(d.granularity()).isEqualTo(PlannerGranularity.FINE);
        assertThat(d.profile()).contains("混合");
    }

    @Test
    @DisplayName("difficulty=HIGH → COARSE 上移 STANDARD")
    void highDifficultyUpshiftsCoarse() {
        Map<String, Object> policy = TaskAgentPolicy.build(
                null, List.of(1L), null, null, TaskAgentPolicy.Difficulty.HIGH);
        PlannerGranularityResolver.GranularityDecision d =
                PlannerGranularityResolver.resolve(policy, List.of(AgentAccessType.CLI_CLIENT));
        assertThat(d.granularity()).isEqualTo(PlannerGranularity.STANDARD);
    }

    @Test
    @DisplayName("difficulty=HIGH → STANDARD 上移 FINE")
    void highDifficultyUpshiftsStandard() {
        Map<String, Object> policy = TaskAgentPolicy.build(
                null, List.of(), null, null, TaskAgentPolicy.Difficulty.HIGH);
        PlannerGranularityResolver.GranularityDecision d =
                PlannerGranularityResolver.resolve(policy, NO_EXECUTORS);
        assertThat(d.granularity()).isEqualTo(PlannerGranularity.FINE);
    }

    @Test
    @DisplayName("difficulty=HIGH → FINE 保持 FINE（已达上限）")
    void highDifficultyKeepsFine() {
        Map<String, Object> policy = TaskAgentPolicy.build(
                null, List.of(1L), null, null, TaskAgentPolicy.Difficulty.HIGH);
        PlannerGranularityResolver.GranularityDecision d =
                PlannerGranularityResolver.resolve(policy, List.of(AgentAccessType.API_KEY_LLM));
        assertThat(d.granularity()).isEqualTo(PlannerGranularity.FINE);
    }

    @Test
    @DisplayName("difficulty=LOW/MEDIUM 不改变基准粒度")
    void lowMediumDifficultyKeepsBase() {
        Map<String, Object> medium = TaskAgentPolicy.build(
                null, List.of(1L), null, null, TaskAgentPolicy.Difficulty.MEDIUM);
        assertThat(PlannerGranularityResolver.resolve(medium, List.of(AgentAccessType.CLI_CLIENT)).granularity())
                .isEqualTo(PlannerGranularity.COARSE);
    }

    @Test
    @DisplayName("白名单非空但画像缺失（agent 已删除/异常）→ STANDARD 保守")
    void whitelistWithMissingProfileResolvesStandard() {
        Map<String, Object> policy = TaskAgentPolicy.build(null, List.of(1L, 2L), null, null, null);
        PlannerGranularityResolver.GranularityDecision d =
                PlannerGranularityResolver.resolve(policy, List.of());
        assertThat(d.granularity()).isEqualTo(PlannerGranularity.STANDARD);
        assertThat(d.profile()).contains("画像缺失");
    }
}