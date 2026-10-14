package com.helloai.core.agent.service;

import com.helloai.core.agent.dto.ApiKeyBatchSaveOutcome;
import com.helloai.core.agent.dto.ApiKeySaveOutcome;

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
     *
     * <p>`M-6` 起带**同 provider 内同 Key 去重**：若该 provider 下已存在同指纹凭证
     * （含历史 EXPIRED/DISABLED 行），则**不重复落库**，返回
     * {@link ApiKeySaveOutcome#created()} = {@code false}。</p>
     *
     * @return 保存结果（是否新增 + 指纹），供调用方提示「已存在」
     */
    ApiKeySaveOutcome saveApiKey(String provider, String apiKey);

    /**
     * `M-7` 批量保存平台级 API Key：把一次粘贴的文本拆成多把，逐把去重后落库。
     *
     * <p><b>拆分</b>：{@link com.helloai.core.shared.util.KeyTextSplitter}（迁自 magpie
     * {@code internal/provider/keys.go:165-178}），支持换行 / 逗号 / 分号 / 空格分隔，
     * 两端剥引号，输入内**去重且保序**。</p>
     *
     * <p><b>去重</b>：与单把 {@link #saveApiKey} 同一口径 —— 作用域 = owner + provider，
     * 比对 `M-6` 指纹，**含历史 EXPIRED/DISABLED 行**；命中即跳过、不重复落库。</p>
     *
     * <p><b>落库形态</b>（对齐 magpie {@code AddKeys} {@code keys.go:183-222}）：该 provider
     * **尚无 ACTIVE 凭证** ⇒ 本批**首把**升为 ACTIVE（"首把为主"）；**已有 ACTIVE** ⇒ 本批新增
     * 一律落 `status='DISABLED'` **备位**，**不覆盖正在使用的主 Key**。备位转正属 `M-8`
     * （多 Key 主备），本批只负责"存进来 + 报清楚"。</p>
     *
     * @param provider LLM Provider
     * @param rawText  粘贴的原始文本（可含多把 Key）
     * @return 批量结果（新增把数 / 已存在把数 / 是否升了首把 / 新增指纹列表）
     * @throws com.helloai.common.base.BizException provider 为空，或文本中未解析出任何 Key
     */
    ApiKeyBatchSaveOutcome saveApiKeys(String provider, String rawText);

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
