package com.helloai.api.controller;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
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
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.OffsetDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

/**
 * {@link TeamController} C2-S1 单元测试：9 端点转发与返回封装。
 *
 * <p>核心断言：参数原样透传 service；返回 R 封装 code=200 + DTO 字段映射；
 * 控制器无编排（verifyNoMoreInteractions）。</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("TeamController Team/成员管理端点（C2-S1）")
class TeamControllerTest {

    @Mock
    private TeamService teamService;

    @InjectMocks
    private TeamController controller;

    private static final Long TEAM_ID = 1L;
    private static final Long AGENT_ID = 100L;
    private static final OffsetDateTime NOW = OffsetDateTime.parse("2026-09-06T12:00:00+08:00");

    private Team team(TeamStatus status) {
        Team t = new Team();
        t.setId(TEAM_ID);
        t.setName("前端专项组");
        t.setDescription("desc");
        t.setStatus(status);
        t.setCreateTime(NOW);
        t.setUpdateTime(NOW);
        return t;
    }

    private TeamMember member() {
        TeamMember m = new TeamMember();
        m.setId(1L);
        m.setTeamId(TEAM_ID);
        m.setAgentId(AGENT_ID);
        m.setSlotRole(AgentRole.EXECUTOR);
        m.setWeight(100);
        return m;
    }

    @Test
    @DisplayName("创建：参数透传 + R 封装")
    void create() {
        when(teamService.createTeam("前端专项组", "desc")).thenReturn(team(TeamStatus.DRAFT));
        TeamRequest req = new TeamRequest();
        req.setName("前端专项组");
        req.setDescription("desc");

        R<TeamResponse> resp = controller.create(req);

        assertThat(resp.getCode()).isEqualTo(200);
        assertThat(resp.getData().getName()).isEqualTo("前端专项组");
        assertThat(resp.getData().getStatus()).isEqualTo("DRAFT");
        verify(teamService).createTeam("前端专项组", "desc");
        verifyNoMoreInteractions(teamService);
    }

    @Test
    @DisplayName("更新：参数透传")
    void update() {
        when(teamService.updateTeam(TEAM_ID, "新名", "nd")).thenReturn(team(TeamStatus.DRAFT));
        TeamRequest req = new TeamRequest();
        req.setName("新名");
        req.setDescription("nd");

        R<TeamResponse> resp = controller.update(TEAM_ID, req);

        assertThat(resp.getData().getName()).isEqualTo("前端专项组");
        verify(teamService).updateTeam(TEAM_ID, "新名", "nd");
        verifyNoMoreInteractions(teamService);
    }

    @Test
    @DisplayName("发布/归档：路径 id 透传")
    void publishAndArchive() {
        when(teamService.publish(TEAM_ID)).thenReturn(team(TeamStatus.ACTIVE));
        when(teamService.archive(TEAM_ID)).thenReturn(team(TeamStatus.ARCHIVED));

        assertThat(controller.publish(TEAM_ID).getData().getStatus()).isEqualTo("ACTIVE");
        assertThat(controller.archive(TEAM_ID).getData().getStatus()).isEqualTo("ARCHIVED");
        verify(teamService).publish(TEAM_ID);
        verify(teamService).archive(TEAM_ID);
        verifyNoMoreInteractions(teamService);
    }

    @Test
    @DisplayName("分页：status 过滤透传 + PageResult 封装")
    void page() {
        Page<Team> page = new Page<>(1, 10);
        page.setRecords(List.of(team(TeamStatus.ACTIVE)));
        page.setTotal(1);
        when(teamService.pageTeams(1, 10, TeamStatus.ACTIVE)).thenReturn(page);

        R<PageResult<TeamResponse>> resp = controller.page(1, 10, TeamStatus.ACTIVE);

        assertThat(resp.getData().getList()).hasSize(1);
        assertThat(resp.getData().getList().get(0).getStatus()).isEqualTo("ACTIVE");
        verify(teamService).pageTeams(1, 10, TeamStatus.ACTIVE);
        verifyNoMoreInteractions(teamService);
    }

    @Test
    @DisplayName("单查 + 成员列表")
    void getAndMembers() {
        when(teamService.getTeam(TEAM_ID)).thenReturn(team(TeamStatus.DRAFT));
        when(teamService.listMembers(TEAM_ID)).thenReturn(List.of(member()));

        R<TeamResponse> teamResp = controller.get(TEAM_ID);
        R<List<TeamMemberResponse>> membersResp = controller.listMembers(TEAM_ID);

        assertThat(teamResp.getData().getId()).isEqualTo(TEAM_ID);
        assertThat(membersResp.getData()).hasSize(1);
        assertThat(membersResp.getData().get(0).getSlotRole()).isEqualTo("EXECUTOR");
        verify(teamService).getTeam(TEAM_ID);
        verify(teamService).listMembers(TEAM_ID);
        verifyNoMoreInteractions(teamService);
    }

    @Test
    @DisplayName("添加成员：slotRole 转 AgentRole 透传")
    void addMember() {
        when(teamService.addMember(TEAM_ID, AGENT_ID, AgentRole.REVIEWER)).thenReturn(member());
        TeamMemberRequest req = new TeamMemberRequest();
        req.setAgentId(AGENT_ID);
        req.setSlotRole("reviewer");

        R<TeamMemberResponse> resp = controller.addMember(TEAM_ID, req);

        assertThat(resp.getData().getAgentId()).isEqualTo(AGENT_ID);
        verify(teamService).addMember(TEAM_ID, AGENT_ID, AgentRole.REVIEWER);
        verifyNoMoreInteractions(teamService);
    }

    @Test
    @DisplayName("添加成员：非法槽位角色 → BizException（不触 service）")
    void addMemberInvalidRole() {
        TeamMemberRequest req = new TeamMemberRequest();
        req.setAgentId(AGENT_ID);
        req.setSlotRole("manager");

        assertThatThrownBy(() -> controller.addMember(TEAM_ID, req))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("槽位角色非法");
        verifyNoMoreInteractions(teamService);
    }

    @Test
    @DisplayName("移除成员：路径透传")
    void removeMember() {
        R<Void> resp = controller.removeMember(TEAM_ID, AGENT_ID);
        assertThat(resp.getCode()).isEqualTo(200);
        verify(teamService).removeMember(TEAM_ID, AGENT_ID);
        verifyNoMoreInteractions(teamService);
    }
}
