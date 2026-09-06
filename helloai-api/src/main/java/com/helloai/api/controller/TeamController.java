package com.helloai.api.controller;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.helloai.api.dto.PageResult;
import com.helloai.api.dto.team.TeamMemberRequest;
import com.helloai.api.dto.team.TeamMemberResponse;
import com.helloai.api.dto.team.TeamRequest;
import com.helloai.api.dto.team.TeamResponse;
import com.helloai.common.base.BizException;
import com.helloai.common.base.R;
import com.helloai.common.constant.AgentRole;
import com.helloai.common.constant.TeamStatus;
import com.helloai.core.agent.entity.Team;
import com.helloai.core.agent.entity.TeamMember;
import com.helloai.core.agent.service.TeamService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * Team 组合管理端点（N-002，C2-S1；/api/admin/* 由 AuthInterceptor 统一鉴权）。
 *
 * <p>纯参数接收 + DTO 装配 + R 封装，无编排（§6.3 红线）；Team 只做组合数据与生命周期，
 * 不参与派发决策（展开喂现有调度链白名单，C2-S2）。</p>
 */
@RestController
@RequestMapping("/api/admin/teams")
@RequiredArgsConstructor
public class TeamController {

    private final TeamService teamService;

    @PostMapping
    public R<TeamResponse> create(@RequestBody TeamRequest req) {
        return R.ok(toTeam(teamService.createTeam(req.getName(), req.getDescription())));
    }

    @PutMapping("/{id}")
    public R<TeamResponse> update(@PathVariable("id") Long id, @RequestBody TeamRequest req) {
        return R.ok(toTeam(teamService.updateTeam(id, req.getName(), req.getDescription())));
    }

    @PostMapping("/{id}/publish")
    public R<TeamResponse> publish(@PathVariable("id") Long id) {
        return R.ok(toTeam(teamService.publish(id)));
    }

    @PostMapping("/{id}/archive")
    public R<TeamResponse> archive(@PathVariable("id") Long id) {
        return R.ok(toTeam(teamService.archive(id)));
    }

    @GetMapping
    public R<PageResult<TeamResponse>> page(
            @RequestParam(value = "page", defaultValue = "1") long page,
            @RequestParam(value = "size", defaultValue = "20") long size,
            @RequestParam(value = "status", required = false) TeamStatus status) {
        IPage<Team> result = teamService.pageTeams(page, size, status);
        return R.ok(PageResult.of(result, this::toTeam));
    }

    @GetMapping("/{id}")
    public R<TeamResponse> get(@PathVariable("id") Long id) {
        return R.ok(toTeam(teamService.getTeam(id)));
    }

    @GetMapping("/{id}/members")
    public R<List<TeamMemberResponse>> listMembers(@PathVariable("id") Long id) {
        List<TeamMemberResponse> list = teamService.listMembers(id).stream()
                .map(this::toMember)
                .toList();
        return R.ok(list);
    }

    @PostMapping("/{id}/members")
    public R<TeamMemberResponse> addMember(@PathVariable("id") Long id,
                                           @RequestBody TeamMemberRequest req) {
        if (req.getAgentId() == null || req.getSlotRole() == null || req.getSlotRole().isBlank()) {
            throw new BizException("成员 agentId 与槽位角色必填");
        }
        AgentRole role;
        try {
            role = AgentRole.valueOf(req.getSlotRole().trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new BizException("槽位角色非法（必须为 PLANNER/EXECUTOR/REVIEWER）: " + req.getSlotRole());
        }
        return R.ok(toMember(teamService.addMember(id, req.getAgentId(), role)));
    }

    @DeleteMapping("/{id}/members/{agentId}")
    public R<Void> removeMember(@PathVariable("id") Long id, @PathVariable("agentId") Long agentId) {
        teamService.removeMember(id, agentId);
        return R.ok();
    }

    private TeamResponse toTeam(Team t) {
        TeamResponse resp = new TeamResponse();
        resp.setId(t.getId());
        resp.setName(t.getName());
        resp.setDescription(t.getDescription());
        resp.setStatus(t.getStatus() != null ? t.getStatus().name() : null);
        resp.setCreateTime(t.getCreateTime());
        resp.setUpdateTime(t.getUpdateTime());
        return resp;
    }

    private TeamMemberResponse toMember(TeamMember m) {
        TeamMemberResponse resp = new TeamMemberResponse();
        resp.setId(m.getId());
        resp.setTeamId(m.getTeamId());
        resp.setAgentId(m.getAgentId());
        resp.setSlotRole(m.getSlotRole() != null ? m.getSlotRole().name() : null);
        resp.setWeight(m.getWeight());
        return resp;
    }
}
