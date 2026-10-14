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
import com.helloai.common.crypto.CredentialCryptoService;
import com.helloai.core.system.crypto.CredentialFingerprint;
import com.helloai.core.system.entity.CredentialAuditLog;
import com.helloai.core.system.entity.CredentialVault;
import com.helloai.core.system.port.CredentialSecret;
import com.helloai.core.system.mapper.CredentialVaultMapper;
import com.helloai.core.system.service.CredentialAuditService;
import com.helloai.core.system.service.CredentialVaultService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * 凭证保险库服务实现。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CredentialVaultServiceImpl extends ServiceImpl<CredentialVaultMapper, CredentialVault> implements CredentialVaultService {

    private final CredentialAuditService credentialAuditService;
    private final CredentialCryptoService credentialCryptoService;

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

    // ── 只读快照变体（RM5 批 3）：语义与 getActive* 逐字一致，仅投影解密所需字段 ──

    @Override
    public CredentialSecret getActiveAgentApiKeySecret(Long agentId, String provider) {
        return toSecret(getActiveAgentApiKey(agentId, provider));
    }

    @Override
    public CredentialSecret getActivePlatformApiKeySecret(String provider) {
        return toSecret(getActivePlatformApiKey(provider));
    }

    /** 凭证实体 → 解密素材快照（null 透传，最小投影）。 */
    private static CredentialSecret toSecret(CredentialVault vault) {
        return vault == null ? null : new CredentialSecret(vault.getEncryptedValue(), vault.getSecretRef());
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

    // ── `M-6` Key 指纹：同 provider 内同 Key 去重 / 历史行惰性回填 ──────────

    /**
     * 判断指定归属 + provider 下是否已存在同指纹凭证（不限状态，含历史 EXPIRED/DISABLED 行）。
     */
    @Override
    public boolean existsFingerprint(CredentialOwnerType ownerType, Long ownerId,
                                     String provider, String fingerprint) {
        if (fingerprint == null || fingerprint.isBlank()) {
            return false;
        }
        return lambdaQuery()
                .eq(CredentialVault::getOwnerType, ownerType)
                .eq(CredentialVault::getOwnerId, ownerId)
                .eq(CredentialVault::getProvider, provider)
                .eq(CredentialVault::getCredentialType, CredentialType.API_KEY)
                .eq(CredentialVault::getFingerprint, fingerprint)
                .count() > 0;
    }

    /**
     * 列出指定归属 + provider 的全部 API Key 凭证（含历史行）。
     */
    private List<CredentialVault> listByOwnerAndProvider(CredentialOwnerType ownerType, Long ownerId,
                                                         String provider) {
        return lambdaQuery()
                .eq(CredentialVault::getOwnerType, ownerType)
                .eq(CredentialVault::getOwnerId, ownerId)
                .eq(CredentialVault::getProvider, provider)
                .eq(CredentialVault::getCredentialType, CredentialType.API_KEY)
                .list();
    }

    /**
     * 仅当该行当前无指纹时写入（只补不覆盖，幂等）。
     */
    private boolean updateFingerprintIfAbsent(Long id, String fingerprint) {
        if (id == null || fingerprint == null || fingerprint.isBlank()) {
            return false;
        }
        return lambdaUpdate()
                .eq(CredentialVault::getId, id)
                .and(w -> w.isNull(CredentialVault::getFingerprint)
                        .or().eq(CredentialVault::getFingerprint, ""))
                .set(CredentialVault::getFingerprint, fingerprint)
                .update();
    }

    /**
     * `M-6` 惰性回填历史行指纹。
     *
     * <p>解密属「凭证素材」职责，落在本域内可避免 agent 域 import {@code system.entity}
     * （RM5 批 3 已为此建立只读快照先例）。逐行 best-effort：单行解密失败只记 WARN 并跳过，
     * **不阻断**管理员保存密钥。</p>
     */
    @Override
    public int backfillFingerprints(CredentialOwnerType ownerType, Long ownerId, String provider) {
        int filled = 0;
        for (CredentialVault row : listByOwnerAndProvider(ownerType, ownerId, provider)) {
            if (row.getFingerprint() != null && !row.getFingerprint().isBlank()) {
                continue;
            }
            String encrypted = row.getEncryptedValue();
            if (encrypted == null || encrypted.isBlank()) {
                // secretRef 型凭证：无明文可摘要，保持 NULL（合法）
                continue;
            }
            String computed;
            try {
                computed = CredentialFingerprint.of(credentialCryptoService.decryptFromBase64(encrypted));
            } catch (Exception e) {
                log.warn("历史凭证指纹回填跳过（解密失败）: id={}, provider={}, reason={}",
                        row.getId(), provider, e.getMessage());
                continue;
            }
            if (updateFingerprintIfAbsent(row.getId(), computed)) {
                filled++;
            }
        }
        if (filled > 0) {
            log.info("凭证指纹已回填: ownerType={}, ownerId={}, provider={}, rows={}",
                    ownerType, ownerId, provider, filled);
        }
        return filled;
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
                                                     String remark, String fingerprint) {
        return saveApiKeyCredential(CredentialOwnerType.AGENT, agentId, provider,
                encryptedValue, secretRef, expiresAt, remark, fingerprint);
    }

    /**
     * 以最小 upsert 方式保存平台级 API Key 凭证（ownerId 固定占位 0）。
     */
    @Transactional(rollbackFor = Exception.class)
    @Override
    public CredentialVault savePlatformApiKeyCredential(String provider,
                                                        String encryptedValue, String secretRef,
                                                        String remark, String fingerprint) {
        return saveApiKeyCredential(CredentialOwnerType.PLATFORM, 0L, provider,
                encryptedValue, secretRef, null, remark, fingerprint);
    }

    // ── `M-7` 批量录入：备位（DISABLED）落库 ─────────────────────────────

    /**
     * 落一条平台级**备位** API Key 凭证（{@code status=DISABLED}），**不动当前 ACTIVE 凭证**。
     *
     * <p>与 {@link #savePlatformApiKeyCredential} 的关键区别：后者会把原 ACTIVE 降级并新落一条
     * ACTIVE（"顶替"语义），本方法**只追加备位**（"并存"语义）—— 这正是 `M-7` 批量录入需要的：
     * 已有主 Key 时粘贴多把不能让正在使用的那把被顶掉（对齐 magpie {@code AddKeys}）。</p>
     *
     * <p>并发安全由 DB 兜底：`uk_credential_vault_*_active` 部分唯一索引**只覆盖
     * {@code status='ACTIVE'}** 的行，备位行不参与该约束，故多条 DISABLED 可共存。</p>
     */
    @Transactional(rollbackFor = Exception.class)
    @Override
    public CredentialVault saveDisabledPlatformApiKeyCredential(String provider,
                                                                String encryptedValue,
                                                                String remark, String fingerprint) {
        CredentialVault vault = new CredentialVault();
        vault.setOwnerType(CredentialOwnerType.PLATFORM);
        vault.setOwnerId(0L);
        vault.setProvider(provider);
        vault.setCredentialType(CredentialType.API_KEY);
        vault.setEncryptedValue(encryptedValue);
        vault.setStatus(CredentialStatus.DISABLED);
        vault.setRemark(remark);
        vault.setFingerprint(fingerprint);
        save(vault);
        // 审计复用 BIND（"新增一条绑定"），detail 标明备位语义以便与启用位区分
        recordAudit(vault, CredentialAuditAction.BIND, CredentialAuditAction.OPERATOR_ADMIN,
                "batch add as standby (DISABLED)");
        log.info("平台级备位 API Key 已落库: provider={}, fingerprint={}", provider, fingerprint);
        return vault;
    }

    private CredentialVault saveApiKeyCredential(CredentialOwnerType ownerType, Long ownerId,
                                                 String provider,
                                                 String encryptedValue, String secretRef,
                                                 OffsetDateTime expiresAt,
                                                 String remark, String fingerprint) {
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
        vault.setFingerprint(fingerprint);
        save(vault);
        // 绑定/保存动作审计（同事务，fail-close——审计失败整体回滚）
        recordAudit(vault, CredentialAuditAction.BIND, CredentialAuditAction.OPERATOR_ADMIN,
                "save/bind credential");
        return vault;
    }

    @Transactional(rollbackFor = Exception.class)
    @Override
    public CredentialVault saveAgentApiKeyCredential(Long agentId, String provider,
                                                     String encryptedValue, String secretRef,
                                                     String remark, String fingerprint) {
        return saveAgentApiKeyCredential(agentId, provider, encryptedValue, secretRef, null,
                remark, fingerprint);
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
                                             String remark, String fingerprint) {
        return rotateApiKey(CredentialOwnerType.AGENT, agentId, provider,
                encryptedValue, secretRef, remark, fingerprint);
    }

    /**
     * 轮换平台级 API Key 凭证（旧凭证 → EXPIRED，新凭证 → ACTIVE），ownerId 固定占位 0。
     */
    @Transactional(rollbackFor = Exception.class)
    @Override
    public CredentialVault rotatePlatformApiKey(String provider,
                                                String encryptedValue, String secretRef,
                                                String remark, String fingerprint) {
        return rotateApiKey(CredentialOwnerType.PLATFORM, 0L, provider,
                encryptedValue, secretRef, remark, fingerprint);
    }

    private CredentialVault rotateApiKey(CredentialOwnerType ownerType, Long ownerId,
                                         String provider,
                                         String encryptedValue, String secretRef,
                                         String remark, String fingerprint) {
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
        newVault.setFingerprint(fingerprint);
        save(newVault);
        // 轮换动作审计（同事务；detail 记录 rotated_from_id 审计链）
        recordAudit(newVault, CredentialAuditAction.ROTATE, CredentialAuditAction.OPERATOR_ADMIN,
                oldVault != null ? "rotated_from_id=" + oldVault.getId() : "first credential");
        return newVault;
    }

    /**
     * 人工停用凭证（N-004 收口）。
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
     * 过期扫描：ACTIVE 且 expire_time < now 的凭证批量置为 EXPIRED。
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
     * 分页查询凭证操作审计。
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
