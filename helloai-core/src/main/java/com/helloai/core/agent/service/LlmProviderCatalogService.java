package com.helloai.core.agent.service;

import com.helloai.core.agent.entity.Agent;

import java.util.List;

/**
 * LLM Provider 目录服务：枚举平台已配置的 provider 及可用性。
 */
public interface LlmProviderCatalogService {

    /** Provider 目录项（对外展示用）。 */
    record ProviderCatalogItem(String provider, String providerName, String protocolType,
                               String defaultModel,
                               boolean apiKeyConfigured, boolean factorySupported,
                               boolean available) {
    }

    /**
     * 枚举全部已配置的 provider 及其可用性。
     */
    List<ProviderCatalogItem> listProviders();

    /**
     * 判断指定 provider 当前是否可用（apiKey 已配置且工厂支持且启用）。
     */
    boolean isProviderAvailable(String provider);

    /**
     * 为 API_KEY_LLM Agent 核对注册前的平台凭证就绪性（内部 LLM Agent 注册后置检查）。
     *
     * <p>内部 LLM Agent 不再持有 AGENT 级 API Key 副本，执行链按 modelType 关联
     * 平台级凭证实时取 Key（见 AgentLlmCredentialResolver）；本方法仅确认
     * provider 可用，返回 false 表示跳过（不阻断注册）。</p>
     */
    boolean provisionPlatformCredential(Agent agent);
}
