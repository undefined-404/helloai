package com.helloai.api.dto.skill;

import lombok.Data;

import java.util.List;
import java.util.Map;

/**
 * 平台技能包目录项（REF-1.2c）。
 *
 * <p>前端消费 {@link #label} 与 {@link #name}：label 由服务端从 {@code description} 派生
 * （{@code name + "（" + description 首个全角冒号之前 + "）"}），<b>取代原先前端手抄的对齐副本</b>
 * ——任何技能目录变更都不再需要改前端。</p>
 *
 * <p>{@link #corrupt} 为 true 时，{@link #error} 给出人可读原因，其余元数据字段为降级空值
 * （{@code name} 取文件名去 {@code .md}）——坏技能包<b>显式可见</b>，不被静默丢弃。</p>
 *
 * <p>命名刻意用 {@code skills/catalog}（平台能力包），与「Agent 角色接入手册 ZIP」
 * （{@code AdminAgentController.getMySkillZipByAgentId}）区分——后者交付物内虽名为 SKILL.md，
 * 但两者不是一件事（见《文档体系分类与治理规则》§3.7）。</p>
 */
@Data
public class SkillPackageResponse {

    /** 技能标签（= frontmatter 的 name，也是 required_skills 命中的键）。 */
    private String name;

    /** 版本（三段式）。 */
    private String version;

    /** 完整描述（中文）。 */
    private String description;

    /** 声明依赖的平台工具（并入 Agent 启用工具清单）。 */
    private List<String> requiredTools;

    /** 声明依赖的其他技能。 */
    private List<String> dependencies;

    /** 输入结构声明（JSON Schema 形态；无声明为空映射）。 */
    private Map<String, Object> inputSchema;

    /** 输出结构声明（JSON Schema 形态；无声明为空映射）。 */
    private Map<String, Object> outputSchema;

    /** 校验规则清单（审查侧与执行侧共用同一词汇表）。 */
    private List<String> validationRules;

    /** 定义文件名（由目录扫描推导，非 frontmatter 字段）。 */
    private String fileName;

    /** 前端显示名（服务端派生，取代前端硬编码副本）。 */
    private String label;

    /** 是否为不可用技能包（frontmatter 缺失 / 非法）。 */
    private boolean corrupt;

    /** 不可用原因（人可读）；健康时为 null。 */
    private String error;
}
