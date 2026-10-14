package com.helloai.core.agent.service;

import com.helloai.core.agent.dto.ApiKeyBatchSaveOutcome;
import com.helloai.core.agent.dto.ApiKeySaveOutcome;
import com.helloai.core.system.port.PlatformApiKeyChange;
import com.helloai.core.system.port.PlatformApiKeyView;

import java.util.List;

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

    // ── `M-8` 多 Key 主备：列表 / 手动切换 / 停用 / 删除（**无**自动 failover，红线 `N-1`）──

    /**
     * `M-8` 列出该 provider 的平台级 API Key 条目（主 / 备 / 历史，不含密文）。
     *
     * <p>只读，无需清理 ChatModel 缓存。</p>
     */
    List<PlatformApiKeyView> listApiKeys(String provider);

    /**
     * `M-8` **手动**把指定备位升为启用位（原启用位降为备位）。
     *
     * <p><b>红线 `N-1`</b>：平台级 Key 的切换**只由本方法这类显式管理动作触发** ——
     * 不做按健康度 / 配额 / 限流自动换 Key（不移植 magpie 的
     * {@code internal/gateway/fallback.go}），也不做加权 routing。</p>
     *
     * <p><b>为何编排落在本域而非 system 域</b>：KV 的启用位变了 ⇒ 缓存的 ChatModel
     * 实例持有的旧 Key 必须失效。{@code ProviderChatModelCache} 属 agent 域，
     * 而 system 域不得依赖 agent 域 ⇒ 由本域串起「改启用位 → 清缓存」两步。</p>
     *
     * @return 切换前后启用位快照
     */
    PlatformApiKeyChange activateApiKey(String provider, Long credentialId);

    /**
     * `M-8` **停用**平台级 Key。
     *
     * <p>停用**启用位**时会自动把最旧的一把备位顶为启用位；无备位可顶则 fail-close
     * （不得让 provider 静默变成「未配置」）。停用**备位**是幂等空操作。详见
     * {@code CredentialVaultService#deactivatePlatformApiKeyCredential}。</p>
     */
    PlatformApiKeyChange deactivateApiKey(String provider, Long credentialId);

    /**
     * `M-8` **删除**（软删）平台级 Key。删除启用位时同「停用」一样先顶替。
     */
    PlatformApiKeyChange deleteApiKey(String provider, Long credentialId);

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
