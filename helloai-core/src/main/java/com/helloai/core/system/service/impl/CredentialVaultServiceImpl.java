package com.helloai.core.system.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.helloai.common.base.BizException;
import com.helloai.common.constant.CredentialAuditAction;
import com.helloai.common.constant.CredentialOwnerType;
import com.helloai.common.constant.CredentialStatus;
import com.helloai.common.constant.CredentialType;
import com.helloai.core.system.entity.CredentialAuditLog;
import com.helloai.core.system.entity.CredentialVault;
import com.helloai.core.system.mapper.CredentialVaultMapper;
import com.helloai.core.system.service.CredentialAuditService;
import com.helloai.core.system.service.CredentialVaultService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * 凭证保险库服务实现。
 */
@Service
@RequiredArgsConstructor
public class CredentialVaultServiceImpl extends ServiceImpl<CredentialVaultMapper, CredentialVault> implements CredentialVaultService {

    private final CredentialAuditService credentialAuditService;

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
        // 显式 baseMapper + LambdaQueryWrapper：规避 lambdaQuery() chain 在隔离测试环境下
        // 的 MybatisMapperProxy 解析限制（MP 3.5.9）
        return baseMapper.selectOne(new LambdaQueryWrapper<CredentialVault>()
                .eq(CredentialVault::getOwnerType, ownerType)
                .eq(CredentialVault::getOwnerId, ownerId)
                .eq(provider != null && !provider.isBlank(), CredentialVault::getProvider, provider)
                .eq(CredentialVault::getCredentialType, CredentialType.API_KEY)
                .eq(CredentialVault::getStatus, CredentialStatus.ACTIVE)
                .orderByDesc(CredentialVault::getCreateTime)
                .last("LIMIT 1"));
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
     * <p>先只支持单条启用态记录；后续多 Provider / 多版本轮换再继续扩展。</p>
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
        // Phase 2 B2：绑定/保存动作审计（同事务，fail-close——审计失败整体回滚）
        recordAudit(vault, CredentialAuditAction.BIND, CredentialAuditAction.OPERATOR_ADMIN,
                "save/bind credential");
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
     * <p>AgentHub 轮换语义：</p>
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
        // Phase 2 B2：轮换动作审计（同事务；detail 记录 rotated_from_id 审计链）
        recordAudit(newVault, CredentialAuditAction.ROTATE, CredentialAuditAction.OPERATOR_ADMIN,
                oldVault != null ? "rotated_from_id=" + oldVault.getId() : "first credential");
        return newVault;
    }

    /**
     * 人工停用凭证（Phase 2 B2，N-004 收口）。
     */
    @Transactional(rollbackFor = Exception.class)
    @Override
    public CredentialVault revokeCredential(Long id, String operator) {
        CredentialVault vault = getById(id);
        if (vault == null) {
            throw new BizException("凭证不存在，禁止停用: id=" + id);
        }
        if (vault.getStatus() == CredentialStatus.EXPIRED) {
            throw new BizException("EXPIRED 凭证为轮换淘汰终态，不可逆，禁止停用: id=" + id);
        }
        boolean ok = lambdaUpdate()
                .eq(CredentialVault::getId, id)
                .in(CredentialVault::getStatus,
                        List.of(CredentialStatus.ACTIVE, CredentialStatus.DISABLED))
                .set(CredentialVault::getStatus, CredentialStatus.DISABLED)
                .update();
        if (!ok) {
            throw new BizException("凭证状态冲突，停用失败（CAS）: id=" + id);
        }
        recordAudit(vault, CredentialAuditAction.REVOKE,
                operator != null ? operator : CredentialAuditAction.OPERATOR_ADMIN,
                "manual revoke (status=" + vault.getStatus().name() + ")");
        vault.setStatus(CredentialStatus.DISABLED);
        return vault;
    }

    /**
     * 过期扫描：ACTIVE 且 expire_time < now 的凭证批量置为 EXPIRED（Phase 2 B2）。
     */
    @Transactional(rollbackFor = Exception.class)
    @Override
    public int expireOverdue(int batchLimit) {
        if (batchLimit <= 0) {
            return 0;
        }
        List<CredentialVault> overdue = baseMapper.selectList(new LambdaQueryWrapper<CredentialVault>()
                .eq(CredentialVault::getStatus, CredentialStatus.ACTIVE)
                .isNotNull(CredentialVault::getExpireTime)
                .lt(CredentialVault::getExpireTime, OffsetDateTime.now())
                .orderByAsc(CredentialVault::getExpireTime)
                .last("LIMIT " + batchLimit));
        int count = 0;
        for (CredentialVault vault : overdue) {
            boolean ok = lambdaUpdate()
                    .eq(CredentialVault::getId, vault.getId())
                    .eq(CredentialVault::getStatus, CredentialStatus.ACTIVE)
                    .set(CredentialVault::getStatus, CredentialStatus.EXPIRED)
                    .set(CredentialVault::getRemark, appendRemark(vault, "expired at " + OffsetDateTime.now()))
                    .update();
            if (ok) {
                recordAudit(vault, CredentialAuditAction.EXPIRE,
                        CredentialAuditAction.OPERATOR_SYSTEM,
                        "expire_time elapsed (expiresAt=" + vault.getExpireTime() + ")");
                count++;
            }
        }
        return count;
    }

    /**
     * 分页查询凭证操作审计（Phase 2 B2）。
     */
    @Override
    public IPage<CredentialAuditLog> listAudits(Long credentialId, CredentialOwnerType ownerType,
                                                Long ownerId, long page, long size) {
        return credentialAuditService.listAudits(credentialId, ownerType, ownerId, page, size);
    }

    /**
     * 审计落点：与凭证主操作同事务（审计失败整体回滚，fail-close）。
     */
    private void recordAudit(CredentialVault vault, String action, String operator, String detail) {
        credentialAuditService.record(vault.getId(), vault.getOwnerType(), vault.getOwnerId(),
                vault.getProvider(), action, operator, detail);
    }

    private String appendRemark(CredentialVault vault, String segment) {
        return (vault.getRemark() != null && !vault.getRemark().isBlank())
                ? vault.getRemark() + " | " + segment
                : segment;
    }
}
