package com.helloai.core.agent.service.impl;

import com.helloai.core.agent.service.PlatformProviderConfigService;
import com.helloai.common.base.BizException;
import com.helloai.common.config.AgentProviderProperties;
import com.helloai.common.crypto.CredentialCryptoService;
import com.helloai.core.agent.chat.provider.ProviderChatModelCache;
import com.helloai.core.system.entity.CredentialVault;
import com.helloai.core.system.entity.LlmProvider;
import com.helloai.core.system.service.CredentialVaultService;
import com.helloai.core.system.service.LlmProviderQueryService;
import com.helloai.core.system.service.SysConfigService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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
        CredentialVault vault = credentialVaultService.getActivePlatformApiKey(provider);
        if (vault != null) {
            if (vault.getSecretRef() != null && !vault.getSecretRef().isBlank()) {
                String env = System.getenv(vault.getSecretRef());
                if (env == null || env.isBlank()) {
                    log.warn("平台级凭证 secretRef 指向的环境变量为空，回退 yml: provider={}, secretRef={}",
                            provider, vault.getSecretRef());
                } else {
                    return env;
                }
            } else if (vault.getEncryptedValue() != null && !vault.getEncryptedValue().isBlank()) {
                return credentialCryptoService.decryptFromBase64(vault.getEncryptedValue());
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
        LlmProvider lp = llmProviderQueryService.findByCode(provider).orElse(null);
        if (lp != null && lp.getBaseUrl() != null && !lp.getBaseUrl().isBlank()) {
            return lp.getBaseUrl();
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
        LlmProvider lp = llmProviderQueryService.findByCode(provider).orElse(null);
        if (lp != null && lp.getDefaultModel() != null && !lp.getDefaultModel().isBlank()) {
            return lp.getDefaultModel();
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
     * <p>缓存全清后旧实例不再命中，下次调用按新 key 自动重建；正在执行的调用持有旧实例引用，
     * 完成后无引用即被 GC，可接受。</p>
     */
    @Transactional(rollbackFor = Exception.class)
    public void saveApiKey(String provider, String apiKey) {
        if (provider == null || provider.isBlank()) {
            throw new BizException("provider 不能为空");
        }
        if (apiKey == null || apiKey.isBlank()) {
            throw new BizException("apiKey 不能为空");
        }
        String encrypted = credentialCryptoService.encryptToBase64(apiKey);
        credentialVaultService.rotatePlatformApiKey(provider, encrypted, null,
                "平台配置动态化（系统设置页写入）");
        providerChatModelCache.clear();
        log.info("平台级 API Key 已轮换并清理 ChatModel 缓存: provider={}", provider);
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
