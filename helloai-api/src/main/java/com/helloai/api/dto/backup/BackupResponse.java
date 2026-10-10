package com.helloai.api.dto.backup;

import lombok.Data;

import java.time.OffsetDateTime;

/**
 * 平台备份台账的对外视图（REF-2.3）。
 *
 * <p>不暴露 core 实体（CODE_STYLE §11.3）；枚举字段在此**降级为字符串**
 * —— 对外契约保持稳定，实体侧的类型演进（§12.2 要求枚举）不外溢到接口。</p>
 */
@Data
public class BackupResponse {

    /** 台账 id。 */
    private Long id;

    /** {@code MANUAL}（永不淘汰）/ {@code AUTO}（参与保留淘汰）。 */
    private String backupType;

    /** {@code RUNNING} / {@code SUCCESS} / {@code FAILED} —— **前端轮询本字段**。 */
    private String state;

    /** 备份在桶下的前缀（成功后有值）。 */
    private String objectPrefix;

    /** 归档头给出的 PG 版本（跨引擎拒的判据来源）。 */
    private String pgVersion;

    /** 备份时数据库的 schema 版本（恢复侧门② 的判据来源）。 */
    private String flywayMaxVersion;

    /** 纳入备份的对象数。 */
    private Integer artifactCount;

    /** 纳入备份的对象总字节数。 */
    private Long artifactBytes;

    /** dump 归档字节数。 */
    private Long dumpBytes;

    /** 备份总字节数。 */
    private Long totalBytes;

    /** dump 归档的 SHA-256。 */
    private String checksumSha256;

    /** 失败原因（{@code FAILED} 时有值）。 */
    private String failureReason;

    /** 开始时刻。 */
    private OffsetDateTime startedAt;

    /** 结束时刻。 */
    private OffsetDateTime finishedAt;
}
