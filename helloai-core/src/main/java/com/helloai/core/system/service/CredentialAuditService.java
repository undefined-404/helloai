package com.helloai.core.system.service;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.helloai.common.constant.CredentialOwnerType;
import com.helloai.core.system.entity.CredentialAuditLog;

/**
 * 凭证操作审计服务（Phase 2 B2，N-004 收口）。
 *
 * <p>append-only 审计台账写入与查询。审计与凭证主操作同事务：审计失败整体回滚
 * （fail-close——无审计的凭证变更宁可不做）。</p>
 */
public interface CredentialAuditService {

    /**
     * 记录一条凭证操作审计。
     *
     * <p>与调用方事务合并（REQUIRED 传播）；action 取值见
     * {@link com.helloai.common.constant.CredentialAuditAction}。</p>
     */
    void record(Long credentialId, CredentialOwnerType ownerType, Long ownerId,
                String provider, String action, String operator, String detail);

    /**
     * 分页查询审计（条件均可选：凭证 ID / 归属类型 / 归属 ID）。
     */
    IPage<CredentialAuditLog> listAudits(Long credentialId, CredentialOwnerType ownerType,
                                         Long ownerId, long page, long size);
}
