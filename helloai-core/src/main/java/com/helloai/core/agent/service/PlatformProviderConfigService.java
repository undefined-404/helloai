package com.helloai.core.agent.service;

/**
 * Provider 平台级配置服务：API Key / Base URL / 默认模型的读取与保存。
 *
 * <p>读取优先级：{@code llm_provider} 表（DB）&gt; sys_config &gt; yml；API Key 优先走
 * 凭证库（vault）PLATFORM 级 ACTIVE 凭证，回退 yml。</p>
 */
public interface PlatformProviderConfigService {

    /**
     * 读取 provider 的平台级 API Key 明文：vault PLATFORM 级 ACTIVE 凭证 &gt; yml 兜底。
     *
     * @return 明文；未配置返回 null
     */
    String getApiKey(String provider);

    /**
     * 读取 provider 的 Base URL：{@code llm_provider.base_url}（DB）&gt; sys_config &gt; yml &gt; null。
     */
    String getBaseUrl(String provider);

    /**
     * 读取 provider 的默认模型：{@code llm_provider.default_model}（DB）&gt; sys_config &gt; yml &gt; null。
     */
    String getDefaultModel(String provider);

    /**
     * 保存 provider 的平台级 API Key（写 vault，明文明文落库前加密）。
     */
    void saveApiKey(String provider, String apiKey);

    /**
     * 保存 provider 的 Base URL 与默认模型（写 sys_config）。
     */
    void saveSettings(String provider, String baseUrl, String defaultModel);

    /**
     * 判断 provider 是否已配置平台级 API Key。
     */
    boolean isApiKeyConfigured(String provider);

    /**
     * 返回 provider 平台级 API Key 的脱敏展示值（无配置返回 null）。
     */
    String maskApiKey(String provider);

    /**
     * 判断 provider 的 API Key 是否来自 vault（false=来自 yml 兜底）。
     */
    boolean isApiKeyFromVault(String provider);
}
