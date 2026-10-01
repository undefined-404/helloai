package com.helloai.core.agent;

import com.helloai.common.constant.AgentAccessType;
import com.helloai.core.agent.entity.Agent;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Agent 能力匹配工具类。
 *
 * <p>P3 能力画像：Agent 的 capabilities 是可独立覆盖的 Map，
 * 注册时按 accessType 默认值填充，调用方按需匹配。</p>
 */
public final class AgentCapability {

    private AgentCapability() {}

    /** 能力键：底层模型具备图片理解（多模态）。默认 false，注册时按模型实际能力覆盖。 */
    public static final String SUPPORTS_IMAGE_UNDERSTANDING =
            AgentAccessType.CAP_SUPPORTS_IMAGE_UNDERSTANDING;
    /** 能力键：底层模型具备音频理解（多模态）。默认 false，注册时按模型实际能力覆盖。 */
    public static final String SUPPORTS_AUDIO_UNDERSTANDING =
            AgentAccessType.CAP_SUPPORTS_AUDIO_UNDERSTANDING;
    /** 能力键：底层模型具备视频理解（多模态）。默认 false，注册时按模型实际能力覆盖。 */
    public static final String SUPPORTS_VIDEO_UNDERSTANDING =
            AgentAccessType.CAP_SUPPORTS_VIDEO_UNDERSTANDING;

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
     * @param key     能力名（如 supportsPull / supportsMCP / isSlow / supportsImageUnderstanding）
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
            try { return Integer.parseInt(s); } catch (NumberFormatException ignored) {
                // 解析失败按缺省处理（返回 null），调用方回退默认值
            }
        }
        return defaultValue;
    }

    /**
     * §6.52 本机执行能力判定：CLI_CLIENT / WEB_BROWSER 天然可本机操作；
     * API_KEY_LLM 需 {@code capabilities.supportsMCP=true}。
     *
     * <p><b>归属说明（2026-10-01 W8 归位）</b>：原为 {@code task.service.SubTaskDispatchService}
     * 的静态方法，但它判定依据**全部是 Agent 自身字段**（{@code accessType} + {@code capabilities}），
     * 属 agent 领域规则，故迁入本工具类。归位后：
     * <ul>
     *   <li>{@code agent} 侧（{@code ResilientDispatcher}）直接调用，不再 import task 域；</li>
     *   <li>{@code task} / {@code review} 侧调用成为顺向合法的 {@code *→agent} 依赖。</li>
     * </ul>
     * 判定语义与原实现<b>逐字一致</b>（含「值非 {@code Boolean.TRUE} 即视为无能力」这一严格口径，
     * 故此处刻意不复用 {@link #hasCapability} 的宽松解析）。</p>
     *
     * @param agent Agent；null 视为可本机执行（与原实现一致）
     * @return true-具备本机执行能力
     */
    public static boolean hasLocalExecutionCapability(Agent agent) {
        if (agent == null || agent.getAccessType() != AgentAccessType.API_KEY_LLM) {
            return true;
        }
        Object supportsMcp = agent.getCapabilities() != null
                ? agent.getCapabilities().get("supportsMCP") : null;
        return Boolean.TRUE.equals(supportsMcp);
    }
}
