package com.helloai.core.task.policy;

import com.helloai.common.constant.AgentRole;
import com.helloai.core.agent.entity.TeamMember;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link TeamPolicyExpander} C2-S2 单元测试：Team 成员 → agent_policy 槽位展开。
 */
@DisplayName("TeamPolicyExpander 槽位展开（C2-S2）")
class TeamPolicyExpanderTest {

    private TeamMember member(Long id, Long agentId, AgentRole role, Integer weight) {
        TeamMember m = new TeamMember();
        m.setId(id);
        m.setAgentId(agentId);
        m.setSlotRole(role);
        m.setWeight(weight);
        return m;
    }

    @Test
    @DisplayName("展开：EXECUTOR 落白名单，PLANNER/REVIEWER 单值取 weight 高者")
    void expandBySlot() {
        List<TeamMember> members = List.of(
                member(1L, 101L, AgentRole.EXECUTOR, 100),
                member(2L, 102L, AgentRole.EXECUTOR, 80),
                member(3L, 103L, AgentRole.PLANNER, 100),
                member(4L, 104L, AgentRole.REVIEWER, 100));

        Map<String, Object> policy = TeamPolicyExpander.expand(Map.of(TaskAgentPolicy.KEY_TEAM_ID, 99L), members);

        assertThat(policy).containsEntry(TaskAgentPolicy.KEY_TEAM_ID, 99L);
        assertThat(policy).containsEntry(TaskAgentPolicy.KEY_EXECUTOR_AGENT_IDS, List.of(101L, 102L));
        assertThat(policy).containsEntry(TaskAgentPolicy.KEY_PLANNER_AGENT_ID, 103L);
        assertThat(policy).containsEntry(TaskAgentPolicy.KEY_REVIEWER_AGENT_ID, 104L);
    }

    @Test
    @DisplayName("显式槽位优先：已指定 executor/planner/reviewer 不被 Team 覆盖")
    void explicitSlotsWin() {
        List<TeamMember> members = List.of(member(1L, 101L, AgentRole.EXECUTOR, 100));
        Map<String, Object> base = Map.of(
                TaskAgentPolicy.KEY_TEAM_ID, 99L,
                TaskAgentPolicy.KEY_EXECUTOR_AGENT_IDS, List.of(201L),
                TaskAgentPolicy.KEY_PLANNER_AGENT_ID, 202L,
                TaskAgentPolicy.KEY_REVIEWER_AGENT_ID, 203L);

        Map<String, Object> policy = TeamPolicyExpander.expand(base, members);

        assertThat(policy).containsEntry(TaskAgentPolicy.KEY_EXECUTOR_AGENT_IDS, List.of(201L));
        assertThat(policy).containsEntry(TaskAgentPolicy.KEY_PLANNER_AGENT_ID, 202L);
        assertThat(policy).containsEntry(TaskAgentPolicy.KEY_REVIEWER_AGENT_ID, 203L);
    }

    @Test
    @DisplayName("缺槽位成员保持原状；空成员不写新键；null 入参不抛")
    void missingSlotKeepsBase() {
        Map<String, Object> base = Map.of(TaskAgentPolicy.KEY_TEAM_ID, 99L);
        // 只有 PLANNER，无 EXECUTOR/REVIEWER
        Map<String, Object> policy = TeamPolicyExpander.expand(
                base, List.of(member(1L, 101L, AgentRole.PLANNER, 100)));

        assertThat(policy).doesNotContainKey(TaskAgentPolicy.KEY_EXECUTOR_AGENT_IDS);
        assertThat(policy).containsEntry(TaskAgentPolicy.KEY_PLANNER_AGENT_ID, 101L);
        assertThat(policy).doesNotContainKey(TaskAgentPolicy.KEY_REVIEWER_AGENT_ID);

        // 空成员：仅保留 teamId
        Map<String, Object> empty = TeamPolicyExpander.expand(base, List.of());
        assertThat(empty).containsOnlyKeys(TaskAgentPolicy.KEY_TEAM_ID);

        // null 入参：返回空 Map，不抛
        assertThat(TeamPolicyExpander.expand(null, null)).isEmpty();
    }

    @Test
    @DisplayName("单槽位选人：weight desc、id asc")
    void pickSingleOrder() {
        List<TeamMember> members = List.of(
                member(5L, 105L, AgentRole.REVIEWER, 100),
                member(6L, 106L, AgentRole.REVIEWER, 100),
                member(7L, 107L, AgentRole.REVIEWER, 120));

        assertThat(TeamPolicyExpander.pickSingle(members, AgentRole.REVIEWER)).isEqualTo(107L);
        assertThat(TeamPolicyExpander.pickSingle(members, AgentRole.PLANNER)).isNull();
    }
}
