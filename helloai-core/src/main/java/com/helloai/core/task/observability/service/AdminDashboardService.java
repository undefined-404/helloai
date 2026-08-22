package com.helloai.core.task.observability.service;

import java.util.List;
import java.util.Map;

/**
 * 管理后台 Dashboard 聚合统计服务。
 *
 * <p>为 {@code /api/admin/dashboard} 提供概览、高亮与趋势三类聚合查询；
 * 涉及 Task、SubTask、Agent、AgentService、SysUserService 多个数据源，
 * 不绑定单一 Mapper，故不继承 {@code ServiceImpl}。</p>
 *
 * <p>由于 {@code helloai-core} 不依赖 {@code helloai-api}，本服务仅返回
 * Map/Entity 等基础结构，DTO 装配由 Controller 完成（符合 §6.7 “聚合看板可
 * 返回专用聚合 DTO 或 Map&lt;String,Object&gt;，但不直接暴露实体”）。</p>
 */
public interface AdminDashboardService {

    /**
     * 概览统计：任务、子任务、Agent、用户与今日完成计数。
     *
     * @return 概览字段 → 计数值（按 DTO 字段顺序组织）
     */
    Map<String, Long> getOverview();

    /**
     * 阻塞子任务列表（最近 10 条，按 updateTime 倒序）。
     *
     * @return 列表中的每个 Map 含 subTaskId/subTaskTitle/priority/taskId/taskTitle
     */
    List<Map<String, Object>> listBlockedHighlight();

    /**
     * 待审查子任务列表（最近 10 条，按 updateTime 倒序）。
     */
    List<Map<String, Object>> listReviewHighlight();

    /**
     * 低活跃 Agent 列表（按近 7 天子任务数升序，取前 10）。
     */
    List<Map<String, Object>> listLowActivityAgents();

    /**
     * 趋势：近 N 天每日创建 / 完成 / 审查条数（{@code days} 不小于 1）。
     *
     * @param days 回溯天数
     * @return 包含 {@code dates}/{@code createdCounts}/{@code completedCounts}/{@code reviewedCounts} 四个并行数组
     */
    Map<String, List<?>> getTrends(int days);
}