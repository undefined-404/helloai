package com.helloai.core.task.workflow.validator;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link WorkflowDefinitionValidator} 纯函数单测（N-001，C1-S1）。
 *
 * <p>覆盖：nodes 非空 / nodeKey 唯一 / role 白名单 / dependsOn 引用存在 / DAG 无环。</p>
 */
@DisplayName("WorkflowDefinitionValidator 定义校验（C1-S1）")
class WorkflowDefinitionValidatorTest {

    private Map<String, Object> node(String key, String role, Object... dependsOn) {
        Map<String, Object> n = new java.util.HashMap<>();
        n.put("nodeKey", key);
        n.put("role", role);
        if (dependsOn.length > 0) {
            n.put("dependsOn", List.of(dependsOn));
        }
        return n;
    }

    private Map<String, Object> definition(Map<String, Object>... nodes) {
        return Map.of("nodes", List.of((Object[]) nodes));
    }

    @Nested
    @DisplayName("合法定义")
    class Valid {

        @Test
        @DisplayName("多节点无环依赖 → 校验通过（空错误）")
        void shouldPassValidDag() {
            Map<String, Object> def = definition(
                    node("contract", "executor"),
                    node("implement", "executor", "contract"),
                    node("review", "reviewer", "implement"));

            assertThat(WorkflowDefinitionValidator.validate(def)).isEmpty();
        }

        @Test
        @DisplayName("null definition → 报错")
        void shouldRejectNullDefinition() {
            assertThat(WorkflowDefinitionValidator.validate(null))
                    .contains("definition 不能为空");
        }
    }

    @Nested
    @DisplayName("非法定义")
    class Invalid {

        @Test
        @DisplayName("nodes 为空/非数组 → 报错")
        void shouldRejectEmptyNodes() {
            assertThat(WorkflowDefinitionValidator.validate(Map.of("nodes", List.of())))
                    .contains("nodes 必须为非空数组");
            assertThat(WorkflowDefinitionValidator.validate(Map.of()))
                    .contains("nodes 必须为非空数组");
        }

        @Test
        @DisplayName("nodeKey 缺失或重复 → 报错")
        void shouldRejectDuplicateOrMissingNodeKey() {
            Map<String, Object> dup = definition(
                    node("a", "executor"),
                    node("a", "executor"));
            assertThat(WorkflowDefinitionValidator.validate(dup)).contains("nodeKey 重复: a");

            Map<String, Object> missing = definition(Map.of("role", "executor"));
            assertThat(WorkflowDefinitionValidator.validate(missing)).contains("节点缺少 nodeKey");
        }

        @Test
        @DisplayName("role 不在白名单 → 报错")
        void shouldRejectInvalidRole() {
            Map<String, Object> def = definition(node("a", "coordinator"));
            assertThat(WorkflowDefinitionValidator.validate(def))
                    .anyMatch(e -> e.contains("role 非法"));
        }

        @Test
        @DisplayName("dependsOn 引用不存在的节点 → 报错")
        void shouldRejectUnknownDependency() {
            Map<String, Object> def = definition(node("a", "executor", "ghost"));
            assertThat(WorkflowDefinitionValidator.validate(def))
                    .contains("节点 a 依赖引用不存在的节点: ghost");
        }

        @Test
        @DisplayName("依赖图存在环（A→B→A）→ 报错")
        void shouldRejectCycle() {
            Map<String, Object> def = definition(
                    node("a", "executor", "b"),
                    node("b", "executor", "a"));
            assertThat(WorkflowDefinitionValidator.validate(def))
                    .anyMatch(e -> e.contains("存在环"));
        }

        @Test
        @DisplayName("自环 → 报错")
        void shouldRejectSelfCycle() {
            Map<String, Object> def = definition(node("a", "executor", "a"));
            assertThat(WorkflowDefinitionValidator.validate(def))
                    .anyMatch(e -> e.contains("存在环"));
        }
    }
}
