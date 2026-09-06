package com.helloai.api.dto.credential;

import lombok.Data;

import java.time.OffsetDateTime;

/**
 * 凭证操作审计响应（Phase 2 B2，N-004 收口）。
 *
 * <p>不含密文：审计行只含动作 / 操作者 / 明细 / 时间，密文永不通过审计面返回。</p>
 */
@Data
public class AuditLogResponse {

    private Long id;
    private Long credentialId;
    private String ownerType;
    private Long ownerId;
    private String provider;
    private String action;
    private String operator;
    private String detail;
    private OffsetDateTime createTime;
}
