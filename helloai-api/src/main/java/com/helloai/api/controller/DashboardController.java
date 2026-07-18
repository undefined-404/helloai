package com.helloai.api.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.helloai.common.base.R;
import com.helloai.common.constant.SubTaskStatus;
import com.helloai.common.constant.TaskStatus;
import com.helloai.core.agent.entity.Agent;
import com.helloai.core.task.entity.SubTask;
import com.helloai.core.task.entity.Task;
import com.helloai.core.agent.mapper.AgentMapper;
import com.helloai.core.task.mapper.SubTaskMapper;
import com.helloai.core.task.mapper.TaskMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Slf4j
@RestController
@RequestMapping("/api/dashboard")
@RequiredArgsConstructor
public class DashboardController {

    private final TaskMapper taskMapper;
    private final SubTaskMapper subTaskMapper;
    private final AgentMapper agentMapper;

    /**
     * Dashboard 统计（与前端 DashboardStats 类型对齐）
     */
    @GetMapping("/stats")
    public R<Map<String, Object>> stats() {
        OffsetDateTime todayStart = OffsetDateTime.now().withHour(0).withMinute(0).withSecond(0).withNano(0);
        OffsetDateTime sevenDaysAgo = OffsetDateTime.now().minusDays(7);

        long totalTasks = taskMapper.selectCount(new LambdaQueryWrapper<Task>().eq(Task::getDeleted, 0));
        long activeSubTasks = subTaskMapper.selectCount(
                new LambdaQueryWrapper<SubTask>()
                        .in(SubTask::getStatus, SubTaskStatus.ASSIGNED, SubTaskStatus.IN_PROGRESS)
                        .eq(SubTask::getDeleted, 0));
        long pendingReviews = subTaskMapper.selectCount(
                new LambdaQueryWrapper<SubTask>().eq(SubTask::getStatus, SubTaskStatus.REVIEW).eq(SubTask::getDeleted, 0));
        long blockedTasks = subTaskMapper.selectCount(
                new LambdaQueryWrapper<SubTask>().eq(SubTask::getStatus, SubTaskStatus.BLOCKED).eq(SubTask::getDeleted, 0));

        // Agent 积分排行
        List<Agent> topAgents = agentMapper.selectList(
                new LambdaQueryWrapper<Agent>()
                        .eq(Agent::getDeleted, 0)
                        .orderByDesc(Agent::getScore)
                        .last("LIMIT 10"));
        List<Map<String, Object>> agentRanking = topAgents.stream().map(a ->
                Map.<String, Object>of("name", a.getName(), "role", a.getRole().name(), "score", a.getScore() != null ? a.getScore() : 0)
        ).collect(Collectors.toList());

        // 近7天任务吞吐量
        List<Map<String, Object>> throughput = new ArrayList<>();
        DateTimeFormatter fmt = DateTimeFormatter.ofPattern("MM-dd");
        for (int i = 6; i >= 0; i--) {
            OffsetDateTime dayStart = OffsetDateTime.now().minusDays(i).withHour(0).withMinute(0).withSecond(0).withNano(0);
            OffsetDateTime dayEnd = dayStart.plusDays(1);
            long count = subTaskMapper.selectCount(
                    new LambdaQueryWrapper<SubTask>()
                            .ge(SubTask::getCompletedAt, dayStart)
                            .lt(SubTask::getCompletedAt, dayEnd)
                            .eq(SubTask::getStatus, SubTaskStatus.DONE)
                            .eq(SubTask::getDeleted, 0));
            throughput.add(Map.of("date", dayStart.format(fmt), "count", count));
        }

        return R.ok(Map.of(
                "totalTasks", totalTasks,
                "activeSubTasks", activeSubTasks,
                "pendingReviews", pendingReviews,
                "blockedTasks", blockedTasks,
                "agentRanking", agentRanking,
                "throughput", throughput
        ));
    }
}
