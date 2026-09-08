package com.helloai.core.agent.skill;

import java.util.List;
import java.util.Map;

/**
 * 平台技能包元数据（P1 Skill Capability，与 {@code ToolRegistry}/{@code ToolDefinition}
 * 同形态的元数据消费面）。
 *
 * <p>承载技能的结构化元数据：{@code name} / {@code version} / {@code description} /
 * {@code requiredTools} / {@code dependencies} / {@code inputSchema} / {@code outputSchema} /
 * {@code validationRules}；instructions 继续由 markdown 文件承载（{@code fileName} 指向
 * classpath {@code skills/plugins/xxx.md}），保持 Markdown 兼容、不重写现有 Skill 系统
 * （CODE_STYLE §50.7：不建平行 Registry，复用 {@code AgentSkillSpecService} 收拢）。</p>
 *
 * <p>诚实边界：{@code inputSchema} / {@code outputSchema} 以 JSON Schema（{@code Map}）表达，
 * 仅当技能文件明确声明输出/验证结构时填充（如 eng-code-review 四元组、eng-verification
 * 验证证据）；无声明字段保持空（不臆造未存在结构）。</p>
 */
public record SkillPackage(
        String name,
        String version,
        String description,
        List<String> requiredTools,
        List<String> dependencies,
        Map<String, Object> inputSchema,
        Map<String, Object> outputSchema,
        List<String> validationRules,
        String fileName) {

    public SkillPackage {
        name = name == null ? "" : name;
        version = version == null ? "" : version;
        description = description == null ? "" : description;
        requiredTools = requiredTools == null ? List.of() : List.copyOf(requiredTools);
        dependencies = dependencies == null ? List.of() : List.copyOf(dependencies);
        inputSchema = inputSchema == null ? Map.of() : Map.copyOf(inputSchema);
        outputSchema = outputSchema == null ? Map.of() : Map.copyOf(outputSchema);
        validationRules = validationRules == null ? List.of() : List.copyOf(validationRules);
        fileName = fileName == null ? "" : fileName;
    }
}