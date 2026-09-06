package com.helloai.core.task.workflow.support;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Workflow 实例化占位符渲染（N-001，C1-S2，纯函数可单测）。
 *
 * <p>把模板文本中的 {@code {{key}}} 替换为 params 对应值（C1 设计 D5 参数化）；
 * 缺失键保持原样（幂等，不报错）——title 必填等硬约束由实例化链路校验兜底。</p>
 */
public final class WorkflowParamRenderer {

    private WorkflowParamRenderer() {
    }

    /**
     * 渲染单个字符串：{@code {{key}}} → params.get(key)。
     */
    public static String render(String template, Map<String, Object> params) {
        if (template == null || params == null || params.isEmpty()) {
            return template;
        }
        String result = template;
        for (Map.Entry<String, Object> e : params.entrySet()) {
            result = result.replace("{{" + e.getKey() + "}}",
                    e.getValue() == null ? "" : String.valueOf(e.getValue()));
        }
        return result;
    }

    /**
     * 渲染 Map（节点 spec / taskDefaults）内所有字符串叶子值；空 Map 原样返回。
     */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> renderMap(Map<String, Object> source, Map<String, Object> params) {
        if (source == null || source.isEmpty()) {
            return source;
        }
        Map<String, Object> out = new LinkedHashMap<>();
        for (Map.Entry<String, Object> e : source.entrySet()) {
            Object v = e.getValue();
            if (v instanceof String s) {
                out.put(e.getKey(), render(s, params));
            } else if (v instanceof Map<?, ?> m) {
                out.put(e.getKey(), renderMap((Map<String, Object>) m, params));
            } else if (v instanceof java.util.List<?> list) {
                java.util.List<Object> rendered = new java.util.ArrayList<>();
                for (Object item : list) {
                    if (item instanceof String s) {
                        rendered.add(render(s, params));
                    } else if (item instanceof Map<?, ?> m) {
                        rendered.add(renderMap((Map<String, Object>) m, params));
                    } else {
                        rendered.add(item);
                    }
                }
                out.put(e.getKey(), rendered);
            } else {
                out.put(e.getKey(), v);
            }
        }
        return out;
    }
}
