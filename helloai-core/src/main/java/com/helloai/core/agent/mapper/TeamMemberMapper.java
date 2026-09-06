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
}
