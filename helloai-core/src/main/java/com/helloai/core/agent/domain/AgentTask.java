package com.helloai.core.agent.domain;

import lombok.Builder;
import lombok.Value;

import java.util.Collections;
import java.util.Map;

/**
 * 平台内 Agent 执行任务封装。
 *
 * <p>先收敛到最小执行输入，避免把 Controller / MQ / Prompt 拼接细节直接暴露给 Executor。</p>
 */
@Value
@Builder
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
}
