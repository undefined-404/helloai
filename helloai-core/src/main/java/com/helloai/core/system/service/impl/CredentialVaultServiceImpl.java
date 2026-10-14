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
import com.helloai.core.system.port.PlatformApiKeyChange;
import com.helloai.core.system.port.PlatformApiKeyView;
import com.helloai.core.system.mapper.CredentialVaultMapper;
import com.helloai.core.system.service.CredentialAuditService;
import com.helloai.core.system.service.CredentialVaultService;
import com.helloai.core.shared.util.SecretMask;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
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

    /** 平台级凭证的固定 {@code owner_id} 占位值（见 {@code CredentialOwnerType#PLATFORM}）。 */
    private static final Long PLATFORM_OWNER_ID = 0L;

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

    // ── `M-8` 多 Key 主备：列表 / 手动切换 / 停用 / 删除 ──────────────────

    /**
     * 列出某 provider 下平台级 API Key 条目快照（不含密文）。
     *
     * <p><b>状态序在内存中排，不写进 SQL</b>：{@code CASE WHEN status=...} 是方言敏感的
     * （PG/MySQL 语法不同），而单个 provider 的 Key 条目数是个位数 ⇒ 用
     * {@link #statusRank} 在内存排序更可移植，且排序规则集中在一处（不散落到 SQL 字符串里）。</p>
     */
    @Override
    public List<PlatformApiKeyView> listPlatformApiKeyViews(String provider) {
        if (provider == null || provider.isBlank()) {
            return List.of();
        }
        List<CredentialVault> rows = baseMapper.selectList(new LambdaQueryWrapper<CredentialVault>()
                .eq(CredentialVault::getOwnerType, CredentialOwnerType.PLATFORM)
                .eq(CredentialVault::getOwnerId, PLATFORM_OWNER_ID)
                .eq(CredentialVault::getProvider, provider)
                .eq(CredentialVault::getCredentialType, CredentialType.API_KEY));
        return rows.stream()
                .sorted(Comparator
                        .comparingInt((CredentialVault row) -> statusRank(row.getStatus()))
                        .thenComparing(CredentialVault::getCreateTime,
                                Comparator.nullsLast(Comparator.naturalOrder()))
                        .thenComparing(CredentialVault::getId,
                                Comparator.nullsLast(Comparator.naturalOrder())))
                .map(this::toPlatformApiKeyView)
                .toList();
    }

    /**
     * `M-8` 手动切换启用位：指定备位升 {@code ACTIVE}，原 {@code ACTIVE} 降 {@code DISABLED}。
     */
    @Transactional(rollbackFor = Exception.class)
    @Override
    public PlatformApiKeyChange activatePlatformApiKeyCredential(Long credentialId, String provider,
                                                                 String operator) {
        CredentialVault target = requirePlatformApiKeyCredential(credentialId, provider);
        if (target.getStatus() == CredentialStatus.ACTIVE) {
            throw new BizException("该 Key 已是启用位，无需切换: id=" + credentialId);
        }
        if (target.getStatus() == CredentialStatus.EXPIRED) {
            throw new BizException("EXPIRED 凭证为轮换淘汰终态，不可启用: id=" + credentialId);
        }
        CredentialVault previous = getActivePlatformApiKey(provider);
        Long previousId = previous == null ? null : previous.getId();
        // ★先降后升：uk_credential_vault_*_active 部分唯一索引只允许一条 ACTIVE，
        //   顺序颠倒会在两步之间同时存在两条 ACTIVE ⇒ 唯一约束冲突
        if (previous != null) {
            demoteActive(previous, operator,
                    "manual switch: replaced by credential id=" + credentialId);
        }
        promoteStandby(target, operator,
                "manual switch: promoted to active (previousActiveId=" + previousId + ")");
        log.info("平台级 API Key 已手动切换: provider={}, previousActiveId={}, newActiveId={}",
                provider, previousId, target.getId());
        return new PlatformApiKeyChange(target.getId(), target.getFingerprint(),
                previousId, target.getId());
    }

    /**
     * `M-8` 停用平台级 API Key；停用**启用位**时先顶替（无备位可顶则拒绝）。
     */
    @Transactional(rollbackFor = Exception.class)
    @Override
    public PlatformApiKeyChange deactivatePlatformApiKeyCredential(Long credentialId, String provider,
                                                                   String operator) {
        CredentialVault target = requirePlatformApiKeyCredential(credentialId, provider);
        if (target.getStatus() == CredentialStatus.EXPIRED) {
            throw new BizException("EXPIRED 凭证为轮换淘汰终态，不可停用: id=" + credentialId);
        }
        if (target.getStatus() == CredentialStatus.DISABLED) {
            // 已是备位（本就不参与路由）⇒ 幂等空操作，沿用 revokeCredential 对 DISABLED 的宽容
            CredentialVault active = getActivePlatformApiKey(provider);
            Long activeId = active == null ? null : active.getId();
            log.info("平台级 API Key 已是备位，停用为空操作: provider={}, id={}", provider, credentialId);
            return new PlatformApiKeyChange(target.getId(), target.getFingerprint(), activeId, activeId);
        }
        // status == ACTIVE：先判能否顶替（fail fast），再同一事务内先降后升
        CredentialVault successor = requireStandbyForSuccession(provider);
        demoteActive(target, operator, "manual deactivate (platform active)");
        promoteStandby(successor, operator,
                "auto promoted on deactivate of previous active (id=" + credentialId + ")");
        log.info("平台级 API Key 已停用并顶替: provider={}, deactivatedId={}, newActiveId={}",
                provider, credentialId, successor.getId());
        return new PlatformApiKeyChange(target.getId(), target.getFingerprint(),
                target.getId(), successor.getId());
    }

    /**
     * `M-8` 删除平台级 API Key（软删）；删除**启用位**时先顶替（无备位可顶则拒绝）。
     */
    @Transactional(rollbackFor = Exception.class)
    @Override
    public PlatformApiKeyChange deletePlatformApiKeyCredential(Long credentialId, String provider,
                                                               String operator) {
        CredentialVault target = requirePlatformApiKeyCredential(credentialId, provider);
        // 先固定原状态：后续先降后升会改写 target.status，审计文案须描述「删除时它是什么」
        CredentialStatus originalStatus = target.getStatus();
        boolean wasActive = originalStatus == CredentialStatus.ACTIVE;

        Long activeId;
        if (wasActive) {
            // 删除启用位 ⇒ 与停用同一条判据：不得让 provider 失去启用位
            CredentialVault successor = requireStandbyForSuccession(provider);
            demoteActive(target, operator, "manual delete of previous active");
            promoteStandby(successor, operator,
                    "auto promoted on delete of previous active (id=" + credentialId + ")");
            activeId = successor.getId();
        } else {
            CredentialVault active = getActivePlatformApiKey(provider);
            activeId = active == null ? null : active.getId();
        }

        // @TableLogic 软删：UPDATE credential_vault SET deleted = 1 WHERE id = ? AND deleted = 0
        removeById(credentialId);
        recordAudit(target, CredentialAuditAction.DELETE, normalizeOperator(operator),
                "soft delete (status at delete=" + originalStatus.name() + ")");
        log.info("平台级 API Key 已删除: provider={}, id={}, wasActive={}, newActiveId={}",
                provider, credentialId, wasActive, activeId);
        return new PlatformApiKeyChange(credentialId, target.getFingerprint(),
                wasActive ? credentialId : activeId, activeId);
    }

    // ── `M-8` 私有判据实现（唯一实现点，勿在调用方另写一份）────────────────

    /**
     * 取平台级 API Key 凭证并校验归属 / provider（跨 provider 误操作是真实风险，故 fail-close）。
     *
     * <p>软删行读不到（{@code @TableLogic} 使 {@code getById} 自动带 {@code deleted = 0}）
     * ⇒ 已删除凭证自然被判为「不存在」。</p>
     */
    private CredentialVault requirePlatformApiKeyCredential(Long credentialId, String provider) {
        CredentialVault row = getById(credentialId);
        if (row == null) {
            throw new BizException("凭证不存在: id=" + credentialId);
        }
        if (row.getOwnerType() != CredentialOwnerType.PLATFORM
                || !PLATFORM_OWNER_ID.equals(row.getOwnerId())
                || row.getCredentialType() != CredentialType.API_KEY) {
            throw new BizException("仅支持平台级 API Key 凭证: id=" + credentialId);
        }
        if (provider == null || provider.isBlank() || !provider.equals(row.getProvider())) {
            throw new BizException("凭证与 Provider 不匹配，禁止跨 Provider 操作: id=" + credentialId
                    + ", 期望=" + provider + ", 实际=" + row.getProvider());
        }
        return row;
    }

    /**
     * **启用位不可缺失**判据的唯一实现（`M-8`）。
     *
     * <p>取该 provider **最旧**的一把备位作为继任者（对齐 magpie 取「下一把在用的」，
     * 见 {@code keys.go:369-379}）；一把都没有 ⇒ fail-close。</p>
     *
     * <p><b>为何必须拒绝而不是放行</b>：放行会让该 provider 静默变成「未配置」
     * （{@code getActive*} 查不到 ACTIVE），线上表现为「模型突然不可用」而无任何显式信号。
     * magpie 同样拒绝（{@code keys.go:397}「that's the only key in use; turn another on first」）。</p>
     *
     * @return 继任的备位凭证（调用方负责先降后升）
     */
    private CredentialVault requireStandbyForSuccession(String provider) {
        List<CredentialVault> standbys = baseMapper.selectList(new LambdaQueryWrapper<CredentialVault>()
                .eq(CredentialVault::getOwnerType, CredentialOwnerType.PLATFORM)
                .eq(CredentialVault::getOwnerId, PLATFORM_OWNER_ID)
                .eq(CredentialVault::getProvider, provider)
                .eq(CredentialVault::getCredentialType, CredentialType.API_KEY)
                .eq(CredentialVault::getStatus, CredentialStatus.DISABLED)
                .orderByAsc(CredentialVault::getCreateTime)
                .orderByAsc(CredentialVault::getId)
                .last("LIMIT 1"));
        if (standbys.isEmpty()) {
            throw new BizException("这是该 Provider 唯一的启用 Key，停用 / 删除会让整个 Provider "
                    + "变成「未配置」。请先新增一把备位（或直接写入新 Key），再操作当前这把: provider=" + provider);
        }
        return standbys.get(0);
    }

    /** 把一行由 {@code ACTIVE} 降为 {@code DISABLED}（CAS，状态被并发改动即抛错回滚）。 */
    private void demoteActive(CredentialVault row, String operator, String detail) {
        boolean ok = lambdaUpdate()
                .eq(CredentialVault::getId, row.getId())
                .eq(CredentialVault::getStatus, CredentialStatus.ACTIVE)
                .set(CredentialVault::getStatus, CredentialStatus.DISABLED)
                .set(CredentialVault::getRemark,
                        appendRemark(row, "deactivated at " + OffsetDateTime.now()))
                .update();
        if (!ok) {
            throw new BizException("启用位状态冲突，降级失败（CAS）: id=" + row.getId());
        }
        row.setStatus(CredentialStatus.DISABLED);
        recordAudit(row, CredentialAuditAction.REVOKE, normalizeOperator(operator), detail);
    }

    /** 把一行由 {@code DISABLED} 升为 {@code ACTIVE}（CAS）。 */
    private void promoteStandby(CredentialVault row, String operator, String detail) {
        boolean ok = lambdaUpdate()
                .eq(CredentialVault::getId, row.getId())
                .eq(CredentialVault::getStatus, CredentialStatus.DISABLED)
                .set(CredentialVault::getStatus, CredentialStatus.ACTIVE)
                .set(CredentialVault::getRemark,
                        appendRemark(row, "activated at " + OffsetDateTime.now()))
                .update();
        if (!ok) {
            throw new BizException("备位状态冲突，启用失败（CAS）: id=" + row.getId());
        }
        row.setStatus(CredentialStatus.ACTIVE);
        recordAudit(row, CredentialAuditAction.ACTIVATE, normalizeOperator(operator), detail);
    }

    /** 列表排序档位：启用位在前，其次备位，再次轮换淘汰的历史行（未知状态排最后）。 */
    private static int statusRank(CredentialStatus status) {
        if (status == CredentialStatus.ACTIVE) {
            return 0;
        }
        if (status == CredentialStatus.DISABLED) {
            return 1;
        }
        if (status == CredentialStatus.EXPIRED) {
            return 2;
        }
        return 3;
    }

    private PlatformApiKeyView toPlatformApiKeyView(CredentialVault row) {
        return new PlatformApiKeyView(row.getId(), row.getStatus(), row.getFingerprint(),
                maskOrNull(row), row.getCreateTime(), row.getRemark());
    }

    /**
     * 行内脱敏（best-effort）：密文不可解 / 无密文（{@code secretRef} 型）⇒ {@code null}，不阻断列表。
     *
     * <p>与 {@link #backfillFingerprints} 同风格：单行失败只 WARN。列表的可用性不应被
     * 某一行密钥的历史遗留问题拖垮（例如轮换过 AES 主密钥后的旧行）。</p>
     */
    private String maskOrNull(CredentialVault row) {
        String cipher = row.getEncryptedValue();
        if (cipher == null || cipher.isBlank()) {
            return null;
        }
        try {
            return SecretMask.mask(credentialCryptoService.decryptFromBase64(cipher));
        } catch (RuntimeException e) {
            log.warn("平台级 Key 行内脱敏失败（跳过该行脱敏值，不阻断列表）: id={}, reason={}",
                    row.getId(), e.getMessage());
            return null;
        }
    }

    private static String normalizeOperator(String operator) {
        return operator != null && !operator.isBlank() ? operator : CredentialAuditAction.OPERATOR_ADMIN;
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
        // `M-8` 平台级启用位不得缺失：旧端点 POST /api/credentials/revoke/{id} 也必须走同一判据，
        // 否则守卫只在新端点上「存在」，却可被旧入口绕过（「规则存在」≠「全路径强制」）
        if (vault.getOwnerType() == CredentialOwnerType.PLATFORM
                && vault.getStatus() == CredentialStatus.ACTIVE) {
            CredentialVault successor = requireStandbyForSuccession(vault.getProvider());
            demoteActive(vault, operator, "manual revoke (platform active)");
            promoteStandby(successor, operator,
                    "auto promoted on revoke of previous active (id=" + id + ")");
            return vault;
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
