package com.helloai.api.dto.skill;

import lombok.Data;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * 已安装技能包（REF-1.6）——安装 / 激活 / 列表的响应体。
 *
 * <p><b>不复用 {@link SkillPackageResponse}</b>：那个是「技能目录」的读模型
 * （classpath 内置 + 已安装合并后的元数据视图，供前端下拉与标签用）；本类是
 * 「已安装行」的管理视图，带 id / state / 摘要 / 原件地址 / 安装时间等管理字段。
 * 两者消费场景不同，混用会让前端分不清「目录里有什么」与「装了哪几版」。</p>
 *
 * <p>不暴露 core 实体（CODE_STYLE §11.3）。</p>
 */
@Data
public class InstalledSkillPackageResponse {

    /** skill_package.id。 */
    private Long id;

    /** 技能标签（= frontmatter 的 name）。 */
    private String name;

    /** 三段式版本。 */
    private String version;

    /** 完整描述。 */
    private String description;

    /** 状态：ACTIVE（当前生效）/ HISTORICAL（历史版本，可 activate 回滚）/ DISABLED（已卸载）。 */
    private String state;

    /** 声明依赖的平台工具。 */
    private List<String> requiredTools;

    /** 原始上传 zip 的 SHA-256（完整性 / 溯源）。 */
    private String checksumSha256;

    /** 原始上传 zip 在受控存储中的地址（minio:// 或 local://）。 */
    private String originZipUrl;

    /** 安装时间。 */
    private OffsetDateTime createTime;

    /** 安装人（来自审计，非 MetaObjectHandler）。 */
    private String createBy;
}
