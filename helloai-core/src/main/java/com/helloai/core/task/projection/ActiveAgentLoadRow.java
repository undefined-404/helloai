package com.helloai.core.task.projection;

import lombok.Data;

/**
 * 活跃 Agent 负载行（PA-4 改版，2026-11-02）。
 *
 * <p>按子任务 {@code IN_PROGRESS} 聚合出「正在执行任务的 Agent」及并发条数，
 * 供个人工作台「任务概况」第二屏展示内/外 Agent。</p>
 */
@Data
public class ActiveAgentLoadRow {

    private Long agentId;

    private Long runningCount;
}
