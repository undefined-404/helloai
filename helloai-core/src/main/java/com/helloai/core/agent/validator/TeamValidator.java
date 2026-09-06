package com.helloai.core.agent.validator;

import com.helloai.common.constant.AgentRole;
import com.helloai.core.agent.entity.TeamMember;

import java.util.List;

/**
 * Team 组合规则的纯函数校验器（N-002，C2-S1）。
 *
 * <p>静态无状态，可独立单测（参照 WorkflowDefinitionValidator 模式）。</p>
 */
public final class TeamValidator {

    private TeamValidator() {
    }

    /**
     * 槽位角色白名单校验：非 null 且非 SYSTEM（SYSTEM 仅系统级事件，不做业务槽位）。
     */
    public static boolean isValidSlotRole(AgentRole role) {
        return role != null && role != AgentRole.SYSTEM;
    }

    /**
     * Team 是否至少含 1 名 EXECUTOR（发布前校验：执行角色是 Team 的可用下限）。
     */
    public static boolean hasExecutorMember(List<TeamMember> members) {
        return members != null
                && members.stream().anyMatch(m -> m != null && m.getSlotRole() == AgentRole.EXECUTOR);
    }

    /** 名称是否空白（非法）。 */
    public static boolean isBlankName(String name) {
        return name == null || name.isBlank();
    }
}
