package com.helloai.core.agent.service;

import com.helloai.core.agent.entity.Agent;

import java.util.List;

/**
 * LLM Provider 目录服务：枚举平台已配置的 provider 及可用性，支持按 provider 绑定平台级 API Key。
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
     * 为 Agent 绑定平台级 API Key（仅当 Agent 未配置自身 API Key 时绑定）。
     *
     * @return true=绑定成功或已存在绑定；false=平台无可用 API Key
     */
    boolean bindPlatformApiKeyIfAbsent(Long agentId, String provider);

    /**
     * 为 Agent 预置平台级凭证（外部 Agent 注册/上线前调用）。
     */
    boolean provisionPlatformCredential(Agent agent);
}
