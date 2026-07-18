package com.helloai.core.util;

import com.helloai.common.constant.AgentAccessType;
import com.helloai.core.agent.entity.Agent;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Agent 能力匹配工具类。
 *
 * <p>v2.4 P3 能力画像：Agent 的 capabilities 是可独立覆盖的 Map，
 * 注册时按 accessType 默认值填充，调用方按需匹配。</p>
 */
public final class AgentCapability {

    private AgentCapability() {}

    /**
     * 合并默认值与覆盖值（覆盖优先，缺失则取默认）。
     *
     * @param type     接入类型（决定默认 capabilities）
     * @param override 覆盖值（可为 null 或空）
     * @return 合并后的 Map
     */
    public static Map<String, Object> mergeDefaults(AgentAccessType type, Map<String, Object> override) {
        Map<String, Object> merged = new HashMap<>(type.defaultCapabilities());
        if (override != null && !override.isEmpty()) {
            merged.putAll(override);
        }
        return merged;
    }

    /**
     * 判断 Agent 是否具备指定能力。
     *
     * @param agent   Agent
     * @param key     能力名（如 supportsPull / supportsMCP / isSlow）
     * @return true-具备（值为 true）/ 能力未配置视为 false
     */
    public static boolean hasCapability(Agent agent, String key) {
        Map<String, Object> caps = agent.getCapabilities();
        if (caps == null || !caps.containsKey(key)) return false;
        Object v = caps.get(key);
        if (v instanceof Boolean b) return b;
        if (v instanceof Number n) return n.intValue() != 0;
        return Boolean.parseBoolean(String.valueOf(v));
    }

    /**
     * 判断 Agent 是否同时具备多个能力。
     */
    public static boolean hasAllCapabilities(Agent agent, List<String> keys) {
        if (keys == null || keys.isEmpty()) return true;
        for (String k : keys) {
            if (!hasCapability(agent, k)) return false;
        }
        return true;
    }

    /**
     * 判断 Agent 是否具备任一能力。
     */
    public static boolean hasAnyCapability(Agent agent, List<String> keys) {
        if (keys == null || keys.isEmpty()) return false;
        for (String k : keys) {
            if (hasCapability(agent, k)) return true;
        }
        return false;
    }

    /**
     * 取数值型能力（如 maxConcurrentTasks）。
     *
     * @param defaultValue 缺失或非数值时返回的默认值
     */
    public static int getIntCapability(Agent agent, String key, int defaultValue) {
        Map<String, Object> caps = agent.getCapabilities();
        if (caps == null) return defaultValue;
        Object v = caps.get(key);
        if (v instanceof Number n) return n.intValue();
        if (v instanceof String s) {
            try { return Integer.parseInt(s); } catch (NumberFormatException ignored) {}
        }
        return defaultValue;
    }
}
