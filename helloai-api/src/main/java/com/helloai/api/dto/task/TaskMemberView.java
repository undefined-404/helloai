package com.helloai.api.dto.task;

import com.helloai.common.constant.TaskMemberJoinSource;
import com.helloai.common.constant.TaskMemberStatus;

import java.time.OffsetDateTime;

/**
 * 任务实际参与成员（任务详情页展示，2026-10-18）。
 *
 * <p><b>只代表「实际参与」（{@code task_agent_member}）</b>，不含团队编制成员
 * （{@code team_member}，后续演进为「专家团」概念）——两者语义不同，不混在同一列表。</p>
 *
 * <p>字段来源：成员事实取 {@code task_agent_member}，展示属性取 Agent 画像快照
 * （api 层组装，避免 task 域直捅 agent 实体）。</p>
 *
 * @param agentId    Agent ID
 * @param name       Agent 名称（Agent 已删除时为 {@code null}，前端回退显示短 ID）
 * @param role       Agent 主角色（PLANNER/EXECUTOR/REVIEWER/SYSTEM；可能为 null）
 * @param accessType 接入类型（API_KEY_LLM / MCP / BROWSER_AGENT 等）
 * @param onlineStatus 在线态（API_KEY_LLM 无心跳语义，可能为 null）
 * @param joinSource 入队来源：ASSIGNED / CLAIMED / REASSIGNED / MANUAL / REBUILT
 * @param status     成员状态（恒 ACTIVE；LEFT 预留）
 * @param joinTime   首次入队时间（「加入过即成员」，改派不改此值）
 */
public record TaskMemberView(
        Long agentId,
        String name,
        String role,
        String accessType,
        String onlineStatus,
        TaskMemberJoinSource joinSource,
        TaskMemberStatus status,
        OffsetDateTime joinTime
) {
}
