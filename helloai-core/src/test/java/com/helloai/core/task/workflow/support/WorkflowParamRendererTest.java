package com.helloai.core.task.workflow.support;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link WorkflowParamRenderer} 纯函数单测（N-001，C1-S2）。
 */
@DisplayName("WorkflowParamRenderer 占位符渲染（C1-S2）")
class WorkflowParamRendererTest {

    @Test
    @DisplayName("{{key}} → params 值替换；缺失键保持原样")
    void shouldRenderPlaceholders() {
        Map<String, Object> params = Map.of("goal", "报表", "platform", "web");

        assertThat(WorkflowParamRenderer.render("开发 {{goal}} 模块（{{platform}}）", params))
                .isEqualTo("开发 报表 模块（web）");
        assertThat(WorkflowParamRenderer.render("含缺失键 {{ghost}} 保持原样", params))
                .isEqualTo("含缺失键 {{ghost}} 保持原样");
    }

    @Test
    @DisplayName("null 模板 / null params / 空 params 幂等返回")
    void shouldBeIdempotent() {
        assertThat(WorkflowParamRenderer.render(null, Map.of("a", "1"))).isNull();
        assertThat(WorkflowParamRenderer.render("原文 {{a}}", null)).isEqualTo("原文 {{a}}");
        assertThat(WorkflowParamRenderer.render("原文 {{a}}", Map.of())).isEqualTo("原文 {{a}}");
    }

    @Test
    @DisplayName("renderMap：递归渲染字符串叶子（含嵌套 Map 与 List），非字符串原样")
    void shouldRenderMapRecursively() {
        Map<String, Object> spec = new java.util.HashMap<>();
        spec.put("goal", "开发 {{goal}}");
        spec.put("estimated_effort", 2);
        spec.put("verification", Map.of("method", "检查 {{platform}} 页面"));
        spec.put("constraints", List.of("{{platform}}-only", Map.of("key", "{{goal}}")));

        Map<String, Object> rendered = WorkflowParamRenderer.renderMap(spec, Map.of("goal", "报表", "platform", "web"));

        assertThat(rendered.get("goal")).isEqualTo("开发 报表");
        assertThat(rendered.get("estimated_effort")).isEqualTo(2); // 非字符串原样
        assertThat(((Map<?, ?>) rendered.get("verification")).get("method")).isEqualTo("检查 web 页面");
        List<?> constraints = (List<?>) rendered.get("constraints");
        assertThat(constraints.get(0)).isEqualTo("web-only");
        assertThat(((Map<?, ?>) constraints.get(1)).get("key")).isEqualTo("报表");
    }
}
