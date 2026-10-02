package com.helloai.core.system.service.impl;

import com.helloai.common.base.BizException;
import com.helloai.common.crypto.CredentialCryptoService;
import com.helloai.core.system.entity.CredentialVault;
import com.helloai.core.system.service.CredentialVaultBindingService;
import com.helloai.core.system.service.CredentialVaultService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;

/**
 * 凭证绑定服务实现。
 */
@Service
@RequiredArgsConstructor
public class CredentialVaultBindingServiceImpl implements CredentialVaultBindingService {

    private final CredentialVaultService credentialVaultService;
    private final CredentialCryptoService credentialCryptoService;

    @Transactional(rollbackFor = Exception.class)
    @Override
    public CredentialVault bindAgentApiKey(Long agentId, String provider, String apiKeyPlaintext,
                                           OffsetDateTime expiresAt, String remark) {
        if (agentId == null) {
            throw new BizException("agentId 不能为空");
        }
        if (provider == null || provider.isBlank()) {
            throw new BizException("provider 不能为空");
        }
        if (apiKeyPlaintext == null || apiKeyPlaintext.isBlank()) {
            throw new BizException("apiKey 不能为空");
        }
        String encrypted = credentialCryptoService.encryptToBase64(apiKeyPlaintext);
        return credentialVaultService.saveAgentApiKeyCredential(agentId, provider, encrypted, null, expiresAt, remark);
    }

    @Override
    public String getAgentApiKeyPlaintext(Long agentId, String provider) {
        CredentialVault vault = credentialVaultService.getActiveAgentApiKey(agentId, provider);
        if (vault == null) {
            return null;
        }
        String secretRef = vault.getSecretRef();
        if (secretRef != null && !secretRef.isBlank()) {
            String env = System.getenv(secretRef);
            if (env == null || env.isBlank()) {
                throw new BizException("secretRef 指向的环境变量为空: " + secretRef);
            }
            return env;
        }
        String encrypted = vault.getEncryptedValue();
        if (encrypted == null || encrypted.isBlank()) {
            throw new BizException("vault 凭证缺少 encrypted_value/secret_ref");
        }
        return credentialCryptoService.decryptFromBase64(encrypted);
    }

    /**
     * 轮换 Agent 的 API Key 凭证。
     *
     * <p>AgentHub 旧凭证 → EXPIRED，新凭证 → ACTIVE。</p>
     * <p>与 {@link #bindAgentApiKey} 的区别：</p>
     * <ul>
     *   <li>bindAgentApiKey：旧凭证 → DISABLED（人为停用语义）</li>
     *   <li>rotateAgentApiKey：旧凭证 → EXPIRED（自动轮换语义），
     *       在 remark 中记录 rotated_from_id 审计链</li>
     * </ul>
     *
     * @param agentId         Agent ID
     * @param provider        LLM Provider
     * @param apiKeyPlaintext 新 API Key 明文
     * @param remark          审计备注
     * @return 新创建的 ACTIVE 凭证
     */
    @Transactional(rollbackFor = Exception.class)
    @Override
    public CredentialVault rotateAgentApiKey(Long agentId, String provider,
                                             String apiKeyPlaintext, String remark) {
        if (agentId == null) {
            throw new BizException("agentId 不能为空");
        }
        if (provider == null || provider.isBlank()) {
            throw new BizException("provider 不能为空");
        }
        if (apiKeyPlaintext == null || apiKeyPlaintext.isBlank()) {
            throw new BizException("apiKey 不能为空");
        }
        String encrypted = credentialCryptoService.encryptToBase64(apiKeyPlaintext);
        return credentialVaultService.rotateAgentApiKey(agentId, provider, encrypted, null, remark);
    }
}
