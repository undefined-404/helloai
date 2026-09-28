package com.helloai.core.agent.domain;

import lombok.Builder;
import lombok.Value;
import lombok.With;

import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * 平台内 Agent 执行任务封装。
 *
 * <p>先收敛到最小执行输入，避免把 Controller / MQ / Prompt 拼接细节直接暴露给 Executor。</p>
 */
@Value
@Builder
@With
public class AgentTask {

    /** 关联子任务 ID；无具体子任务时可为空。 */
    Long subTaskId;

    /** system prompt。 */
    String systemPrompt;

    /** user prompt。 */
    String userPrompt;

    /** 执行上下文。 */
    @Builder.Default
    Map<String, Object> context = Collections.emptyMap();

    /** 执行前要求的能力。 */
    @Builder.Default
    Map<String, Object> requiredCapabilities = Collections.emptyMap();

    /**
     * 平台技能规范标签（eng-*）声明。非空时由契约层（{@code PlatformAgentExecutionServiceImpl}）
     * 统一 resolve 注入 {@code systemPrompt}；拆解/审查/报告三条同步链声明，子任务执行链
     * 不填（走 {@code ExecutionCommand.requiredSkills} 自拼，保护 SKILL_RESOLVED / TOOL_RESOLVED
     * 与 requiredTools 联动）。
     */
    @Builder.Default
    List<String> skills = Collections.emptyList();

    /**
     * 采样温度（可选）。null = 使用模型默认值（现有全部链路行为不变）；
     * 仅稳定改写类辅助场景（如 PromptEnhancer 输入优化）显式设置低值。
     */
    Double temperature;
}
