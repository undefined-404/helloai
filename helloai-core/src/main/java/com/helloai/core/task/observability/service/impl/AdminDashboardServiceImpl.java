package com.helloai.core.task.observability.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.helloai.common.constant.SubTaskStatus;
import com.helloai.common.constant.TaskStatus;
import com.helloai.core.agent.entity.Agent;
import com.helloai.core.agent.service.AgentService;
import com.helloai.core.system.service.SysUserService;
import com.helloai.core.task.entity.SubTask;
import com.helloai.core.task.entity.Task;
import com.helloai.core.task.mapper.SubTaskMapper;
import com.helloai.core.task.mapper.TaskMapper;
import com.helloai.core.task.observability.service.AdminDashboardService;
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
 * 管理后台 Dashboard 聚合统计服务实现。
 */
@Service
@RequiredArgsConstructor
public class AdminDashboardServiceImpl implements AdminDashboardService {

    private static final int HIGHLIGHT_LIMIT = 10;

    private final TaskMapper taskMapper;
    private final SubTaskMapper subTaskMapper;
    private final AgentService agentService;
    private final SysUserService sysUserService;

    /**
     * 概览统计：任务、子任务、Agent、用户与今日完成计数。
     *
     * @return 概览字段 → 计数值（按 DTO 字段顺序组织）
     */
    @Override
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
        // 跨域 Agent 计数走 AgentService 接口，不直捅 agent.mapper
        result.put("totalAgents", agentService.countAll());
        result.put("activeAgents", (long) agentService.listActive().size());
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
    @Override
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
    @Override
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
                Agent agent = agentService.getById(st.getAssignedAgentId());
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
    @Override
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
    @Override
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