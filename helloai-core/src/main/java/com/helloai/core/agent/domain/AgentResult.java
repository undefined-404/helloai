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
    String errorMessage;
    String finishReason;
    String executorName;
    Integer tokenUsage;

    public static AgentResult success(String output, String finishReason, String executorName, Integer tokenUsage) {
        return AgentResult.builder()
                .success(true)
                .output(output)
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
