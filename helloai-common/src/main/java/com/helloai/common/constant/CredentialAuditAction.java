package com.helloai.common.constant;

/**
 * 凭证操作审计动作（Phase 2 B2，N-004 收口）。
 *
 * <p>存储于 {@code credential_audit_log.action}（snake_case 字符串）。
 * 用常量类而非枚举：动作集随管理操作演进，硬编码常量 + 字符串列
 * 便于扩展（与 {@code AgentEventType} 枚举风格区分——后者参与执行语义）。</p>
 */
public final class CredentialAuditAction {

    /** 绑定 / 保存凭证（含平台级保存、Agent 级绑定）。 */
    public static final String BIND = "bind";

    /** 轮换凭证（旧 ACTIVE → EXPIRED，新凭证 → ACTIVE）。 */
    public static final String ROTATE = "rotate";

    /** 人工停用（旧凭证 → DISABLED，人为失效语义）。 */
    public static final String REVOKE = "revoke";

    /** 过期扫描自动失效（ACTIVE 且 expire_time 已过 → EXPIRED）。 */
    public static final String EXPIRE = "expire";

    /** 操作者：管理端。 */
    public static final String OPERATOR_ADMIN = "admin";

    /** 操作者：定时任务。 */
    public static final String OPERATOR_SYSTEM = "system";

    private CredentialAuditAction() {
    }
}
