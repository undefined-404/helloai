package com.helloai.common.constant;

/**
 * 平台备份的触发类型（REF-2.3，表 {@code platform_backup.backup_type}）。
 *
 * <p><b>为什么也是枚举</b>：它不是"动作码"，而是**行为判别** —— 保留淘汰只对
 * {@link #AUTO} 生效、{@link #MANUAL} 永不淘汰（`D-2026-10-10-2⑤`）。这与
 * {@code AgentAccessType}（同为驱动行为的判别枚举）同类，而不同于按
 * {@code CredentialAuditAction} 取舍的"动作码用常量类"。</p>
 *
 * <p>持久化为 {@code name()}，与 V105 的 {@code CHECK (backup_type IN ('MANUAL','AUTO'))} 逐字对齐。</p>
 */
public enum PlatformBackupType {

    /** 手动触发 —— **永不参与保留淘汰**。 */
    MANUAL,

    /** 定时自动 —— 参与按份数保留淘汰。 */
    AUTO
}
