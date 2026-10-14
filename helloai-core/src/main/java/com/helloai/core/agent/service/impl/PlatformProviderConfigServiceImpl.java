package com.helloai.core.agent.service.impl;

import com.helloai.core.agent.service.PlatformProviderConfigService;
import com.helloai.core.agent.dto.ApiKeyBatchSaveOutcome;
import com.helloai.core.agent.dto.ApiKeySaveOutcome;
import com.helloai.common.base.BizException;
import com.helloai.common.config.AgentProviderProperties;
import com.helloai.common.constant.CredentialOwnerType;
import com.helloai.common.crypto.CredentialCryptoService;
import com.helloai.core.shared.util.KeyTextSplitter;
import com.helloai.core.system.crypto.CredentialFingerprint;
import com.helloai.core.agent.chat.provider.ProviderChatModelCache;
import com.helloai.core.system.port.CredentialSecret;
import com.helloai.core.system.port.LlmProviderProfile;
import com.helloai.core.system.service.CredentialVaultService;
import com.helloai.core.system.service.LlmProviderQueryService;
import com.helloai.core.system.service.SysConfigService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;

/**
 * 平台级 LLM Provider 配置服务（先启动后配置）。
 *
 * <p>目标态：第一次部署只需环境变量 {@code HELLOAI_CREDENTIAL_AES_KEY_BASE64}，
 * LLM Provider 的 API Key / Base URL / 默认模型由管理员登录后在"系统设置"页填写/轮换，
 * 写入 credential_vault（PLATFORM 级，AES-GCM 加密）与 sys_config，实时生效无需重启。</p>
 *
 * <p>数据源优先级（方案B扩展）：</p>
 * <ul>
 *   <li>API Key：vault PLATFORM 级 ACTIVE 凭证（解密明文） &gt; yml {@code helloai.providers.<name>.api-key} &gt; null</li>
 *   <li>Base URL：{@code llm_provider.base_url}（DB）&gt; sys_config {@code llm.provider.<name>.base-url} &gt; yml &gt; null</li>
 *   <li>默认模型：{@code llm_provider.default_model}（DB）&gt; sys_config {@code llm.provider.<name>.default-model} &gt; yml &gt; null</li>
 * </ul>
 *
 * <p>兼容性：老环境（yml 已配 key、vault 无 PLATFORM 记录、llm_provider 表无记录）回退 yml，
 * 行为与现状一致；删除 vault PLATFORM 记录即回到 yml 配置行为。
 * 已迁移到 {@code llm_provider} 表的环境：DB 优先，三段兜底，先到先得。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PlatformProviderConfigServiceImpl implements PlatformProviderConfigService {

    /** PLATFORM 级凭证的固定占位归属 ID（与 vault 侧平台级查询口径一致）。 */
    private static final long PLATFORM_OWNER_ID = 0L;

    private final CredentialVaultService credentialVaultService;
    private final CredentialCryptoService credentialCryptoService;
    private final SysConfigService sysConfigService;
    private final AgentProviderProperties providerProperties;
    private final ProviderChatModelCache providerChatModelCache;
    private final LlmProviderQueryService llmProviderQueryService;

    /**
     * 读取 provider 的平台级 API Key 明文：vault PLATFORM 级 ACTIVE 凭证 &gt; yml 兜底。
     *
     * @return 明文；未配置返回 null
     */
    public String getApiKey(String provider) {
        CredentialSecret vault = credentialVaultService.getActivePlatformApiKeySecret(provider);
        if (vault != null) {
            if (vault.secretRef() != null && !vault.secretRef().isBlank()) {
                String env = System.getenv(vault.secretRef());
                if (env == null || env.isBlank()) {
                    log.warn("平台级凭证 secretRef 指向的环境变量为空，回退 yml: provider={}, secretRef={}",
                            provider, vault.secretRef());
                } else {
                    return env;
                }
            } else if (vault.encryptedValue() != null && !vault.encryptedValue().isBlank()) {
                return credentialCryptoService.decryptFromBase64(vault.encryptedValue());
            }
        }
        String ymlKey = providerProperties.getConfig(provider).getApiKey();
        return ymlKey != null && !ymlKey.isBlank() ? ymlKey : null;
    }

    /**
     * 读取 provider 的 Base URL：{@code llm_provider.base_url}（DB）&gt; sys_config &gt; yml &gt; null。
     *
     * <p>DB 路径由 {@link LlmProviderQueryService#findByCode} 提供；若 provider 不在
     * {@code llm_provider} 表（未迁移的老数据），则回退 sys_config + yml 兜底，
     * 保持老环境兼容。</p>
     */
    public String getBaseUrl(String provider) {
        LlmProviderProfile lp = llmProviderQueryService.findProfileByCode(provider).orElse(null);
        if (lp != null && lp.baseUrl() != null && !lp.baseUrl().isBlank()) {
            return lp.baseUrl();
        }
        String sysValue = sysConfigService.getValue(sysConfigKey(provider, "base-url"));
        if (sysValue != null && !sysValue.isBlank()) {
            return sysValue;
        }
        String ymlValue = providerProperties.getConfig(provider).getBaseUrl();
        return ymlValue != null && !ymlValue.isBlank() ? ymlValue : null;
    }

    /**
     * 读取 provider 的默认模型：{@code llm_provider.default_model}（DB）&gt; sys_config &gt; yml &gt; null。
     */
    public String getDefaultModel(String provider) {
        LlmProviderProfile lp = llmProviderQueryService.findProfileByCode(provider).orElse(null);
        if (lp != null && lp.defaultModel() != null && !lp.defaultModel().isBlank()) {
            return lp.defaultModel();
        }
        String sysValue = sysConfigService.getValue(sysConfigKey(provider, "default-model"));
        if (sysValue != null && !sysValue.isBlank()) {
            return sysValue;
        }
        String ymlValue = providerProperties.getConfig(provider).getDefaultModel();
        return ymlValue != null && !ymlValue.isBlank() ? ymlValue : null;
    }

    /**
     * 保存（轮换）平台级 API Key：AES 加密 → vault PLATFORM 级 rotate → 清空 ChatModel 缓存。
     *
     * <p>`M-6` 起带**同 provider 内同 Key 去重**：写入前先把该 provider 的历史行指纹补齐
     * （`V111` 之前的行指纹为 NULL，由 {@code backfillFingerprints} 自愈），再判定是否已有
     * 同指纹凭证；命中则**不重复落库**，返回 {@code created=false} + 「已存在」文案。</p>
     *
     * <p>缓存全清后旧实例不再命中，下次调用按新 key 自动重建；正在执行的调用持有旧实例引用，
     * 完成后无引用即被 GC，可接受。</p>
     *
     * @return 保存结果（是否新增 + 指纹）
     */
    @Transactional(rollbackFor = Exception.class)
    public ApiKeySaveOutcome saveApiKey(String provider, String apiKey) {
        if (provider == null || provider.isBlank()) {
            throw new BizException("provider 不能为空");
        }
        if (apiKey == null || apiKey.isBlank()) {
            throw new BizException("apiKey 不能为空");
        }
        String fingerprint = CredentialFingerprint.of(apiKey);
        // `M-6`：先补齐历史行指纹（幂等），否则「二次录入」对升级前录入过的 Key 判不出来
        credentialVaultService.backfillFingerprints(
                CredentialOwnerType.PLATFORM, PLATFORM_OWNER_ID, provider);
        if (credentialVaultService.existsFingerprint(
                CredentialOwnerType.PLATFORM, PLATFORM_OWNER_ID, provider, fingerprint)) {
            log.info("平台级 API Key 已存在，跳过写入: provider={}, fingerprint={}", provider, fingerprint);
            return ApiKeySaveOutcome.ofAlreadyExists(fingerprint);
        }
        String encrypted = credentialCryptoService.encryptToBase64(apiKey);
        credentialVaultService.rotatePlatformApiKey(provider, encrypted, null,
                "平台配置动态化（系统设置页写入）", fingerprint);
        providerChatModelCache.clear();
        log.info("平台级 API Key 已轮换并清理 ChatModel 缓存: provider={}, fingerprint={}",
                provider, fingerprint);
        return ApiKeySaveOutcome.ofCreated(fingerprint);
    }

    /**
     * `M-7` 批量保存平台级 API Key。
     *
     * <p>流程：拆分（{@link KeyTextSplitter}，迁自 magpie {@code SplitKeys}）→ 补齐历史行指纹
     * （同 `M-6` 前置）→ **一次性全批判重**（分类为"新增 / 已存在"）→ 按「是否已有启用位」决定
     * 首把升 ACTIVE 还是全部落 DISABLED 备位 → 计数返回。</p>
     *
     * <p><b>为何先全批判重、再统一落库</b>：本批各把互不相同（{@link KeyTextSplitter} 已按
     * magpie 口径在输入内去重），故"先全判"与"边判边落"在本批内**结果相同**；采用前者是为了让
     * 计数**不依赖"拆分器一定会去重"这一前提** —— 若将来拆分策略放宽（例如允许同批重复），
     * 边判边落的写法会把同批刚落库的重复把计成"已存在"，使「新增 N 把」静默退化。</p>
     */
    @Transactional(rollbackFor = Exception.class)
    public ApiKeyBatchSaveOutcome saveApiKeys(String provider, String rawText) {
        if (provider == null || provider.isBlank()) {
            throw new BizException("provider 不能为空");
        }
        List<String> keys = KeyTextSplitter.split(rawText);
        if (keys.isEmpty()) {
            throw new BizException("未解析到任何 API Key（支持换行 / 逗号 / 分号 / 空格分隔）");
        }
        // `M-6` 同款前置：先补齐历史行指纹，否则「升级前录入过的 Key」判不出重复
        credentialVaultService.backfillFingerprints(
                CredentialOwnerType.PLATFORM, PLATFORM_OWNER_ID, provider);

        List<String> newKeys = new ArrayList<>();
        int alreadyExists = 0;
        for (String key : keys) {
            String fingerprint = CredentialFingerprint.of(key);
            if (credentialVaultService.existsFingerprint(
                    CredentialOwnerType.PLATFORM, PLATFORM_OWNER_ID, provider, fingerprint)) {
                alreadyExists++;
            } else {
                newKeys.add(key);
            }
        }
        if (newKeys.isEmpty()) {
            log.info("批量录入：全部已存在，未落任何行: provider={}, alreadyExists={}", provider, alreadyExists);
            return ApiKeyBatchSaveOutcome.of(0, alreadyExists, false, List.of());
        }

        // 已有启用位 ⇒ 本批新增全部进备位（不覆盖正在用的主 Key，对齐 magpie AddKeys）
        boolean hadActive = credentialVaultService.hasActivePlatformCredential(provider);
        boolean primaryActivated = false;
        List<String> addedFingerprints = new ArrayList<>(newKeys.size());
        for (int i = 0; i < newKeys.size(); i++) {
            String key = newKeys.get(i);
            String fingerprint = CredentialFingerprint.of(key);
            String encrypted = credentialCryptoService.encryptToBase64(key);
            if (!hadActive && i == 0) {
                credentialVaultService.rotatePlatformApiKey(provider, encrypted, null,
                        "`M-7` 批量录入（首把升为启用位）", fingerprint);
                primaryActivated = true;
            } else {
                credentialVaultService.saveDisabledPlatformApiKeyCredential(provider, encrypted,
                        "`M-7` 批量录入（备位，待 `M-8` 启用）", fingerprint);
            }
            addedFingerprints.add(fingerprint);
        }
        providerChatModelCache.clear();
        log.info("批量录入完成: provider={}, added={}, alreadyExists={}, primaryActivated={}",
                provider, addedFingerprints.size(), alreadyExists, primaryActivated);
        return ApiKeyBatchSaveOutcome.of(addedFingerprints.size(), alreadyExists,
                primaryActivated, addedFingerprints);
    }

    /**
     * 保存 provider 的 Base URL / 默认模型到 sys_config（传空表示清除覆盖，回到 yml 默认）。
     */
    @Transactional(rollbackFor = Exception.class)
    public void saveSettings(String provider, String baseUrl, String defaultModel) {
        if (provider == null || provider.isBlank()) {
            throw new BizException("provider 不能为空");
        }
        if (baseUrl != null) {
            sysConfigService.setValue(sysConfigKey(provider, "base-url"), baseUrl);
        }
        if (defaultModel != null) {
            sysConfigService.setValue(sysConfigKey(provider, "default-model"), defaultModel);
        }
        log.info("平台级 Provider 设置已保存: provider={}, baseUrl={}, defaultModel={}",
                provider, baseUrl, defaultModel);
    }

    /**
     * 判断 provider 的平台级 API Key 是否已配置（vault 有 ACTIVE 凭证或 yml 有值），
     * 作为目录服务可用性判定的数据源。
     */
    public boolean isApiKeyConfigured(String provider) {
        if (credentialVaultService.hasActivePlatformCredential(provider)) {
            return true;
        }
        return providerProperties.getConfig(provider).hasApiKey();
    }

    /**
     * 脱敏展示平台级 API Key：仅保留尾 4 位，前缀 {@code ****}。
     *
     * @return 脱敏串；未配置返回 null
     */
    public String maskApiKey(String provider) {
        String plaintext = getApiKey(provider);
        if (plaintext == null || plaintext.isBlank()) {
            return null;
        }
        String tail = plaintext.length() <= 4 ? plaintext : plaintext.substring(plaintext.length() - 4);
        return "****" + tail;
    }

    /**
     * 当前生效的 API Key 是否来自 vault（PLATFORM 级凭证），供管理端列表标注。
     */
    public boolean isApiKeyFromVault(String provider) {
        return credentialVaultService.hasActivePlatformCredential(provider);
    }

    private String sysConfigKey(String provider, String suffix) {
        return "llm.provider." + provider.toLowerCase() + "." + suffix;
    }
}
