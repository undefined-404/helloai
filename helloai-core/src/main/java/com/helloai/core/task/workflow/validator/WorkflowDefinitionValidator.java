package com.helloai.core.task.workflow.validator;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Workflow 模板版本定义校验器（N-001，C1-S1，纯函数可单测）。
 *
 * <p>校验 {@code definition} JSONB（C1 D2 节点结构）：</p>
 * <ol>
 *   <li>{@code nodes} 必须为非空数组；</li>
 *   <li>每个节点必须有非空且唯一的 {@code nodeKey}；</li>
 *   <li>节点 {@code role} 必须在白名单（planner / executor / reviewer）；</li>
 *   <li>{@code dependsOn} 引用的 nodeKey 必须存在；</li>
 *   <li>依赖图必须无环（DAG，DFS 三色标记）。</li>
 * </ol>
 *
 * <p>返回空列表 = 校验通过；{@code paramsSchema} 结构与节点 spec 字段不做强校验
 * （运行期/实例化时容错，C1 设计 D6 context 容错原则）。</p>
 */
public final class WorkflowDefinitionValidator {

    /** 角色白名单（C1 D2：节点落到三角色，不绑 Agent 实例）。 */
    public static final Set<String> ROLES = Set.of("planner", "executor", "reviewer");

    private WorkflowDefinitionValidator() {
    }

    /**
     * 校验定义。
     *
     * @param definition definition JSONB（解析后的 Map）
     * @return 校验错误列表；空 = 通过
     */
    public static List<String> validate(Map<String, Object> definition) {
        List<String> errors = new ArrayList<>();
        if (definition == null) {
            errors.add("definition 不能为空");
            return errors;
        }
        Object nodesObj = definition.get("nodes");
        if (!(nodesObj instanceof List<?> nodes) || nodes.isEmpty()) {
            errors.add("nodes 必须为非空数组");
            return errors;
        }

        Map<String, Map<String, Object>> nodeMap = new LinkedHashMap<>();
        for (Object n : nodes) {
            if (!(n instanceof Map<?, ?> raw)) {
                errors.add("节点必须是对象");
                continue;
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> node = (Map<String, Object>) raw;
            Object keyObj = node.get("nodeKey");
            String key = keyObj instanceof String s ? s.trim() : null;
            if (key == null || key.isBlank()) {
                errors.add("节点缺少 nodeKey");
                continue;
            }
            if (nodeMap.containsKey(key)) {
                errors.add("nodeKey 重复: " + key);
            }
            nodeMap.put(key, node);
        }

        for (Map.Entry<String, Map<String, Object>> entry : nodeMap.entrySet()) {
            String key = entry.getKey();
            Map<String, Object> node = entry.getValue();
            Object roleObj = node.get("role");
            String role = roleObj instanceof String s ? s : null;
            if (role == null || !ROLES.contains(role)) {
                errors.add("节点 " + key + " role 非法（必须为 planner/executor/reviewer）: " + role);
            }
            Object depObj = node.get("dependsOn");
            if (depObj instanceof List<?> deps) {
                for (Object d : deps) {
                    if (!(d instanceof String depKey)) {
                        errors.add("节点 " + key + " dependsOn 元素必须为 nodeKey 字符串");
                        continue;
                    }
                    if (!nodeMap.containsKey(depKey)) {
                        errors.add("节点 " + key + " 依赖引用不存在的节点: " + depKey);
                    }
                }
            }
        }

        errors.addAll(detectCycle(nodeMap));
        return errors;
    }

    /**
     * DAG 无环检测（DFS 三色标记：0=未访问 / 1=访问中 / 2=完成）。
     */
    private static List<String> detectCycle(Map<String, Map<String, Object>> nodeMap) {
        Map<String, Integer> color = new HashMap<>();
        List<String> cycle = new ArrayList<>();
        for (String key : nodeMap.keySet()) {
            if (dfs(key, nodeMap, color, new ArrayList<>(), cycle)) {
                break;
            }
        }
        return cycle;
    }

    private static boolean dfs(String key, Map<String, Map<String, Object>> nodeMap,
                               Map<String, Integer> color, List<String> path, List<String> cycle) {
        Integer c = color.getOrDefault(key, 0);
        if (c == 2) {
            return false;
        }
        if (c == 1) {
            cycle.add("依赖图存在环: " + String.join(" -> ", path) + " -> " + key);
            return true;
        }
        color.put(key, 1);
        path.add(key);
        Object depObj = nodeMap.get(key).get("dependsOn");
        if (depObj instanceof List<?> deps) {
            for (Object d : deps) {
                if (d instanceof String depKey && nodeMap.containsKey(depKey)) {
                    if (dfs(depKey, nodeMap, color, path, cycle)) {
                        return true;
                    }
                }
            }
        }
        path.remove(path.size() - 1);
        color.put(key, 2);
        return false;
    }
}
