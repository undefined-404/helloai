package com.helloai.core.task.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.helloai.common.base.BizException;
import com.helloai.common.constant.AgentRole;
import com.helloai.common.constant.TeamStatus;
import com.helloai.core.agent.entity.Team;
import com.helloai.core.agent.entity.TeamMember;
import com.helloai.core.agent.service.AgentInboxService;
import com.helloai.core.agent.service.AgentService;
import com.helloai.core.agent.service.TeamService;
import com.helloai.core.task.entity.Task;
import com.helloai.core.task.mapper.AttachmentMapper;
import com.helloai.core.task.mapper.ModuleMapper;
import com.helloai.core.task.mapper.SubTaskMapper;
import com.helloai.core.task.mapper.TaskMapper;
import com.helloai.core.task.mapper.TaskTimelineMapper;
import com.helloai.core.task.policy.TaskAgentPolicy;
import com.helloai.core.task.port.ReviewPort;
import com.helloai.core.task.service.impl.TaskServiceImpl;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link TaskServiceImpl} C2-S2 单元测试：createTask 时 agent_policy.teamId 展开快照。
 *
 * <p>spy + mock 全部依赖 + 注入 mock baseMapper，验证展开路径（快照隔离）与失败语义。</p>
 */
@DisplayName("TaskServiceImpl teamId 展开快照（C2-S2）")
class TaskServiceImplTest {

    private static final Long TEAM_ID = 9L;

    @BeforeAll
    static void initTableInfo() {
        org.apache.ibatis.builder.MapperBuilderAssistant assistant =
                new org.apache.ibatis.builder.MapperBuilderAssistant(new MybatisConfiguration(), "");
        TableInfoHelper.initTableInfo(assistant, Task.class);
    }

    private TaskMapper taskMapper;
    private TeamService teamService;
    private TaskServiceImpl service;

    @BeforeEach
    void setUp() {
        SubTaskMapper subTaskMapper = mock(SubTaskMapper.class);
        ModuleMapper moduleMapper = mock(ModuleMapper.class);
        ReviewPort reviewPort = mock(ReviewPort.class);
        TaskTimelineMapper timelineMapper = mock(TaskTimelineMapper.class);
        AttachmentMapper attachmentMapper = mock(AttachmentMapper.class);
        AgentInboxService agentInboxService = mock(AgentInboxService.class);
        AgentService agentService = mock(AgentService.class);
        com.helloai.core.task.service.SubTaskService subTaskService =
                mock(com.helloai.core.task.service.SubTaskService.class);
        teamService = mock(TeamService.class);
        taskMapper = mock(TaskMapper.class);
        service = spy(new TaskServiceImpl(subTaskMapper, moduleMapper, reviewPort,
                timelineMapper, attachmentMapper, agentInboxService,
                agentService, subTaskService, teamService));
        ReflectionTestUtils.setField(service, "baseMapper", taskMapper);
    }

    private Team team(TeamStatus status) {
        Team t = new Team();
        t.setId(TEAM_ID);
        t.setStatus(status);
        return t;
    }

    private TeamMember member(Long id, Long agentId, AgentRole role) {
        TeamMember m = new TeamMember();
        m.setId(id);
        m.setAgentId(agentId);
        m.setSlotRole(role);
        m.setWeight(100);
        return m;
    }

    @Test
    @DisplayName("无 teamId：policy 直通，不触 Team 查询（零开销）")
    void withoutTeamIdPassThrough() {
        Map<String, Object> policy = Map.of("difficulty", "MEDIUM");
        service.createTask("t", "d", null, policy, null);

        verify(teamService, never()).getTeam(any());
        verify(teamService, never()).listMembers(any());
        verify(taskMapper).insert(any(Task.class));
    }

    @Test
    @DisplayName("有 teamId + Team ACTIVE + 成员：展开为槽位快照落库")
    void withActiveTeamExpands() {
        Map<String, Object> policy = Map.of(TaskAgentPolicy.KEY_TEAM_ID, TEAM_ID);
        when(teamService.getTeam(TEAM_ID)).thenReturn(team(TeamStatus.ACTIVE));
        when(teamService.listMembers(TEAM_ID)).thenReturn(List.of(
                member(1L, 101L, AgentRole.EXECUTOR),
                member(2L, 102L, AgentRole.EXECUTOR),
                member(3L, 103L, AgentRole.PLANNER),
                member(4L, 104L, AgentRole.REVIEWER)));

        service.createTask("t", "d", null, policy, null);

        org.mockito.ArgumentCaptor<Task> captor = org.mockito.ArgumentCaptor.forClass(Task.class);
        verify(taskMapper).insert(captor.capture());
        Map<String, Object> saved = captor.getValue().getAgentPolicy();
        assertThat(saved).containsEntry(TaskAgentPolicy.KEY_TEAM_ID, TEAM_ID);
        assertThat(saved).containsEntry(TaskAgentPolicy.KEY_EXECUTOR_AGENT_IDS, List.of(101L, 102L));
        assertThat(saved).containsEntry(TaskAgentPolicy.KEY_PLANNER_AGENT_ID, 103L);
        assertThat(saved).containsEntry(TaskAgentPolicy.KEY_REVIEWER_AGENT_ID, 104L);
    }

    @Test
    @DisplayName("有 teamId 但 Team 非 ACTIVE：展开失败，任务不落库")
    void withInactiveTeamFails() {
        Map<String, Object> policy = Map.of(TaskAgentPolicy.KEY_TEAM_ID, TEAM_ID);
        when(teamService.getTeam(TEAM_ID)).thenReturn(team(TeamStatus.DRAFT));

        assertThatThrownBy(() -> service.createTask("t", "d", null, policy, null))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("未发布");
        verify(taskMapper, never()).insert(any(Task.class));
    }

    @Test
    @DisplayName("有 teamId 但 Team 无成员：展开失败，任务不落库")
    void withEmptyTeamFails() {
        Map<String, Object> policy = Map.of(TaskAgentPolicy.KEY_TEAM_ID, TEAM_ID);
        when(teamService.getTeam(TEAM_ID)).thenReturn(team(TeamStatus.ACTIVE));
        when(teamService.listMembers(TEAM_ID)).thenReturn(List.of());

        assertThatThrownBy(() -> service.createTask("t", "d", null, policy, null))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("无成员");
        verify(taskMapper, never()).insert(any(Task.class));
    }
}
