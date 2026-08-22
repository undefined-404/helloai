package com.helloai.core.review.service;

import com.helloai.core.shared.event.SubTaskSubmittedForReviewEvent;
import lombok.Data;

/**
 * 子任务 LLM 自动核验服务（内循环核验门控）。
 *
 * <p>入口 {@link #reviewSubTask(Long, Long)}：读取子任务 title/content/deliverable/acceptance
 * + 执行产出（context.lastExecution.output），构造核验 Prompt，经
 * {@link com.helloai.core.agent.service.PlatformAgentExecutionService#executeSync}
 * 调平台内 LLM 判定：</p>
 * <ul>
 *     <li>通过 → {@link com.helloai.core.task.service.SubTaskService#complete}（REVIEW→DONE，
 *         触发隐式评分与下游解锁）</li>
 *     <li>不通过 → {@link com.helloai.core.task.service.SubTaskService#rework}
 *         （REVIEW→REWORK，核验意见写入 context），并对 API_KEY_LLM 执行者重新下发执行命令
 *         闭合返工链</li>
 *     <li>LLM 调用失败/超时/输出不可解析 → <b>不改状态</b>，子任务停留 REVIEW 等人工兜底</li>
 * </ul>
 *
 * <p>触发点：{@code ExecutionResultHandler} 成功提交（→REVIEW）后发布
 * {@link SubTaskSubmittedForReviewEvent}，本服务以 AFTER_COMMIT + @Async 异步消费，
 * 核验 LLM 调用不阻塞结果回报事务。</p>
 *
 * <p>防重：核验前检查当前状态仍为 REVIEW；reworkCount 达
 * {@code helloai.dispatch.auto-review-max-rework}（默认 3）后停留 REVIEW 等人工，
 * 不再自动打回，避免"执行→驳回→重执行"无限循环。</p>
 */
public interface SubTaskReviewService {

    /** AFTER_COMMIT 异步监听：结果回报事务提交后触发自动核验。 */
    void onSubmittedForReview(SubTaskSubmittedForReviewEvent event);

    /**
     * REVIEW 孤儿兜底扫描：当 AFTER_COMMIT 事件链因线程池 / 序列化丢失时，
     * 基于 DB 状态的定期扫描作为二次确保。
     *
     * <p>扫描间隔可通过 {@code helloai.dispatch.review-orphan-scan-interval-ms} 配置（默认 30s），
     * 扫描阈值通过 {@code helloai.dispatch.review-orphan-threshold-seconds} 配置（默认 60s），
     * 表示子任务进入 REVIEW 超过该时间且无审查记录时才触发兜底核验。</p>
     */
    void scanReviewOrphans();

    /**
     * 对指定子任务执行一次 LLM 自动核验。
     *
     * <p>不加类级事务：LLM 调用耗时较长；complete/rework 各自内部事务原子提交，
     * 判定失败/不可解析时不改状态（子任务停留 REVIEW）。</p>
     *
     * <p>§6.82 批次 D 防双审互斥锁：L1 AFTER_COMMIT 事件 / L2 MQ consumer / L3 孤儿扫描
     * 三路可能并发触发同一子任务核验，Redis setIfAbsent 保证 LLM 调用窗口内仅一路进入
     * （其他路直接跳过），TTL 兜底崩溃残留，finally 释放。</p>
     */
    void reviewSubTask(Long subTaskId, Long executorAgentId);

    /**
     * 解析核验判定 JSON；不可解析返回 null（调用方据此停留 REVIEW）。
     */
    ReviewVerdict parseVerdict(String rawOutput);

    /** LLM 核验判定结构化输出（未知字段容忍）。 */
    @Data
    class ReviewVerdict {
        private Boolean pass;
        private Integer score;
        private String issues;
        private String comment;
        /** 逐条对照验收标准的核验分析过程（人工复核判定思路的材料，全文进对话流） */
        private String analysis;
    }
}
