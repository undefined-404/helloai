package com.helloai.core.agent.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
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
import com.helloai.core.agent.service.impl.TeamServiceImpl;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link TeamService} C2-S1 单元测试：Team/成员 CRUD + 生命周期。
 *
 * <p>spy + mock memberMapper/agentMapper，注入 mock baseMapper 隔离数据库；
 * TableInfo 预热规避 MP 3.5.9 lambda 缓存坑（WorkflowTemplateServiceImplTest 同款）。</p>
 */
@DisplayName("TeamService Team/成员 CRUD + 生命周期（C2-S1）")
class TeamServiceImplTest {

    private static final Long TEAM_ID = 1L;
    private static final Long AGENT_EXEC = 100L;
    private static final Long AGENT_PLAN = 101L;
    private static final Long AGENT_OFF = 102L;

    @BeforeAll
    static void initTableInfo() {
        org.apache.ibatis.builder.MapperBuilderAssistant assistant =
                new org.apache.ibatis.builder.MapperBuilderAssistant(new MybatisConfiguration(), "");
        TableInfoHelper.initTableInfo(assistant, Team.class);
        TableInfoHelper.initTableInfo(assistant, TeamMember.class);
        TableInfoHelper.initTableInfo(assistant, Agent.class);
    }

    private TeamMapper teamMapper;
    private TeamMemberMapper memberMapper;
    private AgentMapper agentMapper;
    private TeamServiceImpl service;

    @BeforeEach
    void setUp() {
        teamMapper = mock(TeamMapper.class);
        memberMapper = mock(TeamMemberMapper.class);
        agentMapper = mock(AgentMapper.class);
        service = spy(new TeamServiceImpl(memberMapper, agentMapper));
        ReflectionTestUtils.setField(service, "baseMapper", teamMapper);
    }

    private Team team(TeamStatus status) {
        Team t = new Team();
        t.setId(TEAM_ID);
        t.setName("前端专项组");
        t.setStatus(status);
        return t;
    }

    private Agent agent(Long id, AgentStatus status) {
        Agent a = new Agent();
        a.setId(id);
        a.setStatus(status);
        return a;
    }

    private TeamMember member(Long agentId, AgentRole role) {
        TeamMember m = new TeamMember();
        m.setId(agentId);
        m.setTeamId(TEAM_ID);
        m.setAgentId(agentId);
        m.setSlotRole(role);
        return m;
    }

    @Nested
    @DisplayName("Team CRUD")
    class TeamCrud {

        @Test
        @DisplayName("创建成功（DRAFT）")
        void createOk() {
            when(teamMapper.selectCount(any(LambdaQueryWrapper.class))).thenReturn(0L);
            Team created = service.createTeam("前端专项组", "desc");
            assertThat(created.getStatus()).isEqualTo(TeamStatus.DRAFT);
            assertThat(created.getName()).isEqualTo("前端专项组");
        }

        @Test
        @DisplayName("名称空白报错")
        void createBlankName() {
            assertThatThrownBy(() -> service.createTeam("  ", null))
                    .isInstanceOf(BizException.class)
                    .hasMessageContaining("名称不能为空");
        }

        @Test
        @DisplayName("名称重复报错")
        void createDuplicateName() {
            when(teamMapper.selectCount(any(LambdaQueryWrapper.class))).thenReturn(1L);
            assertThatThrownBy(() -> service.createTeam("前端专项组", null))
                    .isInstanceOf(BizException.class)
                    .hasMessageContaining("名称已存在");
        }

        @Test
        @DisplayName("编辑成功（DRAFT/ACTIVE）")
        void updateOk() {
            doReturn(team(TeamStatus.DRAFT)).when(service).getById(TEAM_ID);
            when(teamMapper.selectCount(any(LambdaQueryWrapper.class))).thenReturn(0L);
            Team updated = service.updateTeam(TEAM_ID, "新名称", "新描述");
            assertThat(updated.getName()).isEqualTo("新名称");
        }

        @Test
        @DisplayName("已归档不可编辑")
        void updateArchived() {
            doReturn(team(TeamStatus.ARCHIVED)).when(service).getById(TEAM_ID);
            assertThatThrownBy(() -> service.updateTeam(TEAM_ID, "x", null))
                    .isInstanceOf(BizException.class)
                    .hasMessageContaining("不可编辑");
        }

        @Test
        @DisplayName("归档成功（幂等）")
        void archiveOk() {
            doReturn(team(TeamStatus.ACTIVE)).when(service).getById(TEAM_ID);
            Team archived = service.archive(TEAM_ID);
            assertThat(archived.getStatus()).isEqualTo(TeamStatus.ARCHIVED);
            // 已归档再归档幂等
            doReturn(team(TeamStatus.ARCHIVED)).when(service).getById(TEAM_ID);
            assertThat(service.archive(TEAM_ID).getStatus()).isEqualTo(TeamStatus.ARCHIVED);
        }

        @Test
        @DisplayName("不存在报错")
        void notFound() {
            doReturn(null).when(service).getById(TEAM_ID);
            assertThatThrownBy(() -> service.getTeam(TEAM_ID))
                    .isInstanceOf(BizException.class)
                    .hasMessageContaining("不存在");
        }
    }

    @Nested
    @DisplayName("发布生命周期")
    class Publish {

        @Test
        @DisplayName("发布成功：有 EXECUTOR 且成员 ACTIVE，CAS DRAFT→ACTIVE")
        void publishOk() {
            doReturn(team(TeamStatus.DRAFT)).when(service).getById(TEAM_ID);
            doReturn(List.of(member(AGENT_EXEC, AgentRole.EXECUTOR), member(AGENT_PLAN, AgentRole.PLANNER)))
                    .when(service).listMembers(TEAM_ID);
            when(agentMapper.selectById(AGENT_EXEC)).thenReturn(agent(AGENT_EXEC, AgentStatus.ACTIVE));
            when(agentMapper.selectById(AGENT_PLAN)).thenReturn(agent(AGENT_PLAN, AgentStatus.ACTIVE));
            doReturn(1).when(teamMapper).update(any(), any());
            Team published = service.publish(TEAM_ID);
            assertThat(published.getStatus()).isEqualTo(TeamStatus.ACTIVE);
            verify(teamMapper).update(any(), any());
        }

        @Test
        @DisplayName("非 DRAFT 不可发布")
        void publishNonDraft() {
            doReturn(team(TeamStatus.ACTIVE)).when(service).getById(TEAM_ID);
            assertThatThrownBy(() -> service.publish(TEAM_ID))
                    .isInstanceOf(BizException.class)
                    .hasMessageContaining("仅 DRAFT 可发布");
        }

        @Test
        @DisplayName("无 EXECUTOR 不可发布")
        void publishNoExecutor() {
            doReturn(team(TeamStatus.DRAFT)).when(service).getById(TEAM_ID);
            doReturn(List.of(member(AGENT_PLAN, AgentRole.PLANNER))).when(service).listMembers(TEAM_ID);
            assertThatThrownBy(() -> service.publish(TEAM_ID))
                    .isInstanceOf(BizException.class)
                    .hasMessageContaining("至少 1 名 EXECUTOR");
        }

        @Test
        @DisplayName("存在非 ACTIVE 成员不可发布（fail-close）")
        void publishInactiveMember() {
            doReturn(team(TeamStatus.DRAFT)).when(service).getById(TEAM_ID);
            doReturn(List.of(member(AGENT_EXEC, AgentRole.EXECUTOR), member(AGENT_OFF, AgentRole.REVIEWER)))
                    .when(service).listMembers(TEAM_ID);
            when(agentMapper.selectById(AGENT_EXEC)).thenReturn(agent(AGENT_EXEC, AgentStatus.ACTIVE));
            when(agentMapper.selectById(AGENT_OFF)).thenReturn(agent(AGENT_OFF, AgentStatus.DISABLED));
            assertThatThrownBy(() -> service.publish(TEAM_ID))
                    .isInstanceOf(BizException.class)
                    .hasMessageContaining("非 ACTIVE");
        }

        @Test
        @DisplayName("CAS 冲突：updated=0 抛冲突")
        void publishCasConflict() {
            doReturn(team(TeamStatus.DRAFT)).when(service).getById(TEAM_ID);
            doReturn(List.of(member(AGENT_EXEC, AgentRole.EXECUTOR))).when(service).listMembers(TEAM_ID);
            when(agentMapper.selectById(AGENT_EXEC)).thenReturn(agent(AGENT_EXEC, AgentStatus.ACTIVE));
            when(teamMapper.update(any(), any())).thenReturn(0);
            assertThatThrownBy(() -> service.publish(TEAM_ID))
                    .isInstanceOf(BizException.class)
                    .hasMessageContaining("CAS");
        }
    }

    @Nested
    @DisplayName("成员管理")
    class Members {

        @Test
        @DisplayName("添加成功（返回落库成员）")
        void addOk() {
            doReturn(team(TeamStatus.DRAFT)).when(service).getById(TEAM_ID);
            when(agentMapper.selectById(AGENT_EXEC)).thenReturn(agent(AGENT_EXEC, AgentStatus.ACTIVE));
            when(memberMapper.selectCount(any(LambdaQueryWrapper.class))).thenReturn(0L);
            TeamMember member = service.addMember(TEAM_ID, AGENT_EXEC, AgentRole.EXECUTOR);
            verify(memberMapper).insert(any(TeamMember.class));
            assertThat(member.getAgentId()).isEqualTo(AGENT_EXEC);
            assertThat(member.getSlotRole()).isEqualTo(AgentRole.EXECUTOR);
            assertThat(member.getWeight()).isEqualTo(100);
        }

        @Test
        @DisplayName("已归档 Team 不可新增成员")
        void addArchived() {
            doReturn(team(TeamStatus.ARCHIVED)).when(service).getById(TEAM_ID);
            assertThatThrownBy(() -> service.addMember(TEAM_ID, AGENT_EXEC, AgentRole.EXECUTOR))
                    .isInstanceOf(BizException.class)
                    .hasMessageContaining("不可新增成员");
        }

        @Test
        @DisplayName("槽位角色非法（SYSTEM）")
        void addInvalidRole() {
            doReturn(team(TeamStatus.DRAFT)).when(service).getById(TEAM_ID);
            assertThatThrownBy(() -> service.addMember(TEAM_ID, AGENT_EXEC, AgentRole.SYSTEM))
                    .isInstanceOf(BizException.class)
                    .hasMessageContaining("槽位角色非法");
        }

        @Test
        @DisplayName("Agent 非 ACTIVE 不可入组")
        void addInactiveAgent() {
            doReturn(team(TeamStatus.DRAFT)).when(service).getById(TEAM_ID);
            when(agentMapper.selectById(AGENT_OFF)).thenReturn(agent(AGENT_OFF, AgentStatus.DISABLED));
            assertThatThrownBy(() -> service.addMember(TEAM_ID, AGENT_OFF, AgentRole.EXECUTOR))
                    .isInstanceOf(BizException.class)
                    .hasMessageContaining("非 ACTIVE");
        }

        @Test
        @DisplayName("成员重复报错")
        void addDuplicate() {
            doReturn(team(TeamStatus.DRAFT)).when(service).getById(TEAM_ID);
            when(agentMapper.selectById(AGENT_EXEC)).thenReturn(agent(AGENT_EXEC, AgentStatus.ACTIVE));
            when(memberMapper.selectCount(any(LambdaQueryWrapper.class))).thenReturn(1L);
            assertThatThrownBy(() -> service.addMember(TEAM_ID, AGENT_EXEC, AgentRole.EXECUTOR))
                    .isInstanceOf(BizException.class)
                    .hasMessageContaining("成员已存在");
        }

        @Test
        @DisplayName("移除成功（物理删除）")
        void removeOk() {
            doReturn(team(TeamStatus.DRAFT)).when(service).getById(TEAM_ID);
            when(memberMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(member(AGENT_EXEC, AgentRole.EXECUTOR));
            service.removeMember(TEAM_ID, AGENT_EXEC);
            verify(memberMapper).physicallyDeleteById(AGENT_EXEC);
            verify(memberMapper, never()).deleteById(any(java.io.Serializable.class));
        }

        @Test
        @DisplayName("成员不存在移除报错")
        void removeMissing() {
            doReturn(team(TeamStatus.DRAFT)).when(service).getById(TEAM_ID);
            when(memberMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(null);
            assertThatThrownBy(() -> service.removeMember(TEAM_ID, AGENT_EXEC))
                    .isInstanceOf(BizException.class)
                    .hasMessageContaining("成员不存在");
        }
    }
}
