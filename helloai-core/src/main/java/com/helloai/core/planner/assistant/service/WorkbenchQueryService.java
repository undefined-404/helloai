package com.helloai.core.planner.assistant.service;

import com.helloai.core.planner.assistant.service.AssistantProfileService.AssistantSnapshot;
import com.helloai.core.planner.assistant.service.MyArtifactsQueryService.ArtifactItem;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * 个人工作台聚合读取面（PA-4，2026-11-02；2026-11-02 晚改版为三段式）。
 *
 * <p>一次返回右栏三块所需的全部数据，避免前端开面板时连打多个接口：
 * <ul>
 *   <li><b>任务概况</b>：执行中任务数 + 子任务「待分配 / 待审查 / 待人工」三态计数，
 *       以及 Agent 名册及每人的实时状态（运行中 / 审核中 / 待命）；前者由 task 域按 owner 宽松口径聚合；</li>
 *   <li><b>最近任务</b>：当前用户仍在推进（PLANNING / IN_PROGRESS）的任务；</li>
 *   <li><b>最近产物</b>：最近会话中的产物（{@code chat_attachment} 单源）。</li>
 * </ul>
 *
 * <p>只读聚合，不做写操作；未登录返回空列表 + 默认身份 + 全零概况，前端总能渲染。</p>
 */
public interface WorkbenchQueryService {

    /** 最近任务展示条数。 */
    int RECENT_TASK_LIMIT = 8;

    /** 最近产物展示条数。 */
    int RECENT_ARTIFACT_LIMIT = 20;

    /** 右栏「活跃 Agent」展示条数（按状态排序后截断）。 */
    int ACTIVE_AGENT_LIMIT = 6;

    /** 活跃 Agent 负载查询上限（须覆盖名册条数，否则状态判定会漏）。 */
    int ACTIVE_AGENT_WORKLOAD_LIMIT = 50;

    /** 活跃 Agent 状态：正在执行子任务。 */
    String STATUS_RUNNING = "RUNNING";
    /** 活跃 Agent 状态：名下产出处于复核流程中。 */
    String STATUS_REVIEWING = "REVIEWING";
    /** 活跃 Agent 状态：空闲待命。 */
    String STATUS_STANDBY = "STANDBY";

    /** 任务概况第一屏：执行中任务 + 子任务三态计数。 */
    record TaskOverview(long inProgressTasks, long pendingDispatch, long pendingReview, long pendingManual) {
        public static final TaskOverview EMPTY = new TaskOverview(0, 0, 0, 0);
    }

    /** 右栏「活跃 Agent」条目：Agent 名册 + 该 Agent 名下工作量状态。 */
    record ActiveAgent(Long agentId, String name, boolean internal, String role, String status, long runningCount) {
    }

    /** 右栏「最近任务」条目。 */
    record RecentTask(Long id, String title, String status, OffsetDateTime createTime) {
    }

    /** 三段式工作台快照。 */
    record Workbench(AssistantSnapshot profile,
                     TaskOverview taskOverview,
                     List<ActiveAgent> activeAgents,
                     List<RecentTask> recentTasks,
                     List<ArtifactItem> artifacts) {
    }

    /**
     * 聚合当前用户的工作台数据。
     *
     * @return 三段快照；未登录时列表均为空、概况全零，绝不返回 null
     */
    Workbench aggregate();
}
