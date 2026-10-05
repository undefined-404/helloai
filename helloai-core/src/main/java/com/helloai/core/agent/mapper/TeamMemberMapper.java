package com.helloai.core.agent.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.helloai.core.agent.entity.TeamMember;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Param;

/**
 * Team 成员 Mapper（N-002，C2-S1）。
 *
 * <p>{@code physicallyDeleteById}：成员删除用物理删除——team_member 存在唯一约束
 * (team_id, agent_id)，逻辑删除行会与后续重新加入冲突；成员组合声明无审计诉求。</p>
 */
public interface TeamMemberMapper extends BaseMapper<TeamMember> {

    /**
     * 物理删除成员（绕开逻辑删除，供成员移除与逻辑删除行清理用）。
     */
    @Delete("DELETE FROM team_member WHERE id = #{id}")
    int physicallyDeleteById(@Param("id") Long id);

    /**
     * 物理删除某 Agent 的全部团队成员关系（cascade）。
     *
     * <p>D-1（2026-10-05）：{@code team_member.agent_id} 语义列<b>无外键</b>——删 Agent 却仍留
     * 在团队花名册里语义错误（全库孤儿实测为 0，属防御性补齐）。供 Agent 级联删除使用；
     * 物理删除（绕开逻辑删除），与 {@link #physicallyDeleteById} 同口径。</p>
     */
    @Delete("DELETE FROM team_member WHERE agent_id = #{agentId}")
    int physicalDeleteByAgentId(@Param("agentId") Long agentId);
}
