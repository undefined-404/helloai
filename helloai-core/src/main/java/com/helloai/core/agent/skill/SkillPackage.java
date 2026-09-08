package com.helloai.core.agent.skill;

import java.util.List;

/**
 * 平台技能包元数据（P1 Skill Capability 第一步，与 {@code ToolRegistry}/{@code ToolDefinition}
 * 同形态的元数据消费面）。
 *
 * <p>承载技能的结构化元数据：{@code name} / {@code version} / {@code description} /
 * {@code requiredTools}；instructions 继续由 markdown 文件承载（{@code fileName} 指向
 * classpath {@code skills/plugins/xxx.md}），保持 Markdown 兼容、不重写现有 Skill 系统
 * （CODE_STYLE §50.7：不建平行 Registry，复用 {@code AgentSkillSpecService} 收拢）。</p>
 *
 * <p>当前技能文件未声明工具依赖，{@code requiredTools} 为空列表；后续技能声明依赖时
 * 增量填充，不臆造未存在字段（与 {@code ToolDefinition} 同哲学）。</p>
 */
public record SkillPackage(
        String name,
        String version,
        String description,
        List<String> requiredTools,
        String fileName) {

    public SkillPackage {
        name = name == null ? "" : name;
        version = version == null ? "" : version;
        description = description == null ? "" : description;
        requiredTools = requiredTools == null ? List.of() : List.copyOf(requiredTools);
        fileName = fileName == null ? "" : fileName;
    }
}