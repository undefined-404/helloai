package com.helloai.core.agent.validator;

import com.helloai.common.constant.AgentRole;
import com.helloai.core.agent.entity.TeamMember;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link TeamValidator} C2-S1 单元测试：纯函数校验。
 */
@DisplayName("TeamValidator 纯函数校验（C2-S1）")
class TeamValidatorTest {

    @Test
    @DisplayName("槽位角色白名单：PLANNER/EXECUTOR/REVIEWER 合法，SYSTEM/null 非法")
    void slotRoleWhitelist() {
        assertThat(TeamValidator.isValidSlotRole(AgentRole.PLANNER)).isTrue();
        assertThat(TeamValidator.isValidSlotRole(AgentRole.EXECUTOR)).isTrue();
        assertThat(TeamValidator.isValidSlotRole(AgentRole.REVIEWER)).isTrue();
        assertThat(TeamValidator.isValidSlotRole(AgentRole.SYSTEM)).isFalse();
        assertThat(TeamValidator.isValidSlotRole(null)).isFalse();
    }

    @Test
    @DisplayName("hasExecutorMember：含 EXECUTOR 为 true，纯 PLANNER/空为 false")
    void hasExecutor() {
        TeamMember executor = member(AgentRole.EXECUTOR);
        TeamMember planner = member(AgentRole.PLANNER);

        assertThat(TeamValidator.hasExecutorMember(List.of(executor))).isTrue();
        assertThat(TeamValidator.hasExecutorMember(List.of(planner, executor))).isTrue();
        assertThat(TeamValidator.hasExecutorMember(List.of(planner))).isFalse();
        assertThat(TeamValidator.hasExecutorMember(List.of())).isFalse();
        assertThat(TeamValidator.hasExecutorMember(null)).isFalse();
    }

    @Test
    @DisplayName("isBlankName：null/空白为 true，正常为 false")
    void blankName() {
        assertThat(TeamValidator.isBlankName(null)).isTrue();
        assertThat(TeamValidator.isBlankName("  ")).isTrue();
        assertThat(TeamValidator.isBlankName("前端专项组")).isFalse();
    }

    private TeamMember member(AgentRole role) {
        TeamMember m = new TeamMember();
        m.setSlotRole(role);
        return m;
    }
}
