package com.helloai.core.task.spec;

/**
 * Task Running Spec 服务接口——隔离存储细节，Phase A JSONB / Phase B 独立表共享同一接口。
 *
 * <p>消费方（executor、ExecutionResultHandler、PlannerAnalysisService）
 * 只依赖本接口，不感知底层存储实现。</p>
 */
public interface TaskRunningSpecService {

    /**
     * 获取或创建空 Running Spec。
     *
     * @param taskId 主任务 ID
     * @return 当前 Running Spec（新任务返回 EMPTY）
     */
    TaskRunningSpec getOrCreate(Long taskId);

    /**
     * 初始化 Baseline（Planner 确认拆解后调用）。
     *
     * <p>幂等：若 Baseline 已存在则跳过。</p>
     *
     * @param taskId   主任务 ID
     * @param baseline Planner 创建的任务全局规格
     */
    void initialize(Long taskId, TaskBaseline baseline);

    /**
     * 写入一条 executor 回填的执行记录（按 subTaskId 去重，rework 覆盖旧记录），
     * 并基于去重后的全量记录自动重新编译 Context Summary。
     *
     * @param taskId 主任务 ID
     * @param record executor 回填的结构化摘要
     */
    void appendExecutionRecord(Long taskId, ExecutionRecord record);

    /**
     * 构建 executor Prompt 上下文段。
     *
     * <p>返回 Markdown 格式的上下文章节文本，包含：Baseline 全局目标 +
     * 已完成的执行记录摘要 + Context Summary。无内容时返回空串。</p>
     *
     * @param taskId 主任务 ID
     * @return Prompt 上下文段；无内容时返回空串
     */
    String buildExecutorPromptSection(Long taskId);

    /**
     * 从所有 ExecutionRecords 重新编译 Context Summary。
     *
     * <p>编译逻辑：拼接每条记录的 summary + 关键下游须知，
     * 形成面向下游 executor 的连贯上下文段落。</p>
     *
     * @param taskId 主任务 ID
     */
    void compileContextSummary(Long taskId);
}
