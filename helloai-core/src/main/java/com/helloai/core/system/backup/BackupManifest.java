package com.helloai.core.system.backup;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.helloai.common.base.BizException;

import java.time.OffsetDateTime;

/**
 * 备份清单（manifest）—— REF-2 的**自描述元数据**，独立于 dump 归档单独存放。
 *
 * <p><b>为什么要有它</b>：恢复侧必须能在**不解档**的前提下回答三个问题
 * （`D-2026-10-10-2⑦` 的前两门）：
 * <ul>
 *   <li>这份备份是哪个引擎、哪个版本导出的？ ⇒ {@link #pgVersion}（跨引擎拒）</li>
 *   <li>它包含的 schema 会不会比当前运行时更新？ ⇒ {@link #flywayMaxVersion}（schema 高过运行时拒）</li>
 *   <li>它到底包含哪些对象、多大、摘要对不对？</li>
 * </ul>
 * 若这些只能从 dump 里读，就必须先解档 —— 那等于把校验放在最危险的动作之后。</p>
 *
 * <p><b>为什么是独立对象而非塞进 dump</b>：{@code pg_dump -Fc} 的归档格式由 PG 决定，
 * 塞不进自定义字段；而 {@code pg_restore -l} 能给出 {@code Dumped from database version}
 * 却给不出 flyway 版本与对象清单。故 manifest 是**并列的一个 JSON 对象**，先读它、再决定要不要动 dump。</p>
 *
 * <p><b>字段与台账的关系</b>：{@code platform_backup} 表是**可查询的台账**（列表/保留淘汰），
 * manifest 是**随备份走的自描述**（备份被搬走后仍能自证）。两者刻意部分冗余 ——
 * 台账会随行删除，manifest 不会。</p>
 */
public record BackupManifest(
        /** 台账 id（{@code platform_backup.id}）。 */
        Long backupId,
        /** {@code MANUAL} / {@code AUTO}。 */
        String backupType,
        /** 备份完成时刻（ISO8601 带偏移）。 */
        OffsetDateTime createdAt,
        /** 归档头 {@code Dumped from database version}（跨引擎拒的判据）。 */
        String pgVersion,
        /** 归档头 {@code Dumped by pg_dump version}（诊断用）。 */
        String pgDumpVersion,
        /** 备份时的 {@code flyway_schema_history} 最高版本（schema 高过运行时拒的判据）。 */
        String flywayMaxVersion,
        /** 数据库名（恢复侧核对目标库用）。 */
        String database,
        /** dump 归档对象键。 */
        String dumpKey,
        /** dump 归档字节数。 */
        long dumpBytes,
        /** dump 归档的 SHA-256。 */
        String dumpSha256,
        /** 纳入备份的对象存储对象数。 */
        int artifactCount,
        /** 纳入备份的对象存储对象总字节数。 */
        long artifactBytes,
        /** 对象存储桶名（恢复侧核对用）。 */
        String artifactBucket,
        /** 操作者（管理端登录名 / {@code system} 表示定时任务）。 */
        String operator
) {

    /** manifest 的固定对象名（与 dump 并列在前缀内）。 */
    public static final String OBJECT_NAME = "manifest.json";

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);

    /** 序列化为 JSON 文本（manifest 落库/落桶都用它）。 */
    public String toJson() {
        try {
            return MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(this);
        } catch (Exception e) {
            throw new BizException("备份清单序列化失败: " + e.getMessage());
        }
    }

    /**
     * 从 JSON 文本反序列化。
     *
     * <p>忽略未知字段（{@code FAIL_ON_UNKNOWN_PROPERTIES=false}）——**向前兼容**：
     * 新版本备份写的 manifest 被旧版本读时，不因多出字段而失败。</p>
     */
    public static BackupManifest fromJson(String json) {
        try {
            return MAPPER.readValue(json, BackupManifest.class);
        } catch (Exception e) {
            throw new BizException("备份清单解析失败（可能不是合法 manifest）: " + e.getMessage());
        }
    }
}
