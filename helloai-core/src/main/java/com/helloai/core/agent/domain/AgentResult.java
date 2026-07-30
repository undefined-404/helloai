package com.helloai.core.agent.domain;

import lombok.Builder;
import lombok.Value;

/**
 * 平台内 Agent 执行结果。
 */
@Value
@Builder
public class AgentResult {

    boolean success;
    String output;
    /** 推理模型思考过程（可为 null）；正文与思考分离后保留，供对话流与前端展示 */
    String thinking;
    String errorMessage;
    String finishReason;
    String executorName;
    Integer tokenUsage;

    public static AgentResult success(String output, String finishReason, String executorName, Integer tokenUsage) {
        return success(output, null, finishReason, executorName, tokenUsage);
    }

    public static AgentResult success(String output, String thinking, String finishReason,
                                      String executorName, Integer tokenUsage) {
        return AgentResult.builder()
                .success(true)
                .output(output)
                .thinking(thinking)
                .finishReason(finishReason)
                .executorName(executorName)
                .tokenUsage(tokenUsage)
                .build();
    }

    public static AgentResult failure(String errorMessage, String finishReason, String executorName) {
        return AgentResult.builder()
                .success(false)
                .errorMessage(errorMessage)
                .finishReason(finishReason)
                .executorName(executorName)
                .build();
    }
}
