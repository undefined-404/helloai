package com.helloai.core.agent.skill;

import java.util.List;
import java.util.Map;

/**
 * 平台技能包元数据（P1 Skill Capability，与 {@code ToolRegistry}/{@code ToolDefinition}
 * 同形态的元数据消费面）。
 *
 * <p>承载技能的结构化元数据：{@code name} / {@code version} / {@code description} /
 * {@code requiredTools} / {@code requiredBaseCapabilities} / {@code dependencies} /
 * {@code inputSchema} / {@code outputSchema} / {@code validationRules}；instructions 继续由
 * markdown 文件承载（{@code fileName} 指向 classpath {@code skills/plugins/xxx.md}），
 * 保持 Markdown 兼容、不重写现有 Skill 系统
 * （CODE_STYLE §50.7：不建平行 Registry，复用 {@code AgentSkillSpecService} 收拢）。</p>
 *
 * <p>诚实边界：{@code inputSchema} / {@code outputSchema} 以 JSON Schema（{@code Map}）表达，
 * 仅当技能文件明确声明输出/验证结构时填充（如 eng-code-review 四元组、eng-verification
 * 验证证据）；无声明字段保持空（不臆造未存在结构）。</p>
 *
 * <p><b>{@code requiredBaseCapabilities} = 细粒度技能 → 粗粒度模型能力的「引用边」</b>
 * （{@code G-022}）：判定式 {@code 技能可派 ⟺ 本技能的 requires ⊆ 模型 provides}。
 * 它<b>不是</b>「技能点名某个模型」——技能只声明<b>需要哪类能力</b>，由
 * {@code SkillCapabilityRequirements.provides} 反查模型谓词是否满足。
 * 缺此边时，选人处 {@code required_skills}（细粒度标签）与 {@code agent.skills}
 * （粗粒度能力标签）分属两个命名空间，AND 匹配恒失败（{@code G-022} 实测症状：
 * 有需求、无人被选上 ⇒ 永久 PENDING）。</p>
 *
 * <p>与 {@code requiredTools} 正交且不可互推：{@code requiredTools} 是「需要哪些
 * <b>工具</b>」（程序化能力，喂 {@code ToolRegistry}），本字段是「需要哪类<b>模型能力</b>」
 * （谓词，喂 {@code llm_provider_model.capabilities}）。内置 4 技能包 3 个
 * {@code requiredTools} 为空 ⇒ 反推工具依赖的覆盖率仅 1/4，且维度不同。</p>
 */
public record SkillPackage(
        String name,
        String version,
        String description,
        List<String> requiredTools,
        List<String> requiredBaseCapabilities,
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
        requiredBaseCapabilities = requiredBaseCapabilities == null
                ? List.of() : List.copyOf(requiredBaseCapabilities);
        dependencies = dependencies == null ? List.of() : List.copyOf(dependencies);
        inputSchema = inputSchema == null ? Map.of() : Map.copyOf(inputSchema);
        outputSchema = outputSchema == null ? Map.of() : Map.copyOf(outputSchema);
        validationRules = validationRules == null ? List.of() : List.copyOf(validationRules);
        fileName = fileName == null ? "" : fileName;
    }
}