package com.helloai.core.system.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.helloai.common.constant.CredentialOwnerType;
import com.helloai.common.constant.CredentialStatus;
import com.helloai.common.constant.CredentialType;
import com.helloai.core.system.entity.CredentialVault;
import com.helloai.core.system.mapper.CredentialVaultMapper;
import com.helloai.core.system.service.CredentialVaultService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * 凭证保险库服务实现。
 */
@Service
public class CredentialVaultServiceImpl extends ServiceImpl<CredentialVaultMapper, CredentialVault> implements CredentialVaultService {

    /**
     * 查询 Agent 的当前启用 API Key 凭证。
     */
    @Override
    public CredentialVault getActiveAgentApiKey(Long agentId, String provider) {
        return getActiveApiKey(CredentialOwnerType.AGENT, agentId, provider);
    }

    /**
     * 查询平台级（PLATFORM/ownerId=0）的当前启用 API Key 凭证。
     *
     * <p>平台级凭证按 provider 唯一（owner_type=PLATFORM、owner_id=0），
     * 由 {@code PlatformProviderConfigService} 读取，替代 yml 启动期一次性绑定。</p>
     */
    @Override
    public CredentialVault getActivePlatformApiKey(String provider) {
        return getActiveApiKey(CredentialOwnerType.PLATFORM, 0L, provider);
    }

    private CredentialVault getActiveApiKey(CredentialOwnerType ownerType, Long ownerId, String provider) {
        return lambdaQuery()
                .eq(CredentialVault::getOwnerType, ownerType)
                .eq(CredentialVault::getOwnerId, ownerId)
                .eq(provider != null && !provider.isBlank(), CredentialVault::getProvider, provider)
                .eq(CredentialVault::getCredentialType, CredentialType.API_KEY)
                .eq(CredentialVault::getStatus, CredentialStatus.ACTIVE)
                .orderByDesc(CredentialVault::getCreateTime)
                .last("LIMIT 1")
                .one();
    }

    /**
     * 查询平台级全部凭证记录（不含加密值明文），供管理端脱敏展示。
     */
    @Override
    public List<CredentialVault> listPlatformCredentials() {
        return lambdaQuery()
                .eq(CredentialVault::getOwnerType, CredentialOwnerType.PLATFORM)
                .eq(CredentialVault::getOwnerId, 0L)
                .list();
    }

    /**
     * 判断平台级是否已配置启用态凭证。
     */
    @Override
    public boolean hasActivePlatformCredential(String provider) {
        return getActivePlatformApiKey(provider) != null;
    }

    /**
     * 查询 Agent 的全部凭证记录（不含加密值明文）。
     *
     * <p>按 §6.3 分层红线从 CredentialController 收口。</p>
     */
    @Override
    public List<CredentialVault> listAgentCredentials(Long agentId) {
        return lambdaQuery()
                .eq(CredentialVault::getOwnerType, CredentialOwnerType.AGENT)
                .eq(CredentialVault::getOwnerId, agentId)
                .list();
    }

    /**
     * 判断 Agent 当前是否已绑定启用态托管凭证。
     */
    @Override
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
    @Override
    public CredentialVault saveAgentApiKeyCredential(Long agentId, String provider,
                                                     String encryptedValue, String secretRef,
                                                     OffsetDateTime expiresAt,
                                                     String remark) {
        return saveApiKeyCredential(CredentialOwnerType.AGENT, agentId, provider,
                encryptedValue, secretRef, expiresAt, remark);
    }

    /**
     * 以最小 upsert 方式保存平台级 API Key 凭证（ownerId 固定占位 0）。
     */
    @Transactional(rollbackFor = Exception.class)
    @Override
    public CredentialVault savePlatformApiKeyCredential(String provider,
                                                        String encryptedValue, String secretRef,
                                                        String remark) {
        return saveApiKeyCredential(CredentialOwnerType.PLATFORM, 0L, provider,
                encryptedValue, secretRef, null, remark);
    }

    private CredentialVault saveApiKeyCredential(CredentialOwnerType ownerType, Long ownerId,
                                                 String provider,
                                                 String encryptedValue, String secretRef,
                                                 OffsetDateTime expiresAt,
                                                 String remark) {
        lambdaUpdate()
                .eq(CredentialVault::getOwnerType, ownerType)
                .eq(CredentialVault::getOwnerId, ownerId)
                .eq(CredentialVault::getProvider, provider)
                .eq(CredentialVault::getCredentialType, CredentialType.API_KEY)
                .eq(CredentialVault::getStatus, CredentialStatus.ACTIVE)
                .set(CredentialVault::getStatus, CredentialStatus.DISABLED)
                .update();

        CredentialVault vault = new CredentialVault();
        vault.setOwnerType(ownerType);
        vault.setOwnerId(ownerId);
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
    @Override
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
    @Override
    public CredentialVault rotateAgentApiKey(Long agentId, String provider,
                                             String encryptedValue, String secretRef,
                                             String remark) {
        return rotateApiKey(CredentialOwnerType.AGENT, agentId, provider,
                encryptedValue, secretRef, remark);
    }

    /**
     * 轮换平台级 API Key 凭证（旧凭证 → EXPIRED，新凭证 → ACTIVE），ownerId 固定占位 0。
     */
    @Transactional(rollbackFor = Exception.class)
    @Override
    public CredentialVault rotatePlatformApiKey(String provider,
                                                String encryptedValue, String secretRef,
                                                String remark) {
        return rotateApiKey(CredentialOwnerType.PLATFORM, 0L, provider,
                encryptedValue, secretRef, remark);
    }

    private CredentialVault rotateApiKey(CredentialOwnerType ownerType, Long ownerId,
                                         String provider,
                                         String encryptedValue, String secretRef,
                                         String remark) {
        CredentialVault oldVault = getActiveApiKey(ownerType, ownerId, provider);

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
        newVault.setOwnerType(ownerType);
        newVault.setOwnerId(ownerId);
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
