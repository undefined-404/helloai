package com.helloai.core.system.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.helloai.common.base.BaseEntity;
import com.helloai.common.constant.PlatformBackupState;
import com.helloai.common.constant.PlatformBackupType;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.OffsetDateTime;

/**
 * 平台备份台账（REF-2.3，表 {@code platform_backup}）。
 *
 * <p>回答「有哪些备份、谁触发的、成功没有、多大」——否则这些事实只存在于对象存储里，无从查询。</p>
 *
 * <p><b>落点为什么在 system 域</b>：备份是**系统级运维能力**（与存储 / 对账巡检同类，
 * 见 {@code system.storage}），不是业务流程；符合 CODE_STYLE §5.5「system 域含存储，
 * 禁止将具体业务流程塞入 system」。台账实体与 {@link CredentialAuditLog} 同处。</p>
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("platform_backup")
public class PlatformBackup extends BaseEntity {

    /**
     * 触发类型（**枚举**：驱动保留淘汰策略 —— {@code MANUAL} 永不淘汰 / {@code AUTO} 参与）。
     *
     * <p>持久化为 {@code name()}，与 V105 的 {@code CHECK (backup_type IN ('MANUAL','AUTO'))} 对齐。</p>
     */
    private PlatformBackupType backupType;

    /**
     * 状态（**枚举**，CODE_STYLE §12.2「状态用枚举」/ §64 Checklist）。
     *
     * <p>持久化为 {@code name()}，与 V105 的 {@code CHECK (state IN ('RUNNING','SUCCESS','FAILED'))} 对齐。</p>
     */
    private PlatformBackupState state;

    /** 本次备份在备份桶下的前缀（一次备份一个前缀，内部含 manifest 与 dump）。 */
    private String objectPrefix;

    /** manifest 对象键 —— 恢复侧三门先读它（不解档）。 */
    private String manifestKey;

    /** {@code pg_dump -Fc} 归档对象键。 */
    private String dumpKey;

    /** 归档头 {@code Dumped from database version}（跨引擎拒的判据）。 */
    private String pgVersion;

    /** 备份时的 {@code flyway_schema_history} 最高版本（schema 高过运行时拒的判据）。 */
    private String flywayMaxVersion;

    /** 纳入本次备份的对象存储对象数（{@code listObjects} 枚举结果）。 */
    private Integer artifactCount;

    /** 纳入本次备份的对象总字节数。 */
    private Long artifactBytes;

    /** dump 归档字节数。 */
    private Long dumpBytes;

    /** 备份总字节数（dump + manifest + 对象合计口径由实现定义）。 */
    private Long totalBytes;

    /** manifest 的 SHA-256（备份整体摘要）。 */
    private String checksumSha256;

    /** 失败原因（成功时为 null）。 */
    private String failureReason;

    /** 开始时刻。 */
    private OffsetDateTime startedAt;

    /** 结束时刻（成功或失败均写）。 */
    private OffsetDateTime finishedAt;
}
