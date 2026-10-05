package com.helloai.core.agent.command;

import lombok.Data;

@Data
public class ExecutionResultReport {
    private Long subTaskId;
    private Long agentId;
    /**
     * 上报方持有的执行记录 ID（可为 {@code null}）。
     *
     * <p>用于失败回写的<b>归属校验</b>：仅当该记录仍为 {@code RUNNING}（即本轮上报方确实
     * 持有这次执行）时才推进 {@code BLOCKED}；否则视为「并发修改 / 重复消费」的输家，
     * 不阻塞子任务（终态交由接管方决定）。2026-10-05 修 804 假 BLOCKED 引入。</p>
     */
    private Long recordId;
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
