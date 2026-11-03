package com.helloai.core.task.projection;

import lombok.Data;

/**
 * 活跃 Agent 负载行（PA-4 改版，2026-11-02；2026-11-03 补复核计数）。
 *
 * <p>按子任务状态分组出「该 Agent 名下正在执行（IN_PROGRESS）/ 处于复核流程（REVIEW）」
 * 的子任务条数，供个人工作台「活跃 Agent」区块判定每人的实时状态。</p>
 */
@Data
public class ActiveAgentLoadRow {

    private Long agentId;

    private Long runningCount;

    private Long reviewCount;
}
