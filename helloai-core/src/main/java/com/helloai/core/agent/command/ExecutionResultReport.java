package com.helloai.core.agent.command;

import lombok.Data;

import java.util.List;

@Data
public class ExecutionResultReport {
    private Long subTaskId;
    private Long agentId;
    /**
     * 上报方持有的执行记录 ID（可为 {@code null}）。
     *
     * <p>用于失败回写的<b>归属校验</b>：仅当该记录仍为 {@code RUNNING}（即本轮上报方确实
     * 持有这次执行）时才推进 {@code BLOCKED}；否则视为「并发修改 / 重复消费」的输家，
     * 不阻塞子任务（终态交由接管方决定）。804 假 BLOCKED 引入。</p>
     */
    private Long recordId;
    private String source;
    private String idempotencyKey;
    private boolean success;
    private String executorName;
    private String finishReason;
    private Object tokenUsage;
    private String output;
    /**
     * 结构化产物分片（ADR-009 §8.6 / {@code AQ-7} 方案 A，第一批）。
     *
     * <p>非空时 {@link #output} 不再是独立事实源，而是 {@link ExecutionPart#projectToText}
     * 的纯文本投影——两者在<b>同一个装配点</b>（{@code ExecutionResultHandler#handleReport}）
     * 一次性构造，禁止在调用方分别写（双写必分叉）。</p>
     *
     * <p>内部执行链不产生分片（恒为 null，行为与旧协议完全一致）；外部执行者经
     * {@code submitResult.parts} 上报，协议层已拒收 file 分片。</p>
     */
    private List<ExecutionPart> parts;
    private String thinking;
    private String error;
}
