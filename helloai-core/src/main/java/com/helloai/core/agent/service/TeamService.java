package com.helloai.core.agent.service;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.helloai.common.constant.AgentRole;
import com.helloai.common.constant.TeamStatus;
import com.helloai.core.agent.entity.Team;
import com.helloai.core.agent.entity.TeamMember;

import java.util.List;

/**
 * Team 服务（N-002，C2-S1）。
 *
 * <p>Team = 命名可复用的 Agent 组合 + 槽位填充参数源；本服务只做组合数据与生命周期，
 * 不参与派发决策（复用现有 {@code AgentSelector} 现链，S2 展开）。</p>
 */
public interface TeamService {

    /** 创建 Team（初始 DRAFT）。 */
    Team createTeam(String name, String description);

    /** 编辑名称/描述（ARCHIVED 不可编辑）。 */
    Team updateTeam(Long id, String name, String description);

    /** 发布（DRAFT → ACTIVE，CAS）：校验至少 1 名 EXECUTOR、成员均 ACTIVE。 */
    Team publish(Long id);

    /** 归档（DRAFT/ACTIVE → ARCHIVED）。 */
    Team archive(Long id);

    /** 分页查询（status 可空=全部）。 */
    IPage<Team> pageTeams(long page, long size, TeamStatus status);

    /** 按 id 查询（不存在抛 BizException）。 */
    Team getTeam(Long id);

    /** 成员列表（按 weight desc、id asc）。 */
    List<TeamMember> listMembers(Long teamId);

    /** 新增成员：校验 Team 非 ARCHIVED、槽位角色白名单、Agent ACTIVE、不重复；返回落库成员。 */
    TeamMember addMember(Long teamId, Long agentId, AgentRole slotRole);

    /** 移除成员（物理删除，避免逻辑删除行与唯一约束冲突）。 */
    void removeMember(Long teamId, Long agentId);
}
