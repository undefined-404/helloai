package com.helloai.core.task.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * TaskAgentPolicy 静态解析工具单元测试（V47，§6.58 P1）。
 *
 * <p>覆盖：默认值回落（null policy / 键缺失 / 非法值）、JSONB 数字类型防御
 * （Integer/Long/字符串）、executorAgentIds 过滤、isFallbackForbidden 判定、
 * build 落库形态（null/空值键不写入）。</p>
 */
@DisplayName("TaskAgentPolicy")
class TaskAgentPolicyTest {

    @Test
    @DisplayName("null / 空 policy 全部回落默认值")
    void shouldFallbackToDefaultsForNullPolicy() {
        assertThat(TaskAgentPolicy.plannerAgentId(null)).isNull();
        assertThat(TaskAgentPolicy.executorAgentIds(null)).isEmpty();
        assertThat(TaskAgentPolicy.reviewerAgentId(null)).isNull();
        assertThat(TaskAgentPolicy.fallbackPolicy(null)).isEqualTo(TaskAgentPolicy.FallbackPolicy.AUTO);
        assertThat(TaskAgentPolicy.difficulty(null)).isEqualTo(TaskAgentPolicy.Difficulty.MEDIUM);
        assertThat(TaskAgentPolicy.isFallbackForbidden(null)).isFalse();

        assertThat(TaskAgentPolicy.plannerAgentId(Map.of())).isNull();
        assertThat(TaskAgentPolicy.executorAgentIds(Map.of())).isEmpty();
        assertThat(TaskAgentPolicy.fallbackPolicy(Map.of())).isEqualTo(TaskAgentPolicy.FallbackPolicy.AUTO);
        assertThat(TaskAgentPolicy.difficulty(Map.of())).isEqualTo(TaskAgentPolicy.Difficulty.MEDIUM);
    }

    @Test
    @DisplayName("显式值正常解析（含 JSONB 数字类型防御）")
    void shouldParseExplicitValuesWithNumericTypeDefense() {
        // JSONB 反序列化后数字可能是 Integer/Long/BigDecimal，甚至字符串
        Map<String, Object> policy = Map.of(
                TaskAgentPolicy.KEY_PLANNER_AGENT_ID, 123L,
                TaskAgentPolicy.KEY_EXECUTOR_AGENT_IDS, List.of(1, 2L, "3"),
                TaskAgentPolicy.KEY_REVIEWER_AGENT_ID, "456",
                TaskAgentPolicy.KEY_FALLBACK_POLICY, "restricted",
                TaskAgentPolicy.KEY_DIFFICULTY, "high");

        assertThat(TaskAgentPolicy.plannerAgentId(policy)).isEqualTo(123L);
        assertThat(TaskAgentPolicy.executorAgentIds(policy)).containsExactly(1L, 2L, 3L);
        assertThat(TaskAgentPolicy.reviewerAgentId(policy)).isEqualTo(456L);
        assertThat(TaskAgentPolicy.fallbackPolicy(policy)).isEqualTo(TaskAgentPolicy.FallbackPolicy.RESTRICTED);
        assertThat(TaskAgentPolicy.difficulty(policy)).isEqualTo(TaskAgentPolicy.Difficulty.HIGH);
        assertThat(TaskAgentPolicy.isFallbackForbidden(policy)).isTrue();
    }

    @Test
    @DisplayName("非法枚举值回落默认，类型异常不抛错")
    void shouldFallbackOnIllegalEnumAndType() {
        Map<String, Object> policy = Map.of(
                TaskAgentPolicy.KEY_FALLBACK_POLICY, "whatever",
                TaskAgentPolicy.KEY_DIFFICULTY, 42,
                TaskAgentPolicy.KEY_EXECUTOR_AGENT_IDS, "not-a-list",
                TaskAgentPolicy.KEY_PLANNER_AGENT_ID, List.of(1));

        assertThat(TaskAgentPolicy.fallbackPolicy(policy)).isEqualTo(TaskAgentPolicy.FallbackPolicy.AUTO);
        assertThat(TaskAgentPolicy.difficulty(policy)).isEqualTo(TaskAgentPolicy.Difficulty.MEDIUM);
        assertThat(TaskAgentPolicy.executorAgentIds(policy)).isEmpty();
        assertThat(TaskAgentPolicy.plannerAgentId(policy)).isNull();
        assertThat(TaskAgentPolicy.isFallbackForbidden(policy)).isFalse();
    }

    @Test
    @DisplayName("isFallbackForbidden：NONE 或 HIGH 为 true，其余为 false")
    void shouldForbidFallbackOnNoneOrHigh() {
        assertThat(TaskAgentPolicy.isFallbackForbidden(
                TaskAgentPolicy.build(null, null, null, TaskAgentPolicy.FallbackPolicy.NONE, null))).isTrue();
        assertThat(TaskAgentPolicy.isFallbackForbidden(
                TaskAgentPolicy.build(null, null, null, null, TaskAgentPolicy.Difficulty.HIGH))).isTrue();
        assertThat(TaskAgentPolicy.isFallbackForbidden(
                TaskAgentPolicy.build(null, null, null, TaskAgentPolicy.FallbackPolicy.RESTRICTED, null))).isFalse();
        assertThat(TaskAgentPolicy.isFallbackForbidden(
                TaskAgentPolicy.build(null, null, null, TaskAgentPolicy.FallbackPolicy.AUTO, TaskAgentPolicy.Difficulty.LOW))).isFalse();
    }

    @Test
    @DisplayName("build：null/空值键不写入，落库形态与默认值语义一致")
    void shouldOmitNullAndEmptyKeysOnBuild() {
        Map<String, Object> policy = TaskAgentPolicy.build(
                null, List.of(11L), null, TaskAgentPolicy.FallbackPolicy.AUTO, null);

        assertThat(policy).containsOnlyKeys(TaskAgentPolicy.KEY_EXECUTOR_AGENT_IDS, TaskAgentPolicy.KEY_FALLBACK_POLICY);
        assertThat(policy.get(TaskAgentPolicy.KEY_EXECUTOR_AGENT_IDS)).isEqualTo(List.of(11L));
        // 显式写入 AUTO 后解析仍为 AUTO（与缺省语义一致）
        assertThat(TaskAgentPolicy.fallbackPolicy(policy)).isEqualTo(TaskAgentPolicy.FallbackPolicy.AUTO);
    }
}
