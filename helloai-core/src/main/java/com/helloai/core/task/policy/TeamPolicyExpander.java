package com.helloai.core.task.policy;

import com.helloai.common.constant.AgentRole;
import com.helloai.core.agent.entity.TeamMember;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Team 成员 → {@code task.agent_policy} 槽位的展开器（N-002，C2-S2）。
 *
 * <p>纯函数（无 DB 依赖，可单测）：把 Team 成员按槽位角色展开为
 * {@code executorAgentIds} / {@code plannerAgentId} / {@code reviewerAgentId}。
 * 显式已指定的槽位键保留（显式 &gt; Team 展开，Team 是默认组合源）；
 * {@code teamId} 键保留为来源元信息（调度只读展开后的白名单/单值键）。</p>
 */
public final class TeamPolicyExpander {

    private TeamPolicyExpander() {
    }

    /**
     * 展开 {@code agent_policy} 槽位。
     *
     * <p>对 basePolicy 浅拷贝后处理，不改动入参。无对应槽位成员时保持原状
     * （缺 EXECUTOR 成员则 executorAgentIds 不写，等价不限定）。</p>
     */
    public static Map<String, Object> expand(Map<String, Object> basePolicy, List<TeamMember> members) {
        Map<String, Object> policy = basePolicy == null
                ? new LinkedHashMap<>()
                : new LinkedHashMap<>(basePolicy);
        if (TaskAgentPolicy.executorAgentIds(policy).isEmpty()) {
            List<Long> execs = agentIds(members, AgentRole.EXECUTOR);
            if (!execs.isEmpty()) {
                policy.put(TaskAgentPolicy.KEY_EXECUTOR_AGENT_IDS, execs);
            }
        }
        if (TaskAgentPolicy.plannerAgentId(policy) == null) {
            Long planner = pickSingle(members, AgentRole.PLANNER);
            if (planner != null) {
                policy.put(TaskAgentPolicy.KEY_PLANNER_AGENT_ID, planner);
            }
        }
        if (TaskAgentPolicy.reviewerAgentId(policy) == null) {
            Long reviewer = pickSingle(members, AgentRole.REVIEWER);
            if (reviewer != null) {
                policy.put(TaskAgentPolicy.KEY_REVIEWER_AGENT_ID, reviewer);
            }
        }
        return policy;
    }

    /** 指定槽位角色的成员 agentId 列表（weight desc、id asc）。 */
    public static List<Long> agentIds(List<TeamMember> members, AgentRole role) {
        return members == null ? List.of()
                : members.stream()
                        .filter(m -> m != null && m.getSlotRole() == role && m.getAgentId() != null)
                        .sorted(memberOrder())
                        .map(TeamMember::getAgentId)
                        .toList();
    }

    /** 单槽位取一个：weight desc、id asc 的第一个；无则 null。 */
    public static Long pickSingle(List<TeamMember> members, AgentRole role) {
        List<Long> ids = agentIds(members, role);
        return ids.isEmpty() ? null : ids.get(0);
    }

    private static Comparator<TeamMember> memberOrder() {
        return Comparator
                .comparingInt((TeamMember m) -> m.getWeight() == null ? 100 : m.getWeight())
                .reversed()
                .thenComparing(TeamMember::getId);
    }
}
