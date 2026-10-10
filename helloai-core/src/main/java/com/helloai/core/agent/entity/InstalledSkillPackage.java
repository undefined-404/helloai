package com.helloai.core.agent.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.helloai.common.base.BaseEntity;
import com.helloai.core.shared.handler.PgJsonbTypeHandler;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.util.List;
import java.util.Map;

/**
 * 已安装技能包（REF-1.6）——受控存储中的技能包行。
 *
 * <p><b>命名避让</b>：技能域已有 {@link com.helloai.core.agent.skill.SkillPackage}（record，
 * 从 md frontmatter 解析出的<b>元数据</b>）。本类是它的<b>持久化形态</b>——多出正文、
 * 状态、来源与完整性字段，故以「已安装」限定词区分；表名仍为 {@code skill_package}。</p>
 *
 * <p><b>双源</b>：与 classpath 内置源（{@code skills/plugins/*.md}）并存。本表装
 * {@code origin=INSTALLED} 的包（{@code BUILTIN} 为预留取值，内置包当前不落库）。
 * 与内置同名的安装请求由服务层直接拒绝（`D-2026-10-10-1⑨`）。</p>
 *
 * <p><b>版本策略</b>：`(name, version)` 唯一、多版本共存；同 name 至多一行
 * {@code state=ACTIVE}（DB partial unique index 保证）。回滚 / 降版只是翻 state。</p>
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName(value = "skill_package", autoResultMap = true)
public class InstalledSkillPackage extends BaseEntity {

    /** 状态：当前生效。同 name 至多一行（DB partial unique index 保证）。 */
    public static final String STATE_ACTIVE = "ACTIVE";

    /** 状态：历史版本。保留供回滚（{@code activate} 把它翻回 ACTIVE）。 */
    public static final String STATE_HISTORICAL = "HISTORICAL";

    /** 状态：已停用。目录不读，也不再被回滚命中。 */
    public static final String STATE_DISABLED = "DISABLED";

    /** 技能标签（= frontmatter 的 name），也是 required_skills 命中的键。 */
    private String name;

    /** 三段式数字版本；与 name 构成唯一键（多版本共存，供回滚）。 */
    private String version;

    /** 完整描述（中文）。 */
    private String description;

    /** 声明依赖的平台工具（并入 Agent 启用工具清单）。 */
    @TableField(typeHandler = PgJsonbTypeHandler.class)
    private List<String> requiredTools;

    /** 依赖的其它技能标签。 */
    @TableField(typeHandler = PgJsonbTypeHandler.class)
    private List<String> dependencies;

    /** 入参 schema（JSONB 对象）。 */
    @TableField(typeHandler = PgJsonbTypeHandler.class)
    private Map<String, Object> inputSchema;

    /** 出参 schema（JSONB 对象）。 */
    @TableField(typeHandler = PgJsonbTypeHandler.class)
    private Map<String, Object> outputSchema;

    /** 校验规则（JSONB 数组）。 */
    @TableField(typeHandler = PgJsonbTypeHandler.class)
    private List<String> validationRules;

    /**
     * 技能包<b>原始正文</b>（含 YAML frontmatter），<b>非</b>预渲染速览。
     *
     * <p>落 PG 的理由见 `D-2026-10-10-1②⑩`：{@code resolve()} 在每轮装配热路径读正文，
     * 正文若只在 MinIO，则 MinIO 故障会让技能注入整链断；存原文还能让渲染器升级后
     * 存量包自动重算。</p>
     */
    private String body;

    /** 包内清单文件名（固定 {@code skill-package-manifest.md}），记录本包按哪个清单校验通过。 */
    private String manifestName;

    /** 来源：{@code BUILTIN}（预留）/ {@code INSTALLED}。 */
    private String origin;

    /** REF-1.4 来源锁定标记：{@code 1}=禁止下游改写（打戳能力见 REF-1.4）。 */
    private Integer locked;

    /** 状态：{@code ACTIVE}（当前生效，同 name 唯一）/ {@code HISTORICAL}（可回滚）/ {@code DISABLED}。 */
    private String state;

    /** 原始上传 zip 的 SHA-256（完整性 / 溯源）。 */
    private String checksumSha256;

    /** 原始上传 zip 在 {@code ArtifactStorage} 中的地址（{@code minio://} 或 {@code local://}）。 */
    private String originZipUrl;
}
