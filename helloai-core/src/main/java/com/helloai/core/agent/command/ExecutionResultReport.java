package com.helloai.core.agent.command;

import lombok.Data;

@Data
public class ExecutionResultReport {
    private Long subTaskId;
    private Long agentId;
    private String source;
    private String idempotencyKey;
    private boolean success;
    private String executorName;
    private String finishReason;
    private Object tokenUsage;
    private String output;
    private String thinking;
    private String error;
}
