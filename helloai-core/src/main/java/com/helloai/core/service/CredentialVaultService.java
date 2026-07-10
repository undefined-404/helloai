package com.helloai.core.service;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.helloai.common.constant.CredentialOwnerType;
import com.helloai.common.constant.CredentialStatus;
import com.helloai.common.constant.CredentialType;
import com.helloai.core.entity.CredentialVault;
import com.helloai.core.mapper.CredentialVaultMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;

/**
 * 凭证保险库服务。
 *
 * <p>T1 只提供最小可复用能力，供后续 AgentExecutor / ChatClient 链路继续接入。</p>
 */
@Service
public class CredentialVaultService extends ServiceImpl<CredentialVaultMapper, CredentialVault> {

    /**
     * 查询 Agent 的当前启用 API Key 凭证。
     */
    public CredentialVault getActiveAgentApiKey(Long agentId, String provider) {
        return lambdaQuery()
                .eq(CredentialVault::getOwnerType, CredentialOwnerType.AGENT)
                .eq(CredentialVault::getOwnerId, agentId)
                .eq(provider != null && !provider.isBlank(), CredentialVault::getProvider, provider)
                .eq(CredentialVault::getCredentialType, CredentialType.API_KEY)
                .eq(CredentialVault::getStatus, CredentialStatus.ACTIVE)
                .orderByDesc(CredentialVault::getCreateTime)
                .last("LIMIT 1")
                .one();
    }

    /**
     * 判断 Agent 当前是否已绑定启用态托管凭证。
     */
    public boolean hasActiveAgentCredential(Long agentId) {
        return lambdaQuery()
                .eq(CredentialVault::getOwnerType, CredentialOwnerType.AGENT)
                .eq(CredentialVault::getOwnerId, agentId)
                .eq(CredentialVault::getStatus, CredentialStatus.ACTIVE)
                .count() > 0;
    }

    /**
     * 以最小 upsert 方式保存 Agent 的 API Key 凭证。
     *
     * <p>T1 先只支持单条启用态记录；后续多 Provider / 多版本轮换再继续扩展。</p>
     */
    @Transactional(rollbackFor = Exception.class)
    public CredentialVault saveAgentApiKeyCredential(Long agentId, String provider,
                                                     String encryptedValue, String secretRef,
                                                     OffsetDateTime expiresAt,
                                                     String remark) {
        lambdaUpdate()
                .eq(CredentialVault::getOwnerType, CredentialOwnerType.AGENT)
                .eq(CredentialVault::getOwnerId, agentId)
                .eq(CredentialVault::getProvider, provider)
                .eq(CredentialVault::getCredentialType, CredentialType.API_KEY)
                .eq(CredentialVault::getStatus, CredentialStatus.ACTIVE)
                .set(CredentialVault::getStatus, CredentialStatus.DISABLED)
                .update();

        CredentialVault vault = new CredentialVault();
        vault.setOwnerType(CredentialOwnerType.AGENT);
        vault.setOwnerId(agentId);
        vault.setProvider(provider);
        vault.setCredentialType(CredentialType.API_KEY);
        vault.setEncryptedValue(encryptedValue);
        vault.setSecretRef(secretRef);
        vault.setStatus(CredentialStatus.ACTIVE);
        vault.setExpiresAt(expiresAt);
        vault.setRemark(remark);
        save(vault);
        return vault;
    }

    @Transactional(rollbackFor = Exception.class)
    public CredentialVault saveAgentApiKeyCredential(Long agentId, String provider,
                                                     String encryptedValue, String secretRef,
                                                     String remark) {
        return saveAgentApiKeyCredential(agentId, provider, encryptedValue, secretRef, null, remark);
    }
}
