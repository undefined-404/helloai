package com.helloai.core.agent.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.helloai.common.base.BaseEntity;
import com.helloai.common.constant.AgentRole;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * Team 成员槽位（N-002，C2-S1）。
 *
 * <p>一个 agent 在一个 team 内单一槽位（唯一约束 (team_id, agent_id)），
 * 槽位角色白名单 PLANNER/EXECUTOR/REVIEWER（SYSTEM 不做业务槽位）。
 * 成员删除为物理删除（避免逻辑删除行与唯一约束冲突导致无法重新加入）。</p>
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("team_member")
public class TeamMember extends BaseEntity {

    private Long teamId;

    private Long agentId;

    /** 槽位角色：PLANNER / EXECUTOR / REVIEWER。 */
    private AgentRole slotRole;

    /** 组内优先级（默认 100，预留，S1 不参与派发排序）。 */
    private Integer weight;
}
