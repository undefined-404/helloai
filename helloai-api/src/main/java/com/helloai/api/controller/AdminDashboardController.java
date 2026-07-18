package com.helloai.api.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.helloai.api.dto.PageResult;
import com.helloai.api.dto.admin.DashboardHighlights;
import com.helloai.api.dto.admin.DashboardOverview;
import com.helloai.api.dto.admin.DashboardTrend;
import com.helloai.api.dto.admin.FeedResponse;
import com.helloai.common.base.R;
import com.helloai.common.constant.AgentStatus;
import com.helloai.common.constant.SubTaskStatus;
import com.helloai.common.constant.TaskStatus;
import com.helloai.core.task.entity.ActivityLog;
import com.helloai.core.agent.entity.Agent;
import com.helloai.core.task.entity.SubTask;
import com.helloai.core.task.entity.Task;
import com.helloai.core.task.mapper.ActivityLogMapper;
import com.helloai.core.agent.mapper.AgentMapper;
import com.helloai.core.task.mapper.SubTaskMapper;
import com.helloai.core.task.mapper.TaskMapper;
import com.helloai.core.agent.service.AgentService;
import com.helloai.core.system.service.SysUserService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

@Slf4j
@RestController
@RequestMapping("/api/admin/dashboard")
@RequiredArgsConstructor
public class AdminDashboardController {

    private final TaskMapper taskMapper;
    private final SubTaskMapper subTaskMapper;
    private final AgentMapper agentMapper;
    private final ActivityLogMapper activityLogMapper;
    private final AgentService agentService;
    private final SysUserService sysUserService;

    /**
     * 概览统计
     */
    @GetMapping("/overview")
    public R<DashboardOverview> overview() {
        OffsetDateTime todayStart = OffsetDateTime.now().withHour(0).withMinute(0).withSecond(0).withNano(0);

        DashboardOverview overview = new DashboardOverview();
        overview.setTotalTasks(taskMapper.selectCount(new LambdaQueryWrapper<Task>().eq(Task::getDeleted, 0)));
        overview.setInProgressTasks(taskMapper.selectCount(
                new LambdaQueryWrapper<Task>().eq(Task::getStatus, TaskStatus.IN_PROGRESS).eq(Task::getDeleted, 0)));
        overview.setCompletedTasks(taskMapper.selectCount(
                new LambdaQueryWrapper<Task>().eq(Task::getStatus, TaskStatus.DONE).eq(Task::getDeleted, 0)));
        overview.setBlockedTasks(subTaskMapper.selectCount(
                new LambdaQueryWrapper<SubTask>().eq(SubTask::getStatus, SubTaskStatus.BLOCKED).eq(SubTask::getDeleted, 0)));
        overview.setTotalAgents(agentMapper.selectCount(new LambdaQueryWrapper<Agent>().eq(Agent::getDeleted, 0)));
        overview.setActiveAgents(agentMapper.selectCount(
                new LambdaQueryWrapper<Agent>().eq(Agent::getStatus, AgentStatus.ACTIVE).eq(Agent::getDeleted, 0)));
        overview.setPendingReviews(subTaskMapper.selectCount(
                new LambdaQueryWrapper<SubTask>().eq(SubTask::getStatus, SubTaskStatus.REVIEW).eq(SubTask::getDeleted, 0)));
        overview.setTodayCompleted(subTaskMapper.selectCount(
                new LambdaQueryWrapper<SubTask>()
                        .ge(SubTask::getCompleteTime, todayStart)
                        .eq(SubTask::getStatus, SubTaskStatus.DONE)
                        .eq(SubTask::getDeleted, 0)));
        overview.setTodayCreated(taskMapper.selectCount(
                new LambdaQueryWrapper<Task>().ge(Task::getCreateTime, todayStart).eq(Task::getDeleted, 0)));
        overview.setTotalUsers(sysUserService.count());

        return R.ok(overview);
    }

    /**
     * 高亮信息（阻塞/待审查/低活跃）
     */
    @GetMapping("/highlights")
    public R<DashboardHighlights> highlights() {
        DashboardHighlights result = new DashboardHighlights();

        // 阻塞子任务
        var blockedSubTasks = subTaskMapper.selectList(
                new LambdaQueryWrapper<SubTask>()
                        .eq(SubTask::getStatus, SubTaskStatus.BLOCKED)
                        .eq(SubTask::getDeleted, 0)
                        .orderByDesc(SubTask::getUpdateTime)
                        .last("LIMIT 10"));

        result.setBlockedTasks(blockedSubTasks.stream().map(st -> {
            var item = new DashboardHighlights.BlockedTaskItem();
            item.setSubTaskId(st.getId());
            item.setSubTaskTitle(st.getTitle());
            item.setPriority(st.getPriority());
            Task task = taskMapper.selectById(st.getTaskId());
            if (task != null) {
                item.setTaskId(task.getId());
                item.setTaskTitle(task.getTitle());
            }
            return item;
        }).collect(Collectors.toList()));
        result.setTotalBlocked(blockedSubTasks.size());

        // 待审查子任务
        var reviewSubTasks = subTaskMapper.selectList(
                new LambdaQueryWrapper<SubTask>()
                        .eq(SubTask::getStatus, SubTaskStatus.REVIEW)
                        .eq(SubTask::getDeleted, 0)
                        .orderByDesc(SubTask::getUpdateTime)
                        .last("LIMIT 10"));

        result.setPendingReviews(reviewSubTasks.stream().map(st -> {
            var item = new DashboardHighlights.PendingReviewItem();
            item.setSubTaskId(st.getId());
            item.setSubTaskTitle(st.getTitle());
            item.setPriority(st.getPriority());
            if (st.getAssignedAgentId() != null) {
                Agent agent = agentMapper.selectById(st.getAssignedAgentId());
                if (agent != null) {
                    item.setAssignedAgent(agent.getName());
                }
            }
            return item;
        }).collect(Collectors.toList()));
        result.setTotalPendingReview(reviewSubTasks.size());

        // 低活跃 Agent（按子任务数排序取最后10个）
        var agents = agentService.listActive();
        OffsetDateTime sevenDaysAgo = OffsetDateTime.now().minusDays(7);
        result.setLowActivityAgents(agents.stream().map(agent -> {
            var item = new DashboardHighlights.LowActivityAgent();
            item.setAgentId(agent.getId());
            item.setAgentName(agent.getName());
            item.setRole(agent.getRole().name());
            long taskCount = subTaskMapper.selectCount(
                    new LambdaQueryWrapper<SubTask>()
                            .eq(SubTask::getAssignedAgentId, agent.getId())
                            .ge(SubTask::getCreateTime, sevenDaysAgo)
                            .eq(SubTask::getDeleted, 0));
            item.setTaskCount((int) taskCount);
            item.setIdleMinutes(0);
            return item;
        }).sorted((a, b) -> Integer.compare(a.getTaskCount(), b.getTaskCount()))
                .limit(10)
                .collect(Collectors.toList()));
        result.setTotalLowActivity(result.getLowActivityAgents().size());

        return R.ok(result);
    }

    /**
     * 趋势数据（近 N 天）
     */
    @GetMapping("/trends")
    public R<DashboardTrend> trends(@RequestParam(value = "days", defaultValue = "7") int days) {
        OffsetDateTime start = OffsetDateTime.now().minusDays(days);

        DashboardTrend trend = new DashboardTrend();
        List<String> dates = new ArrayList<>();
        List<Long> createdCounts = new ArrayList<>();
        List<Long> completedCounts = new ArrayList<>();
        List<Long> reviewedCounts = new ArrayList<>();

        DateTimeFormatter fmt = DateTimeFormatter.ofPattern("MM-dd");
        for (int i = days - 1; i >= 0; i--) {
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

        trend.setDates(dates);
        trend.setCreatedCounts(createdCounts);
        trend.setCompletedCounts(completedCounts);
        trend.setReviewedCounts(reviewedCounts);

        return R.ok(trend);
    }
}
