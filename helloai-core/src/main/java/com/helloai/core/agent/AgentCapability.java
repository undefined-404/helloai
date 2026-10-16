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

    /**
     * `LOG-20261011-007` A 能力闸：判断 Agent 是否具备「<b>读取并解析原始附件</b>」的能力。
     *
     * <p><b>为什么需要独立命名（而不直接在调用点复用 {@link #hasLocalExecutionCapability}）</b>：
     * 两者当前判据相同，但<b>问题域不同</b>——前者问「能不能在本机跑东西」，本方法问
     * 「能不能打开用户给的结构化文件本体」。独立命名让语义在调用点自解释，
     * 且未来若出现「只能读结构化附件、但不能跑 shell」的新接入类型（或反之），
     * 只需在本方法内扩展，<b>调用方零改动</b>。</p>
     *
     * <p><b>当前判据 = 本机执行能力</b>（委托 {@link #hasLocalExecutionCapability}，判据单源防漂移）：</p>
     * <ul>
     *   <li>{@code CLI_CLIENT} / {@code WEB_BROWSER} ⇒ {@code true}（自己有文件系统 / 浏览器，能真打开文件）；</li>
     *   <li>{@code API_KEY_LLM} ⇒ 仅当 {@code capabilities.supportsMCP == Boolean.TRUE}（可挂结构化解析类 MCP 工具）。</li>
     * </ul>
     *
     * <p><b>为什么 API_KEY_LLM 默认判「无能力」</b>：内部执行者虽有
     * {@code internal_read_attachment}（`REF-8.1` 交付），但其实现是
     * {@code new String(content, UTF_8)}（{@code InternalLlmExecutorTools}）——
     * 对 xlsx / docx / pdf 等<b>二进制</b>结构化附件只能得到乱码，拿不到结构化列。
     * ⇒ 缺口不在「有没有读通道」，而在「通道不支持结构化解析」。详见
     * `doc/log/2026-10.md` `LOG-20261011-007` 及 2026-10-16 前提订正条目。</p>
     *
     * @param agent Agent；null 视为具备（与 {@link #hasLocalExecutionCapability} 同口径，
     *              保证「缺失提交者不误拦」的既有语义）
     * @return true-具备读取并解析原始附件的能力
     */
    public static boolean hasStructuredAttachmentReadCapability(Agent agent) {
        // 判据单源：当前与「本机执行能力」同判据；差异只在语义与未来扩展点。
        return hasLocalExecutionCapability(agent);
    }
}
