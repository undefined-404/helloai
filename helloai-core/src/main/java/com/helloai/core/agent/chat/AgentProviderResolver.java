package com.helloai.core.agent.chat;

import com.helloai.core.agent.entity.Agent;

/**
 * Provider 和 Model 解析工具。
 *
 * <p>从 Agent.modelType 中解析 provider 和 model。
 * modelType 格式为 {@code provider:model}（如 {@code deepseek:deepseek-chat}）。</p>
 */
public final class AgentProviderResolver {

    private AgentProviderResolver() {
    }

    /**
     * 从 Agent 解析 provider 名称。
     *
     * @param agent    Agent 实体
     * @param fallback 当 modelType 为空时的默认值
     * @return provider 名称，不会为 null
     */
    public static String resolveProvider(Agent agent, String fallback) {
        return resolveProvider(agent.getModelType(), fallback);
    }

    /**
     * 从 {@code modelType} 原文解析 provider 名称（RM5 批 2：供只读快照消费方复用）。
     *
     * <p>与 {@link #resolveProvider(Agent, String)} 解析口径<b>逐字一致</b>，
     * 仅把入参从实体收窄为 {@code modelType} 字符串，使持有 {@code AgentProfileSnapshot}
     * 的调用方无需取实体即可解析 provider。</p>
     *
     * @param modelType 形如 {@code provider:model}；null/blank 时返回 fallback
     * @param fallback  当 modelType 为空时的默认值
     * @return provider 名称，不会为 null
     */
    public static String resolveProvider(String modelType, String fallback) {
        if (modelType == null || modelType.isBlank()) {
            return fallback;
        }
        int separator = modelType.indexOf(':');
        if (separator > 0) {
            return modelType.substring(0, separator);
        }
        return modelType;
    }

    /**
     * 从 Agent 解析 model 名称。
     *
     * @param agent    Agent 实体
     * @param fallback 当 modelType 为空时的默认值
     * @return model 名称，可能为 null（交由 factory 使用其默认值）
     */
    public static String resolveModel(Agent agent, String fallback) {
        String modelType = agent.getModelType();
        if (modelType == null || modelType.isBlank()) {
            return fallback;
        }
        int separator = modelType.indexOf(':');
        if (separator > 0 && separator + 1 < modelType.length()) {
            return modelType.substring(separator + 1);
        }
        return null;
    }
}
