package com.helloai.core.task.policy;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * {@code task.agent_policy} JSONB 的静态解析工具（§6.58 P1）。
 *
 * <p>任务级 Agent 指定策略的全部读取/判定统一收口到本类，避免各处散落
 * Map 取值与防御式类型转换。键结构与默认值语义：
 * <ul>
 *   <li>{@code plannerAgentId}：指定拆解/澄清 Planner（失效回退自动选择）；</li>
 *   <li>{@code executorAgentIds[]}：执行者白名单（为空=不限定）；</li>
 *   <li>{@code reviewerAgentId}：指定自动核验 Reviewer（失效回退自动选择）；</li>
 *   <li>{@code fallbackPolicy}：AUTO（默认）/ RESTRICTED / NONE；</li>
 *   <li>{@code difficulty}：LOW / MEDIUM（默认）/ HIGH。</li>
 * </ul>
 * 所有解析均为防御式：policy 为 null、键缺失、类型异常时一律回落默认值，
 * 与旧数据（默认 {@code {}}）行为完全一致。</p>
 */
public final class TaskAgentPolicy {

    private TaskAgentPolicy() {
    }

    /** policy 键：指定拆解/澄清 Planner Agent ID。 */
    public static final String KEY_PLANNER_AGENT_ID = "plannerAgentId";

    /** policy 键：执行者白名单（JSONB 数组）。 */
    public static final String KEY_EXECUTOR_AGENT_IDS = "executorAgentIds";

    /** policy 键：指定自动核验 Reviewer Agent ID。 */
    public static final String KEY_REVIEWER_AGENT_ID = "reviewerAgentId";

    /** policy 键：N11 回退策略。 */
    public static final String KEY_FALLBACK_POLICY = "fallbackPolicy";

    /** policy 键：任务难度。 */
    public static final String KEY_DIFFICULTY = "difficulty";

    /** N11 外部 Agent 失败后的保底回退策略。 */
    public enum FallbackPolicy {
        /** 默认：N11 正常回退 API_KEY_LLM 保底。 */
        AUTO,
        /** 仅回退 executorAgentIds 内的 API_KEY_LLM Agent；集合为空或其中无 API_KEY_LLM 时等同于 NONE。 */
        RESTRICTED,
        /** 禁止 N11 自动回退，改打人工介入标记。 */
        NONE
    }

    /** 任务难度。 */
    public enum Difficulty {
        LOW,
        /** 默认。 */
        MEDIUM,
        /** 高风险任务：视为禁止 N11 自动回退，改打人工介入标记。 */
        HIGH
    }

    /** 指定拆解/澄清 Planner Agent ID；未指定或类型异常返回 null。 */
    public static Long plannerAgentId(Map<String, Object> policy) {
        return asLong(raw(policy, KEY_PLANNER_AGENT_ID));
    }

    /** 执行者白名单；未指定返回空列表（调用方视为不限定）。 */
    public static List<Long> executorAgentIds(Map<String, Object> policy) {
        Object raw = raw(policy, KEY_EXECUTOR_AGENT_IDS);
        if (!(raw instanceof List<?> list)) {
            return List.of();
        }
        return list.stream().map(TaskAgentPolicy::asLong).filter(Objects::nonNull).toList();
    }

    /** 指定自动核验 Reviewer Agent ID；未指定或类型异常返回 null。 */
    public static Long reviewerAgentId(Map<String, Object> policy) {
        return asLong(raw(policy, KEY_REVIEWER_AGENT_ID));
    }

    /** N11 回退策略；未指定或非法值回落 AUTO。 */
    public static FallbackPolicy fallbackPolicy(Map<String, Object> policy) {
        String value = asString(raw(policy, KEY_FALLBACK_POLICY));
        if (value == null) {
            return FallbackPolicy.AUTO;
        }
        try {
            return FallbackPolicy.valueOf(value.toUpperCase());
        } catch (IllegalArgumentException e) {
            return FallbackPolicy.AUTO;
        }
    }

    /** 任务难度；未指定或非法值回落 MEDIUM。 */
    public static Difficulty difficulty(Map<String, Object> policy) {
        String value = asString(raw(policy, KEY_DIFFICULTY));
        if (value == null) {
            return Difficulty.MEDIUM;
        }
        try {
            return Difficulty.valueOf(value.toUpperCase());
        } catch (IllegalArgumentException e) {
            return Difficulty.MEDIUM;
        }
    }

    /**
     * 是否禁止 N11 自动回退：fallbackPolicy=NONE 或 difficulty=HIGH。
     *
     * <p>此时外部 Agent 连续失败不再自动转 API_KEY_LLM 保底，改打人工介入标记，
     * 避免"高风险/明确禁止回退的任务被静默换人执行"。</p>
     */
    public static boolean isFallbackForbidden(Map<String, Object> policy) {
        return fallbackPolicy(policy) == FallbackPolicy.NONE
                || difficulty(policy) == Difficulty.HIGH;
    }

    /**
     * 构建 policy Map（测试与任务创建入口写库用）。
     *
     * <p>null/空值键不写入，保证落库形态与默认值语义一致（缺省即默认）。</p>
     */
    public static Map<String, Object> build(Long plannerAgentId, List<Long> executorAgentIds,
                                            Long reviewerAgentId, FallbackPolicy fallbackPolicy,
                                            Difficulty difficulty) {
        Map<String, Object> policy = new LinkedHashMap<>();
        if (plannerAgentId != null) {
            policy.put(KEY_PLANNER_AGENT_ID, plannerAgentId);
        }
        if (executorAgentIds != null && !executorAgentIds.isEmpty()) {
            policy.put(KEY_EXECUTOR_AGENT_IDS, executorAgentIds);
        }
        if (reviewerAgentId != null) {
            policy.put(KEY_REVIEWER_AGENT_ID, reviewerAgentId);
        }
        if (fallbackPolicy != null) {
            policy.put(KEY_FALLBACK_POLICY, fallbackPolicy.name());
        }
        if (difficulty != null) {
            policy.put(KEY_DIFFICULTY, difficulty.name());
        }
        return policy;
    }

    private static Object raw(Map<String, Object> policy, String key) {
        return policy == null ? null : policy.get(key);
    }

    /** JSONB 反序列化后整数可能是 Integer/Long/BigDecimal，统一按 Number 取 long；字符串兜底。 */
    private static Long asLong(Object value) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        if (value instanceof String s) {
            try {
                return Long.parseLong(s.trim());
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return null;
    }

    private static String asString(Object value) {
        return value == null ? null : value.toString();
    }
}
