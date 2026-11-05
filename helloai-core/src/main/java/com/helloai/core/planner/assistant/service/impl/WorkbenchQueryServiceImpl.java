package com.helloai.core.planner.assistant.service.impl;

import com.helloai.common.constant.AgentAccessType;
import com.helloai.common.constant.AgentOnlineStatus;
import com.helloai.common.constant.AgentRole;
import com.helloai.core.agent.port.AgentProfileSnapshot;
import com.helloai.core.agent.service.AgentService;
import com.helloai.core.agent.service.LlmProviderCatalogService;
import com.helloai.core.planner.assistant.AssistantWorkBriefFormatter;
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

import java.util.ArrayList;
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
    /** 模型候选集提供方（agent 域；`planner → agent` 顺向）。 */
    private final LlmProviderCatalogService llmProviderCatalogService;
    /** `PA-15`：工作概览开关与预算。 */
    private final com.helloai.common.config.AssistantChatProperties assistantChatProperties;

    @Override
    public Workbench aggregate() {
        Long userId = CurrentUser.userId();
        AssistantSnapshot profile = assistantProfileService.getActiveByUser(userId);
        if (userId == null) {
            log.debug("workbench: 无登录用户，仅返回默认身份");
            return new Workbench(profile, TaskOverview.EMPTY, List.of(), 0, List.of(), List.of());
        }
        AgentRoster roster = agentRoster(userId);
        return new Workbench(
                profile,
                taskOverview(userId),
                roster.visible(),
                roster.hiddenOffline(),
                recentTasks(),
                myArtifactsQueryService.conversationArtifacts(RECENT_ARTIFACT_LIMIT));
    }

    @Override
    public List<LlmProviderCatalogService.ModelOption> modelOptions() {
        // 候选集与有效性判定都在 agent 域 Catalog 内（供应商可用性 × 模型启用位），
        // 本域只做转发，不重算——两处各判一次必然漂移。
        return llmProviderCatalogService.listModelOptions();
    }

    @Override
    public String recentWorkBrief(Long userId) {
        if (userId == null || !assistantChatProperties.isWorkBriefEnabled()) {
            return "";
        }
        List<Task> tasks = taskService.listActiveTasksByOwner(userId,
                assistantChatProperties.getWorkBriefMaxTasks());
        if (tasks.isEmpty()) {
            return "";
        }
        List<AssistantWorkBriefFormatter.TaskBrief> briefs = tasks.stream()
                .map(task -> new AssistantWorkBriefFormatter.TaskBrief(
                        task.getTitle(),
                        task.getStatus() == null ? null : task.getStatus().name(),
                        subTaskQueryService.listViewsByTaskId(task.getId()).stream()
                                .limit(Math.max(1, assistantChatProperties.getWorkBriefMaxSubTasks()))
                                .map(view -> new AssistantWorkBriefFormatter.SubTaskBrief(
                                        view.title(),
                                        view.status() == null ? null : view.status().name(),
                                        view.acceptance()))
                                .toList()))
                .toList();
        return AssistantWorkBriefFormatter.render(briefs,
                assistantChatProperties.getWorkBriefMaxChars());
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
     * 活跃 Agent：<b>带角色的名册 + 每人实时状态</b>，而不是只列「此刻正在跑任务」的那几个人。
     *
     * <p>名册取平台 ACTIVE 的执行 / 复核角色 Agent（{@code EXECUTOR} / {@code REVIEWER}），
     * 负载按 owner 宽松口径取自 task 域：名下 IN_PROGRESS 子任务数 = 执行中，
     * 名下处于 REVIEW 的子任务数 = 审核中（产出在复核流程里），其余为待命。
     * 这样右栏在「没有正在跑的任务」时也仍然展示可用 Agent 名单，而不是整块消失。</p>
     *
     * <p><b>可见性规则（2026-11-05 设计稿裁定）</b>——两类 Agent 的「在线」语义不同：</p>
     * <ul>
     *   <li><b>内部 LLM</b>（{@code API_KEY_LLM}）：平台自持凭证，<b>常驻在线</b>，
     *       一律可见，空闲即「待命中」。不看心跳——它没有外部进程需要报活。</li>
     *   <li><b>外部 Agent</b>（{@code CLI_CLIENT} / {@code WEB_BROWSER}）：必须
     *       <b>心跳在线</b>（{@code ONLINE} / {@code IDLE}）才进列表；{@code OFFLINE} /
     *       {@code SLEEPING} / 未知一律不进候选，只汇总条数给前端提示「已隐藏 N 个」。</li>
     * </ul>
     *
     * <p>理由：外部 Agent 离线时派不进去，列在「活跃」里等于给用户一个按不动的按钮。
     * 隐藏但不静默——汇总条数保留可解释性（判据「不静默失败」）。</p>
     */
    private AgentRoster agentRoster(Long userId) {
        List<AgentProfileSnapshot> roster = agentService.listActiveProfiles().stream()
                .filter(a -> a.role() == AgentRole.EXECUTOR || a.role() == AgentRole.REVIEWER)
                .toList();
        if (roster.isEmpty()) {
            return new AgentRoster(List.of(), 0);
        }
        List<AgentProfileSnapshot> visibleRoster = new ArrayList<>(roster.size());
        int hiddenOffline = 0;
        for (AgentProfileSnapshot agent : roster) {
            if (isVisible(agent)) {
                visibleRoster.add(agent);
            } else {
                hiddenOffline++;
            }
        }
        if (visibleRoster.isEmpty()) {
            return new AgentRoster(List.of(), hiddenOffline);
        }
        Map<Long, ActiveAgentLoad> loadByAgent = subTaskQueryService
                .activeAgentLoadsForOwner(userId, ACTIVE_AGENT_WORKLOAD_LIMIT).stream()
                .collect(Collectors.toMap(ActiveAgentLoad::agentId, Function.identity(), (a, b) -> a));
        List<ActiveAgent> visible = visibleRoster.stream()
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
        return new AgentRoster(visible, hiddenOffline);
    }

    /** 是否进「活跃 Agent」列表：内部 LLM 恒可见；外部 Agent 须心跳在线。 */
    private static boolean isVisible(AgentProfileSnapshot agent) {
        if (agent.accessType() == AgentAccessType.API_KEY_LLM) {
            return true;
        }
        AgentOnlineStatus online = agent.onlineStatus();
        return online == AgentOnlineStatus.ONLINE || online == AgentOnlineStatus.IDLE;
    }

    /**
     * 名册筛选结果：可见条目 + 被隐藏的外部 Agent 条数。
     *
     * <p>隐藏条数单独成字段（而不是让前端「用总数减可见数」）：名册里还混着内部 LLM，
     * 前端做减法会把内部 LLM 算成「被隐藏的外部 Agent」，必错。</p>
     */
    private record AgentRoster(List<ActiveAgent> visible, int hiddenOffline) {
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
