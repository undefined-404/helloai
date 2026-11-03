package com.helloai.api.dto.requirement;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import com.helloai.core.planner.assistant.service.WorkbenchQueryService.ActiveAgent;
import com.helloai.core.planner.assistant.service.WorkbenchQueryService.RecentTask;
import com.helloai.core.planner.assistant.service.WorkbenchQueryService.TaskOverview;
import com.helloai.core.planner.assistant.service.WorkbenchQueryService.Workbench;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * 个人工作台聚合响应（PA-4，2026-11-02 晚改版为三段式）。
 *
 * <p>任务概况（计数 + 活跃 Agent）/ 最近任务（进行中）/ 最近产物（会话单源）一次下发。
 * 产物条目复用 {@link ArtifactItemVO}，下载/预览仍走既有 {@code /chat-attachments/*} 端点。</p>
 */
public record WorkbenchVO(AssistantProfileVO profile,
                          TaskOverviewVO taskOverview,
                          List<ActiveAgentVO> activeAgents,
                          List<RecentTaskVO> recentTasks,
                          List<ArtifactItemVO> artifacts) {

    /** 任务概况第一屏：执行中任务 + 子任务三态计数。 */
    public record TaskOverviewVO(long inProgressTasks,
                                 long pendingDispatch,
                                 long pendingReview,
                                 long pendingManual) {
    }

    /** 活跃 Agent 条目（名册 + 实时状态；内 = API_KEY_LLM，外 = CLI_CLIENT / WEB_BROWSER）。 */
    public record ActiveAgentVO(@JsonSerialize(using = ToStringSerializer.class) Long agentId,
                                String name,
                                boolean internal,
                                String role,
                                String status,
                                long runningCount) {
    }

    /** 最近任务条目（右栏点击跳任务列表用）。 */
    public record RecentTaskVO(@JsonSerialize(using = ToStringSerializer.class) Long id,
                               String title,
                               String status,
                               OffsetDateTime createTime) {
    }

    public static WorkbenchVO from(Workbench workbench) {
        return new WorkbenchVO(
                AssistantProfileVO.from(workbench.profile()),
                toOverview(workbench.taskOverview()),
                workbench.activeAgents() == null ? List.of()
                        : workbench.activeAgents().stream().map(WorkbenchVO::toAgent).toList(),
                workbench.recentTasks() == null ? List.of()
                        : workbench.recentTasks().stream().map(WorkbenchVO::toRecentTask).toList(),
                ArtifactItemVO.fromList(workbench.artifacts()));
    }

    private static TaskOverviewVO toOverview(TaskOverview overview) {
        if (overview == null) {
            return new TaskOverviewVO(0, 0, 0, 0);
        }
        return new TaskOverviewVO(overview.inProgressTasks(), overview.pendingDispatch(),
                overview.pendingReview(), overview.pendingManual());
    }

    private static ActiveAgentVO toAgent(ActiveAgent agent) {
        return new ActiveAgentVO(agent.agentId(), agent.name(), agent.internal(),
                agent.role(), agent.status(), agent.runningCount());
    }

    private static RecentTaskVO toRecentTask(RecentTask task) {
        return new RecentTaskVO(task.id(), task.title(), task.status(), task.createTime());
    }
}
