package com.helloai.core.planner.assistant.service.impl;

import com.helloai.common.constant.AgentAccessType;
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

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * {@link WorkbenchQueryService} 实现（PA-4；2026-11-02 晚改版为三段式）。
 *
 * <p>各块复用既有读取面，不新增跨域直连 Mapper：
 * 任务概况与活跃 Agent 走 task 域 {@link SubTaskQueryService}；最近任务走
 * {@link TaskService#listActiveTasksForOwner}（owner 宽松并入）；最近产物走
 * {@link MyArtifactsQueryService#conversationArtifacts}（会话单源）。
 * Agent 名称与接入类型由 {@link AgentService} 回填（内 = API_KEY_LLM，外 = CLI_CLIENT / WEB_BROWSER）。</p>
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

    /** 正在执行任务的活跃 Agent：task 域只出 id + 并发数，名称/内外由本层回填。 */
    private List<ActiveAgent> activeAgents(Long userId) {
        List<ActiveAgentLoad> loads = subTaskQueryService.activeAgentLoadsForOwner(userId, ACTIVE_AGENT_LIMIT);
        if (loads.isEmpty()) {
            return List.of();
        }
        List<Long> agentIds = loads.stream().map(ActiveAgentLoad::agentId).toList();
        Map<Long, AgentProfileSnapshot> agentById = agentService.listProfilesByIds(agentIds).stream()
                .collect(Collectors.toMap(AgentProfileSnapshot::id, Function.identity(), (a, b) -> a));
        return loads.stream()
                .map(load -> {
                    AgentProfileSnapshot agent = agentById.get(load.agentId());
                    if (agent == null) {
                        return null;
                    }
                    boolean internal = agent.accessType() == AgentAccessType.API_KEY_LLM;
                    return new ActiveAgent(agent.id(), agent.name(), internal, load.runningCount());
                })
                .filter(Objects::nonNull)
                .toList();
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
