package com.helloai.core.system.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.helloai.common.constant.AgentStatus;
import com.helloai.common.constant.SubTaskStatus;
import com.helloai.common.constant.TaskStatus;
import com.helloai.core.agent.entity.Agent;
import com.helloai.core.agent.mapper.AgentMapper;
import com.helloai.core.agent.service.AgentService;
import com.helloai.core.task.entity.SubTask;
import com.helloai.core.task.entity.Task;
import com.helloai.core.task.mapper.SubTaskMapper;
import com.helloai.core.task.mapper.TaskMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 管理后台 Dashboard 聚合统计服务。
 *
 * <p>为 {@code /api/admin/dashboard} 提供概览、高亮与趋势三类聚合查询；
 * 涉及 Task、SubTask、Agent、SubTask、AgentService、SysUserService 多个数据源，
 * 不绑定单一 Mapper，故不继承 {@code ServiceImpl}。</p>
 *
 * <p>由于 {@code helloai-core} 不依赖 {@code helloai-api}，本服务仅返回
 * Map/Entity 等基础结构，DTO 装配由 Controller 完成（符合 §6.7 “聚合看板可
 * 返回专用聚合 DTO 或 Map<String,Object>，但不直接暴露实体”）。</p>
 */
@Service
@RequiredArgsConstructor
public class AdminDashboardService {

    private static final int HIGHLIGHT_LIMIT = 10;

    private final TaskMapper taskMapper;
    private final SubTaskMapper subTaskMapper;
    private final AgentMapper agentMapper;
    private final AgentService agentService;
    private final SysUserService sysUserService;

    /**
     * 概览统计：任务、子任务、Agent、用户与今日完成计数。
     *
     * @return 概览字段 → 计数值（按 DTO 字段顺序组织）
     */
    public Map<String, Long> getOverview() {
        OffsetDateTime todayStart = OffsetDateTime.now().withHour(0).withMinute(0).withSecond(0).withNano(0);

        Map<String, Long> result = new LinkedHashMap<>();
        result.put("totalTasks", taskMapper.selectCount(
                new LambdaQueryWrapper<Task>().eq(Task::getDeleted, 0)));
        result.put("inProgressTasks", taskMapper.selectCount(
                new LambdaQueryWrapper<Task>().eq(Task::getStatus, TaskStatus.IN_PROGRESS).eq(Task::getDeleted, 0)));
        result.put("completedTasks", taskMapper.selectCount(
                new LambdaQueryWrapper<Task>().eq(Task::getStatus, TaskStatus.DONE).eq(Task::getDeleted, 0)));
        result.put("blockedTasks", subTaskMapper.selectCount(
                new LambdaQueryWrapper<SubTask>().eq(SubTask::getStatus, SubTaskStatus.BLOCKED).eq(SubTask::getDeleted, 0)));
        result.put("totalAgents", agentMapper.selectCount(
                new LambdaQueryWrapper<Agent>().eq(Agent::getDeleted, 0)));
        result.put("activeAgents", agentMapper.selectCount(
                new LambdaQueryWrapper<Agent>().eq(Agent::getStatus, AgentStatus.ACTIVE).eq(Agent::getDeleted, 0)));
        result.put("pendingReviews", subTaskMapper.selectCount(
                new LambdaQueryWrapper<SubTask>().eq(SubTask::getStatus, SubTaskStatus.REVIEW).eq(SubTask::getDeleted, 0)));
        result.put("todayCompleted", subTaskMapper.selectCount(
                new LambdaQueryWrapper<SubTask>()
                        .ge(SubTask::getCompleteTime, todayStart)
                        .eq(SubTask::getStatus, SubTaskStatus.DONE)
                        .eq(SubTask::getDeleted, 0)));
        result.put("todayCreated", taskMapper.selectCount(
                new LambdaQueryWrapper<Task>().ge(Task::getCreateTime, todayStart).eq(Task::getDeleted, 0)));
        result.put("totalUsers", sysUserService.count());
        return result;
    }

    /**
     * 阻塞子任务列表（最近 10 条，按 updateTime 倒序）。
     *
     * @return 列表中的每个 Map 含 subTaskId/subTaskTitle/priority/taskId/taskTitle
     */
    public List<Map<String, Object>> listBlockedHighlight() {
        List<SubTask> blockedSubTasks = subTaskMapper.selectList(
                new LambdaQueryWrapper<SubTask>()
                        .eq(SubTask::getStatus, SubTaskStatus.BLOCKED)
                        .eq(SubTask::getDeleted, 0)
                        .orderByDesc(SubTask::getUpdateTime)
                        .last("LIMIT " + HIGHLIGHT_LIMIT));
        if (blockedSubTasks == null || blockedSubTasks.isEmpty()) {
            return Collections.emptyList();
        }
        return blockedSubTasks.stream().map(st -> {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("subTaskId", st.getId());
            item.put("subTaskTitle", st.getTitle());
            item.put("priority", st.getPriority());
            Task task = taskMapper.selectById(st.getTaskId());
            if (task != null) {
                item.put("taskId", task.getId());
                item.put("taskTitle", task.getTitle());
            }
            return item;
        }).collect(Collectors.toList());
    }

    /**
     * 待审查子任务列表（最近 10 条，按 updateTime 倒序）。
     */
    public List<Map<String, Object>> listReviewHighlight() {
        List<SubTask> reviewSubTasks = subTaskMapper.selectList(
                new LambdaQueryWrapper<SubTask>()
                        .eq(SubTask::getStatus, SubTaskStatus.REVIEW)
                        .eq(SubTask::getDeleted, 0)
                        .orderByDesc(SubTask::getUpdateTime)
                        .last("LIMIT " + HIGHLIGHT_LIMIT));
        if (reviewSubTasks == null || reviewSubTasks.isEmpty()) {
            return Collections.emptyList();
        }
        return reviewSubTasks.stream().map(st -> {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("subTaskId", st.getId());
            item.put("subTaskTitle", st.getTitle());
            item.put("priority", st.getPriority());
            if (st.getAssignedAgentId() != null) {
                Agent agent = agentMapper.selectById(st.getAssignedAgentId());
                if (agent != null) {
                    item.put("assignedAgent", agent.getName());
                }
            }
            return item;
        }).collect(Collectors.toList());
    }

    /**
     * 低活跃 Agent 列表（按近 7 天子任务数升序，取前 10）。
     */
    public List<Map<String, Object>> listLowActivityAgents() {
        List<Agent> agents = agentService.listActive();
        if (agents == null || agents.isEmpty()) {
            return Collections.emptyList();
        }
        OffsetDateTime sevenDaysAgo = OffsetDateTime.now().minusDays(7);
        return agents.stream().map(agent -> {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("agentId", agent.getId());
            item.put("agentName", agent.getName());
            item.put("role", agent.getRole().name());
            long taskCount = subTaskMapper.selectCount(
                    new LambdaQueryWrapper<SubTask>()
                            .eq(SubTask::getAssignedAgentId, agent.getId())
                            .ge(SubTask::getCreateTime, sevenDaysAgo)
                            .eq(SubTask::getDeleted, 0));
            item.put("taskCount", (int) taskCount);
            item.put("idleMinutes", 0);
            return item;
        }).sorted((a, b) -> Integer.compare((Integer) a.get("taskCount"), (Integer) b.get("taskCount")))
                .limit(HIGHLIGHT_LIMIT)
                .collect(Collectors.toList());
    }

    /**
     * 趋势：近 N 天每日创建 / 完成 / 审查条数（{@code days} 不小于 1）。
     *
     * @param days 回溯天数
     * @return 包含 {@code dates}/{@code createdCounts}/{@code completedCounts}/{@code reviewedCounts} 四个并行数组
     */
    public Map<String, List<?>> getTrends(int days) {
        int safeDays = days > 0 ? days : 7;

        Map<String, List<?>> trend = new LinkedHashMap<>();
        List<String> dates = new ArrayList<>();
        List<Long> createdCounts = new ArrayList<>();
        List<Long> completedCounts = new ArrayList<>();
        List<Long> reviewedCounts = new ArrayList<>();

        DateTimeFormatter fmt = DateTimeFormatter.ofPattern("MM-dd");
        for (int i = safeDays - 1; i >= 0; i--) {
            OffsetDateTime dayStart = OffsetDateTime.now().minusDays(i).withHour(0).withMinute(0).withSecond(0).withNano(0);
            OffsetDateTime dayEnd = dayStart.plusDays(1);

            dates.add(dayStart.format(fmt));
            createdCounts.add(taskMapper.selectCount(
                    new LambdaQueryWrapper<Task>()
                            .ge(Task::getCreateTime, dayStart)
                            .lt(Task::getCreateTime, dayEnd)
                            .eq(Task::getDeleted, 0)));
            completedCounts.add(subTaskMapper.selectCount(
                    new LambdaQueryWrapper<SubTask>()
                            .ge(SubTask::getCompleteTime, dayStart)
                            .lt(SubTask::getCompleteTime, dayEnd)
                            .eq(SubTask::getStatus, SubTaskStatus.DONE)
                            .eq(SubTask::getDeleted, 0)));
            reviewedCounts.add(subTaskMapper.selectCount(
                    new LambdaQueryWrapper<SubTask>()
                            .ge(SubTask::getUpdateTime, dayStart)
                            .lt(SubTask::getUpdateTime, dayEnd)
                            .eq(SubTask::getStatus, SubTaskStatus.DONE)
                            .eq(SubTask::getDeleted, 0)));
        }

        trend.put("dates", dates);
        trend.put("createdCounts", createdCounts);
        trend.put("completedCounts", completedCounts);
        trend.put("reviewedCounts", reviewedCounts);
        return trend;
    }
}