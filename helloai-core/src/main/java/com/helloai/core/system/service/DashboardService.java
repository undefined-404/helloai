package com.helloai.core.system.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.helloai.common.constant.SubTaskStatus;
import com.helloai.core.agent.entity.Agent;
import com.helloai.core.agent.mapper.AgentMapper;
import com.helloai.core.task.entity.SubTask;
import com.helloai.core.task.mapper.SubTaskMapper;
import com.helloai.core.task.mapper.TaskMapper;
import com.helloai.core.task.entity.Task;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Dashboard 统计聚合服务（前端主页面 {@code /api/dashboard/stats} 数据源）。
 *
 * <p>聚合任务计数、子任务状态分布、Agent 积分排行与近 7 天吞吐量；
 * 与 {@link AdminDashboardService} 类似不绑定单一 Mapper。</p>
 *
 * <p>返回 {@code Map<String, Object>} 而非具体 DTO，因为 {@code helloai-core}
 * 不依赖 {@code helloai-api}，DTO 装配由 Controller 完成（§6.7 聚合看板语义）。</p>
 */
@Service
@RequiredArgsConstructor
public class DashboardService {

    private static final int AGENT_RANKING_LIMIT = 10;
    private static final int THROUGHPUT_DAYS = 7;

    private final TaskMapper taskMapper;
    private final SubTaskMapper subTaskMapper;
    private final AgentMapper agentMapper;

    /**
     * Dashboard 主统计。
     *
     * @return 包含 totalTasks / activeSubTasks / pendingReviews / blockedTasks /
     *         agentRanking / throughput 六个字段的 Map
     */
    public Map<String, Object> getStats() {
        OffsetDateTime todayStart = OffsetDateTime.now().withHour(0).withMinute(0).withSecond(0).withNano(0);

        long totalTasks = taskMapper.selectCount(
                new LambdaQueryWrapper<Task>().eq(Task::getDeleted, 0));
        long activeSubTasks = subTaskMapper.selectCount(
                new LambdaQueryWrapper<SubTask>()
                        .in(SubTask::getStatus, SubTaskStatus.ASSIGNED, SubTaskStatus.IN_PROGRESS)
                        .eq(SubTask::getDeleted, 0));
        long pendingReviews = subTaskMapper.selectCount(
                new LambdaQueryWrapper<SubTask>().eq(SubTask::getStatus, SubTaskStatus.REVIEW).eq(SubTask::getDeleted, 0));
        long blockedTasks = subTaskMapper.selectCount(
                new LambdaQueryWrapper<SubTask>().eq(SubTask::getStatus, SubTaskStatus.BLOCKED).eq(SubTask::getDeleted, 0));

        // Agent 积分排行（按 score 倒序取前 10）
        List<Agent> topAgents = agentMapper.selectList(
                new LambdaQueryWrapper<Agent>()
                        .eq(Agent::getDeleted, 0)
                        .orderByDesc(Agent::getScore)
                        .last("LIMIT " + AGENT_RANKING_LIMIT));
        List<Map<String, Object>> agentRanking = topAgents == null ? new ArrayList<>()
                : topAgents.stream().map(a -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("name", a.getName());
            m.put("role", a.getRole().name());
            m.put("score", a.getScore() != null ? a.getScore() : 0);
            return m;
        }).collect(Collectors.toList());

        // 近 7 天每日吞吐量
        List<Map<String, Object>> throughput = new ArrayList<>();
        DateTimeFormatter fmt = DateTimeFormatter.ofPattern("MM-dd");
        for (int i = THROUGHPUT_DAYS - 1; i >= 0; i--) {
            OffsetDateTime dayStart = OffsetDateTime.now().minusDays(i).withHour(0).withMinute(0).withSecond(0).withNano(0);
            OffsetDateTime dayEnd = dayStart.plusDays(1);
            long count = subTaskMapper.selectCount(
                    new LambdaQueryWrapper<SubTask>()
                            .ge(SubTask::getCompleteTime, dayStart)
                            .lt(SubTask::getCompleteTime, dayEnd)
                            .eq(SubTask::getStatus, SubTaskStatus.DONE)
                            .eq(SubTask::getDeleted, 0));
            Map<String, Object> bucket = new LinkedHashMap<>();
            bucket.put("date", dayStart.format(fmt));
            bucket.put("count", count);
            throughput.add(bucket);
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("totalTasks", totalTasks);
        result.put("activeSubTasks", activeSubTasks);
        result.put("pendingReviews", pendingReviews);
        result.put("blockedTasks", blockedTasks);
        result.put("agentRanking", agentRanking);
        result.put("throughput", throughput);
        result.put("todayStart", todayStart);
        return result;
    }
}