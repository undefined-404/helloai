package com.helloai.core.system.service;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.helloai.common.constant.CredentialOwnerType;
import com.helloai.common.constant.CredentialStatus;
import com.helloai.common.constant.CredentialType;
import com.helloai.core.system.entity.CredentialVault;
import com.helloai.core.system.mapper.CredentialVaultMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;

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
     * 查询 Agent 的全部凭证记录（不含加密值明文）。
     *
     * <p>按 §6.3 分层红线从 CredentialController 收口。</p>
     */
    public List<CredentialVault> listAgentCredentials(Long agentId) {
        return lambdaQuery()
                .eq(CredentialVault::getOwnerType, CredentialOwnerType.AGENT)
                .eq(CredentialVault::getOwnerId, agentId)
                .list();
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
        vault.setExpireTime(expiresAt);
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

    /**
     * 轮换 Agent 的 API Key 凭证：旧凭证 → EXPIRED，新凭证 → ACTIVE。
     *
     * <p>AgentHub V1 T4 轮换语义：</p>
     * <ul>
     *   <li>旧 ACTIVE 凭证标为 {@code EXPIRED}（非 DISABLED），
     *       区分"人为停用"和"自动轮换"</li>
     *   <li>新建 ACTIVE 凭证，在 remark 中记录 rotated_from_id 审计链</li>
     *   <li>事务内保证一致性：旧凭证过期 + 新凭证创建原子完成</li>
     * </ul>
     *
     * @param agentId        Agent ID
     * @param provider       LLM Provider
     * @param encryptedValue 新凭证加密值
     * @param secretRef      新凭证 Secret 引用
     * @param remark         审计备注
     * @return 新创建的 ACTIVE 凭证
     */
    @Transactional(rollbackFor = Exception.class)
    public CredentialVault rotateAgentApiKey(Long agentId, String provider,
                                             String encryptedValue, String secretRef,
                                             String remark) {
        CredentialVault oldVault = getActiveAgentApiKey(agentId, provider);

        if (oldVault != null) {
            lambdaUpdate()
                    .eq(CredentialVault::getId, oldVault.getId())
                    .set(CredentialVault::getStatus, CredentialStatus.EXPIRED)
                    .set(CredentialVault::getRemark,
                            (oldVault.getRemark() != null ? oldVault.getRemark() + " | " : "")
                                    + "rotated at " + OffsetDateTime.now())
                    .update();
        }

        String finalRemark = remark != null ? remark : "credential rotation";
        if (oldVault != null) {
            finalRemark = finalRemark + " | rotated_from_id=" + oldVault.getId();
        }

        CredentialVault newVault = new CredentialVault();
        newVault.setOwnerType(CredentialOwnerType.AGENT);
        newVault.setOwnerId(agentId);
        newVault.setProvider(provider);
        newVault.setCredentialType(CredentialType.API_KEY);
        newVault.setEncryptedValue(encryptedValue);
        newVault.setSecretRef(secretRef);
        newVault.setStatus(CredentialStatus.ACTIVE);
        newVault.setRemark(finalRemark);
        save(newVault);
        return newVault;
    }
}
