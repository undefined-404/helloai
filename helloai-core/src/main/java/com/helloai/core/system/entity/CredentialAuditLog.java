package com.helloai.core.system.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.helloai.common.base.BaseEntity;
import com.helloai.common.constant.CredentialOwnerType;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 凭证操作审计日志（Phase 2 B2，N-004 收口）。
 *
 * <p>append-only 审计台账：bind / rotate / revoke / expire 四类凭证操作落库，
 * 支撑差距表 N-004 完成标准「异常情况下如何恢复」的取证与审计。只 INSERT，
 * 不 UPDATE 业务列——审计保留原始记录（action 语义不可变）。</p>
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("credential_audit_log")
public class CredentialAuditLog extends BaseEntity {

    /** 被操作凭证 ID（credential_vault.id）。 */
    private Long credentialId;

    /** 归属对象类型。 */
    private CredentialOwnerType ownerType;

    /** 归属对象 ID。 */
    private Long ownerId;

    /** LLM Provider 标识。 */
    private String provider;

    /** 动作：bind / rotate / revoke / expire（见 CredentialAuditAction）。 */
    private String action;

    /** 操作者：admin / system。 */
    private String operator;

    /** 关键事实明细（如 rotated_from_id / 过期前状态）。 */
    private String detail;
}
