package com.helloai.core.agent.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.helloai.common.base.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 技能包安装 / 激活 / 卸载 / 拒绝的审计行（REF-1.6）。
 *
 * <p><b>为什么单独落表</b>：安装是**供应链入口**（新的对外输入面），比普通管理端 CRUD 重，
 * 对齐凭证域 {@code CredentialAuditLog} 的先例（`D-2026-10-10-1⑥`）。</p>
 *
 * <p><b>为什么显式写 operator</b>：{@code MyBatisPlusMetaObjectHandler}（helloai-start）
 * 的 {@code getCurrentUser()} 是硬编码桩、恒返 {@code "system"}，全平台 {@code create_by}
 * 都不记操作人（差距表 §7.1.3 R5）。因此本表的 {@code operatorId} / {@code operatorName}
 * 必须由应用从 Sa-Token 会话显式写入，不能依赖自动填充。</p>
 *
 * <p><b>不建 FK 到 skill_package</b>：审计须在包被移除后存活，{@code packageId} 允许为空
 * （安装被拒时还没有包行）。</p>
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("skill_package_audit")
public class SkillPackageAudit extends BaseEntity {

    /** 关联 skill_package.id；安装被拒等无包行场景为 null。 */
    private Long packageId;

    /** 技能包标签。 */
    private String name;

    /** 技能包版本；安装被拒且未解析出版本时为空串。 */
    private String version;

    /** {@code INSTALL} / {@code ACTIVATE} / {@code UNINSTALL} / {@code REJECT}。 */
    private String action;

    /** 操作人 ID（取自 Sa-Token 会话，非 MetaObjectHandler）。 */
    private String operatorId;

    /** 操作人显示名（Sn 名称或用户名）。 */
    private String operatorName;

    /** {@code SUCCESS} / {@code FAIL}。 */
    private String result;

    /** 失败原因 / 拒绝理由（成功时为 null）。 */
    private String reason;

    /** 原始上传 zip 的 SHA-256（被拒时可为空）。 */
    private String checksumSha256;
}
