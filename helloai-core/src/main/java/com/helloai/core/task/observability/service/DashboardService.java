package com.helloai.core.task.observability.service;

import java.util.Map;

/**
 * Dashboard 统计聚合服务（前端主页面 {@code /api/dashboard/stats} 数据源）。
 *
 * <p>聚合任务计数、子任务状态分布、Agent 积分排行与近 7 天吞吐量；
 * 与 {@link AdminDashboardService} 类似不绑定单一 Mapper。</p>
 *
 * <p>返回 {@code Map<String, Object>} 而非具体 DTO，因为 {@code helloai-core}
 * 不依赖 {@code helloai-api}，DTO 装配由 Controller 完成（§6.7 聚合看板语义）。</p>
 */
public interface DashboardService {

    /**
     * Dashboard 主统计。
     *
     * @return 包含 totalTasks / activeSubTasks / pendingReviews / blockedTasks /
     *         agentRanking / throughput 六个字段的 Map
     */
    Map<String, Object> getStats();
}