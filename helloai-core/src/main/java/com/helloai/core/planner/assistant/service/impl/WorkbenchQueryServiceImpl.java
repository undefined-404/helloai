package com.helloai.core.planner.assistant.service.impl;

import com.helloai.common.constant.AgentAccessType;
import com.helloai.common.constant.AgentRole;
import com.helloai.core.agent.port.AgentProfileSnapshot;
import com.helloai.core.agent.service.AgentService;
import com.helloai.core.planner.assistant.service.AssistantProfileService;
import com.helloai.core.planner.assistant.service.AssistantProfileService.AssistantSnapshot;
import com.helloai.core.planner.assistant.service.MyArtifactsQueryService;
import com.helloai.core.planner.assistant.service.WorkbenchQueryService;
import com.helloai.core.shared.util.CurrentUser;
import com.helloai.core.task.entity.Task;
import com.helloai.core.task.service.SubTaskQueryService;
import com.helloai.core.task.service.SubTaskQueryService.ActiveAgentLoad;
import com.helloai.core.task.service.TaskService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * {@link WorkbenchQueryService} 实现（PA-4；2026-11-02 晚改版为三段式）。
 *
 * <p>各块复用既有读取面，不新增跨域直连 Mapper：
 * 任务概况与活跃 Agent 走 task 域 {@link SubTaskQueryService}；最近任务走
 * {@link TaskService#listActiveTasksForOwner}（owner 宽松并入）；最近产物走
 * {@link MyArtifactsQueryService#conversationArtifacts}（会话单源）。
 * Agent 名册取自 {@link AgentService}（ACTIVE 的执行 / 复核角色），
 * 接入类型由同源回填（内 = API_KEY_LLM，外 = CLI_CLIENT / WEB_BROWSER），
 * 每人的运行状态由 task 域负载（owner 宽松口径）推导。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WorkbenchQueryServiceImpl implements WorkbenchQueryService {

    private final AssistantProfileService assistantProfileService;
    private final TaskService taskService;
    private final SubTaskQueryService subTaskQueryService;
    private final AgentService agentService;
    private final MyArtifactsQueryService myArtifactsQueryService;

    @Override
    public Workbench aggregate() {
        Long userId = CurrentUser.userId();
        AssistantSnapshot profile = assistantProfileService.getActiveByUser(userId);
        if (userId == null) {
            log.debug("workbench: 无登录用户，仅返回默认身份");
            return new Workbench(profile, TaskOverview.EMPTY, List.of(), List.of(), List.of());
        }
        return new Workbench(
                profile,
                taskOverview(userId),
                activeAgents(userId),
                recentTasks(),
                myArtifactsQueryService.conversationArtifacts(RECENT_ARTIFACT_LIMIT));
    }

    private TaskOverview taskOverview(Long userId) {
        SubTaskQueryService.TaskOverview overview = subTaskQueryService.overviewForOwner(userId);
        return new TaskOverview(
                overview.inProgressTasks(),
                overview.pendingDispatch(),
                overview.pendingReview(),
                overview.pendingManual());
    }

    /**
     * 活跃 Agent：<b>名册 + 每人实时状态</b>，而不是只列「此刻正在跑任务」的那几个人。
     *
     * <p>名册取平台 ACTIVE 的执行 / 复核角色 Agent（{@code EXECUTOR} / {@code REVIEWER}），
     * 负载按 owner 宽松口径取自 task 域：名下 IN_PROGRESS 子任务数 = 执行中，
     * 名下处于 REVIEW 的子任务数 = 审核中（产出在复核流程里），其余为待命。
     * 这样右栏在「没有正在跑的任务」时也仍然展示可用 Agent 名单，而不是整块消失。</p>
     */
    private List<ActiveAgent> activeAgents(Long userId) {
        List<AgentProfileSnapshot> roster = agentService.listActiveProfiles().stream()
                .filter(a -> a.role() == AgentRole.EXECUTOR || a.role() == AgentRole.REVIEWER)
                .toList();
        if (roster.isEmpty()) {
            return List.of();
        }
        Map<Long, ActiveAgentLoad> loadByAgent = subTaskQueryService
                .activeAgentLoadsForOwner(userId, ACTIVE_AGENT_WORKLOAD_LIMIT).stream()
                .collect(Collectors.toMap(ActiveAgentLoad::agentId, Function.identity(), (a, b) -> a));
        return roster.stream()
                .map(agent -> {
                    ActiveAgentLoad load = loadByAgent.get(agent.id());
                    long running = load == null ? 0L : load.runningCount();
                    long reviewing = load == null ? 0L : load.reviewCount();
                    String status = running > 0 ? STATUS_RUNNING
                            : reviewing > 0 ? STATUS_REVIEWING : STATUS_STANDBY;
                    boolean internal = agent.accessType() == AgentAccessType.API_KEY_LLM;
                    String role = agent.role() == null ? null : agent.role().name();
                    return new ActiveAgent(agent.id(), agent.name(), internal, role, status, running);
                })
                .sorted(Comparator.comparingInt((ActiveAgent a) -> statusRank(a.status()))
                        .thenComparing(Comparator.comparingLong(ActiveAgent::runningCount).reversed())
                        .thenComparing(ActiveAgent::name, Comparator.nullsLast(Comparator.naturalOrder())))
                .limit(ACTIVE_AGENT_LIMIT)
                .toList();
    }

    /** 状态排序权重：运行中 → 审核中 → 待命。 */
    private static int statusRank(String status) {
        if (STATUS_RUNNING.equals(status)) {
            return 0;
        }
        return STATUS_REVIEWING.equals(status) ? 1 : 2;
    }

    /** 最近任务：只展示仍在推进（PLANNING / IN_PROGRESS）的任务。 */
    private List<RecentTask> recentTasks() {
        return taskService.listActiveTasksForOwner(RECENT_TASK_LIMIT).stream()
                .map(this::toRecentTask)
                .toList();
    }

    private RecentTask toRecentTask(Task task) {
        return new RecentTask(
                task.getId(),
                task.getTitle(),
                task.getStatus() == null ? null : task.getStatus().name(),
                task.getCreateTime());
    }
}
