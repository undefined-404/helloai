package com.helloai.core.task.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.helloai.common.base.BizException;
import com.helloai.common.constant.AgentRole;
import com.helloai.common.constant.TeamStatus;
import com.helloai.core.agent.port.TeamMemberView;
import com.helloai.core.agent.service.AgentInboxService;
import com.helloai.core.agent.service.AgentService;
import com.helloai.core.agent.service.TeamService;
import com.helloai.core.task.entity.Task;
import com.helloai.core.task.mapper.AttachmentMapper;
import com.helloai.core.task.mapper.ModuleMapper;
import com.helloai.core.task.mapper.SubTaskMapper;
import com.helloai.core.task.mapper.TaskAgentMemberMapper;
import com.helloai.core.task.mapper.TaskExecutionRecordMapper;
import com.helloai.core.task.mapper.TaskIterationMapper;
import com.helloai.core.task.mapper.TaskMapper;
import com.helloai.core.task.mapper.TaskRunningSpecMapper;
import com.helloai.core.task.mapper.TaskTimelineMapper;
import com.helloai.core.task.workflow.mapper.WorkflowInstanceMapper;
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
    private SubTaskMapper subTaskMapper;
    private TeamService teamService;
    private TaskIterationMapper taskIterationMapper;
    private WorkflowInstanceMapper workflowInstanceMapper;
    private AgentService agentService;
    private TaskServiceImpl service;

    @BeforeEach
    void setUp() {
        subTaskMapper = mock(SubTaskMapper.class);
        ModuleMapper moduleMapper = mock(ModuleMapper.class);
        // P2-4：级联删除新增三张 task 子表（Running Spec / 执行记录 / 成员）
        TaskRunningSpecMapper taskRunningSpecMapper = mock(TaskRunningSpecMapper.class);
        TaskExecutionRecordMapper taskExecutionRecordMapper = mock(TaskExecutionRecordMapper.class);
        TaskAgentMemberMapper taskAgentMemberMapper = mock(TaskAgentMemberMapper.class);
        // D-1：级联删除新增两张 task 域子表（迭代记录 / 工作流实例）
        taskIterationMapper = mock(TaskIterationMapper.class);
        workflowInstanceMapper = mock(WorkflowInstanceMapper.class);
        ReviewPort reviewPort = mock(ReviewPort.class);
        TaskTimelineMapper timelineMapper = mock(TaskTimelineMapper.class);
        AttachmentMapper attachmentMapper = mock(AttachmentMapper.class);
        AgentInboxService agentInboxService = mock(AgentInboxService.class);
        agentService = mock(AgentService.class);
        com.helloai.core.task.service.SubTaskService subTaskService =
                mock(com.helloai.core.task.service.SubTaskService.class);
        teamService = mock(TeamService.class);
        taskMapper = mock(TaskMapper.class);
        service = spy(new TaskServiceImpl(subTaskMapper, moduleMapper,
                taskRunningSpecMapper, taskExecutionRecordMapper, taskAgentMemberMapper,
                taskIterationMapper, workflowInstanceMapper,
                reviewPort, timelineMapper, attachmentMapper, agentInboxService,
                agentService, subTaskService, teamService));
        ReflectionTestUtils.setField(service, "baseMapper", taskMapper);
    }

    private TeamMemberView member(Long id, Long agentId, AgentRole role) {
        return new TeamMemberView(id, agentId, role, 100);
    }

    @Test
    @DisplayName("无 teamId：policy 直通，不触 Team 查询（零开销）")
    void withoutTeamIdPassThrough() {
        Map<String, Object> policy = Map.of("difficulty", "MEDIUM");
        service.createTask("t", "d", null, policy, null);

        verify(teamService, never()).getTeamStatus(any());
        verify(teamService, never()).listMemberViews(any());
        verify(taskMapper).insert(any(Task.class));
    }

    @Test
    @DisplayName("有 teamId + Team ACTIVE + 成员：展开为槽位快照落库")
    void withActiveTeamExpands() {
        Map<String, Object> policy = Map.of(TaskAgentPolicy.KEY_TEAM_ID, TEAM_ID);
        when(teamService.getTeamStatus(TEAM_ID)).thenReturn(TeamStatus.ACTIVE);
        when(teamService.listMemberViews(TEAM_ID)).thenReturn(List.of(
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
        when(teamService.getTeamStatus(TEAM_ID)).thenReturn(TeamStatus.DRAFT);

        assertThatThrownBy(() -> service.createTask("t", "d", null, policy, null))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("未发布");
        verify(taskMapper, never()).insert(any(Task.class));
    }

    @Test
    @DisplayName("有 teamId 但 Team 无成员：展开失败，任务不落库")
    void withEmptyTeamFails() {
        Map<String, Object> policy = Map.of(TaskAgentPolicy.KEY_TEAM_ID, TEAM_ID);
        when(teamService.getTeamStatus(TEAM_ID)).thenReturn(TeamStatus.ACTIVE);
        when(teamService.listMemberViews(TEAM_ID)).thenReturn(List.of());

        assertThatThrownBy(() -> service.createTask("t", "d", null, policy, null))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("无成员");
        verify(taskMapper, never()).insert(any(Task.class));
    }

    @Test
    @DisplayName("O-1：级联删除 confirmTitle 不匹配 → BizException(400) 且不触达任何物理删除")
    void deleteTaskCascadeTitleMismatchIsBadRequest() {
        Long taskId = 9_900_001L;
        Task task = new Task();
        task.setId(taskId);
        task.setTitle("周报统计聚合服务");
        when(taskMapper.selectById(taskId)).thenReturn(task);

        assertThatThrownBy(() -> service.deleteTaskCascade(taskId, "错误标题"))
                .isInstanceOf(BizException.class)
                .hasMessage("任务标题不匹配")
                .satisfies(e -> assertThat(((BizException) e).getCode()).isEqualTo(400));

        // 校验失败必须先于任何级联清理：本体删除与外键子表删除均不得发生
        verify(taskMapper, never()).physicalDeleteById(any());
        verify(subTaskMapper, never()).physicalDeleteByTaskId(any());
    }

    @Test
    @DisplayName("O-1：级联删除 confirmTitle 匹配 → 正常进入删除链（不抛异常）")
    void deleteTaskCascadeTitleMatchProceeds() {
        Long taskId = 9_900_002L;
        Task task = new Task();
        task.setId(taskId);
        task.setTitle("周报统计聚合服务");
        when(taskMapper.selectById(taskId)).thenReturn(task);

        Map<String, Object> counts = service.deleteTaskCascade(taskId, "周报统计聚合服务");

        assertThat(counts).isNotNull();
        verify(taskMapper).physicalDeleteById(taskId);
        // D-1：task 域漏删子表已补齐（迭代记录 / 工作流实例）
        verify(taskIterationMapper).deleteByTaskId(taskId);
        verify(workflowInstanceMapper).physicalDeleteByTaskId(taskId);
        // D-1：agent 域漏删表经 physicalDeleteTaskTrace 收口（此处仅验证收口被调用一次）
        verify(agentService).physicalDeleteTaskTrace(taskId);
    }
}
