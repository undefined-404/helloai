package com.helloai.core.agent.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.helloai.common.base.BizException;
import com.helloai.common.constant.AgentRole;
import com.helloai.common.constant.AgentStatus;
import com.helloai.common.constant.TeamStatus;
import com.helloai.core.agent.entity.Agent;
import com.helloai.core.agent.entity.Team;
import com.helloai.core.agent.entity.TeamMember;
import com.helloai.core.agent.mapper.AgentMapper;
import com.helloai.core.agent.mapper.TeamMapper;
import com.helloai.core.agent.mapper.TeamMemberMapper;
import com.helloai.core.agent.service.TeamService;
import com.helloai.core.agent.validator.TeamValidator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Team 服务实现（N-002，C2-S1）。
 *
 * <p>只做组合数据与生命周期（命名组合 + 槽位声明），不参与派发决策；
 * S2 以 {@code agent_policy.teamId} 展开喂现有调度链白名单。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TeamServiceImpl extends ServiceImpl<TeamMapper, Team> implements TeamService {

    private final TeamMemberMapper memberMapper;
    private final AgentMapper agentMapper;

    @Transactional(rollbackFor = Exception.class)
    @Override
    public Team createTeam(String name, String description) {
        if (TeamValidator.isBlankName(name)) {
            throw new BizException("Team 名称不能为空");
        }
        String trimmed = name.trim();
        if (countByName(trimmed) > 0) {
            throw new BizException("Team 名称已存在: " + trimmed);
        }
        Team team = new Team();
        team.setName(trimmed);
        team.setDescription(description);
        team.setStatus(TeamStatus.DRAFT);
        save(team);
        log.info("Team 已创建: id={}, name={}", team.getId(), trimmed);
        return team;
    }

    @Transactional(rollbackFor = Exception.class)
    @Override
    public Team updateTeam(Long id, String name, String description) {
        Team team = requireTeam(id);
        if (team.getStatus() == TeamStatus.ARCHIVED) {
            throw new BizException("已归档 Team 不可编辑: id=" + id);
        }
        if (name != null && !name.isBlank() && !name.trim().equals(team.getName())) {
            String trimmed = name.trim();
            if (countByName(trimmed) > 0) {
                throw new BizException("Team 名称已存在: " + trimmed);
            }
            team.setName(trimmed);
        }
        team.setDescription(description);
        updateById(team);
        return team;
    }

    @Transactional(rollbackFor = Exception.class)
    @Override
    public Team publish(Long id) {
        Team team = requireTeam(id);
        if (team.getStatus() != TeamStatus.DRAFT) {
            throw new BizException("仅 DRAFT 可发布: id=" + id);
        }
        List<TeamMember> members = listMembers(id);
        if (!TeamValidator.hasExecutorMember(members)) {
            throw new BizException("Team 发布需至少 1 名 EXECUTOR 成员: id=" + id);
        }
        // 发布时成员快照校验：所有成员 Agent 仍须 ACTIVE（fail-close）
        List<Long> invalidAgents = members.stream()
                .map(m -> agentMapper.selectById(m.getAgentId()))
                .filter(a -> a == null || a.getStatus() != AgentStatus.ACTIVE)
                .map(a -> a == null ? null : a.getId())
                .toList();
        if (!invalidAgents.isEmpty()) {
            throw new BizException("Team 存在非 ACTIVE 成员，发布失败: id=" + id + ", agents=" + invalidAgents);
        }
        int updated = baseMapper.update(null,
                new LambdaUpdateWrapper<Team>()
                        .eq(Team::getId, id)
                        .eq(Team::getStatus, TeamStatus.DRAFT)
                        .set(Team::getStatus, TeamStatus.ACTIVE));
        if (updated == 0) {
            throw new BizException("Team 状态冲突，发布失败（CAS）: id=" + id);
        }
        team.setStatus(TeamStatus.ACTIVE);
        log.info("Team 已发布: id={}, members={}", id, members.size());
        return team;
    }

    @Transactional(rollbackFor = Exception.class)
    @Override
    public Team archive(Long id) {
        Team team = requireTeam(id);
        if (team.getStatus() == TeamStatus.ARCHIVED) {
            return team;
        }
        lambdaUpdate()
                .eq(Team::getId, id)
                .set(Team::getStatus, TeamStatus.ARCHIVED)
                .update();
        team.setStatus(TeamStatus.ARCHIVED);
        log.info("Team 已归档: id={}", id);
        return team;
    }

    @Override
    public IPage<Team> pageTeams(long page, long size, TeamStatus status) {
        LambdaQueryWrapper<Team> wrapper = new LambdaQueryWrapper<>();
        if (status != null) {
            wrapper.eq(Team::getStatus, status);
        }
        wrapper.orderByDesc(Team::getCreateTime);
        return baseMapper.selectPage(new Page<>(page, size), wrapper);
    }

    @Override
    public Team getTeam(Long id) {
        return requireTeam(id);
    }

    @Override
    public List<TeamMember> listMembers(Long teamId) {
        return memberMapper.selectList(new LambdaQueryWrapper<TeamMember>()
                .eq(TeamMember::getTeamId, teamId)
                .orderByDesc(TeamMember::getWeight)
                .orderByAsc(TeamMember::getId));
    }

    @Transactional(rollbackFor = Exception.class)
    @Override
    public TeamMember addMember(Long teamId, Long agentId, AgentRole slotRole) {
        Team team = requireTeam(teamId);
        if (team.getStatus() == TeamStatus.ARCHIVED) {
            throw new BizException("已归档 Team 不可新增成员: id=" + teamId);
        }
        if (!TeamValidator.isValidSlotRole(slotRole)) {
            throw new BizException("槽位角色非法: " + slotRole);
        }
        Agent agent = agentMapper.selectById(agentId);
        if (agent == null || agent.getStatus() != AgentStatus.ACTIVE) {
            throw new BizException("Agent 不存在或非 ACTIVE: agentId=" + agentId);
        }
        Long count = memberMapper.selectCount(new LambdaQueryWrapper<TeamMember>()
                .eq(TeamMember::getTeamId, teamId)
                .eq(TeamMember::getAgentId, agentId));
        if (count != null && count > 0) {
            throw new BizException("成员已存在: teamId=" + teamId + ", agentId=" + agentId);
        }
        TeamMember member = new TeamMember();
        member.setTeamId(teamId);
        member.setAgentId(agentId);
        member.setSlotRole(slotRole);
        member.setWeight(100);
        memberMapper.insert(member);
        log.info("Team 成员已添加: teamId={}, agentId={}, role={}", teamId, agentId, slotRole);
        return member;
    }

    @Transactional(rollbackFor = Exception.class)
    @Override
    public void removeMember(Long teamId, Long agentId) {
        requireTeam(teamId);
        TeamMember member = memberMapper.selectOne(new LambdaQueryWrapper<TeamMember>()
                .eq(TeamMember::getTeamId, teamId)
                .eq(TeamMember::getAgentId, agentId));
        if (member == null) {
            throw new BizException("成员不存在: teamId=" + teamId + ", agentId=" + agentId);
        }
        memberMapper.physicallyDeleteById(member.getId());
        log.info("Team 成员已移除: teamId={}, agentId={}", teamId, agentId);
    }

    private Team requireTeam(Long id) {
        Team team = getById(id);
        if (team == null) {
            throw new BizException("Team 不存在: id=" + id);
        }
        return team;
    }

    private long countByName(String name) {
        Long count = baseMapper.selectCount(new LambdaQueryWrapper<Team>().eq(Team::getName, name));
        return count == null ? 0 : count;
    }
}
