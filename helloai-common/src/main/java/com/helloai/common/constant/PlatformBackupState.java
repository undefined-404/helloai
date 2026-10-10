package com.helloai.common.constant;

/**
 * 平台备份的状态（REF-2.3，表 {@code platform_backup.state}）。
 *
 * <p>与 {@link SkillPackageState} 同因：CODE_STYLE §12.2 / §64 要求「状态使用枚举」。
 * 本状态参与**保留淘汰**的准入判定（只有 {@code SUCCESS} 的自动备份参与按份数保留）。</p>
 *
 * <p>持久化为 {@code name()}，与 V105 的
 * {@code CHECK (state IN ('RUNNING','SUCCESS','FAILED'))} 逐字对齐。</p>
 */
public enum PlatformBackupState {

    /** 进行中（含进程被强杀后残留的"僵尸 RUNNING"——由超时判定兜底）。 */
    RUNNING,

    /** 成功。 */
    SUCCESS,

    /** 失败（{@code failure_reason} 给出可读原因）。 */
    FAILED
}
