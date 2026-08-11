package com.helloai.core.agent.mcp;

import com.helloai.common.constant.AgentStatus;
import com.helloai.core.agent.command.ExecutionResultHandler;
import com.helloai.core.agent.entity.Agent;
import com.helloai.core.agent.entity.AgentDutyLease;
import com.helloai.core.agent.entity.AgentInbox;
import com.helloai.core.agent.observability.HeartbeatService;
import com.helloai.core.agent.service.AgentDutyLeaseService;
import com.helloai.core.agent.service.AgentInboxService;
import com.helloai.core.agent.service.AgentMcpServerService;
import com.helloai.core.agent.service.AgentService;
import com.helloai.core.system.service.AttachmentService;
import com.helloai.core.task.entity.SubTask;
import com.helloai.core.task.mapper.SubTaskMapper;
import com.helloai.core.task.service.SubTaskService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

/**
 * A0-1（§6.60）pullTasks 撤销标记单元测试：
 * 曾分配给我但已转移的子任务打 reassigned=true + currentAgentId，未转移不打标。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("McpToolService.pullTasks 撤销标记（A0-1）")
class McpToolServiceTest {

    private static final long AGENT_ID = 1L;
    private static final long SUB_TASK_ID = 5L;
    private static final long OTHER_AGENT = 999L;

    @Mock private AgentService agentService;
    @Mock private AgentInboxService agentInboxService;
    @Mock private AgentMcpServerService agentMcpServerService;
    @Mock private SubTaskService subTaskService;
    @Mock private SubTaskMapper subTaskMapper;
    @Mock private HeartbeatService heartbeatService;
    @Mock private AttachmentService attachmentService;
    @Mock private ExecutionResultHandler executionResultHandler;
    @Mock private AgentDutyLeaseService agentDutyLeaseService;

    private McpToolService mcpToolService;

    @BeforeEach
    void setUp() {
        mcpToolService = new McpToolService(
                agentService, agentInboxService, agentMcpServerService,
                subTaskService, subTaskMapper, heartbeatService,
                attachmentService, executionResultHandler, agentDutyLeaseService);

        Agent agent = new Agent();
        agent.setId(AGENT_ID);
        agent.setStatus(AgentStatus.ACTIVE);
        lenient().when(agentService.getById(AGENT_ID)).thenReturn(agent);
        lenient().when(agentMcpServerService.isToolEnabled(eq(AGENT_ID), eq("pullTasks"))).thenReturn(true);
        lenient().when(agentMcpServerService.getParamConstraints(eq(AGENT_ID), eq("pullTasks"))).thenReturn(null);
    }

    private AgentInbox subTaskInbox(Long refId) {
        AgentInbox inbox = new AgentInbox();
        inbox.setId(refId);
        inbox.setEventType("sub_task.reassigned");
        inbox.setTitle("任务已改派");
        inbox.setRefType("sub_task");
        inbox.setRefId(refId);
        inbox.setPriority("HIGH");
        return inbox;
    }

    private SubTask subTask(Long assignedAgentId) {
        SubTask subTask = new SubTask();
        subTask.setId(SUB_TASK_ID);
        subTask.setAssignedAgentId(assignedAgentId);
        return subTask;
    }

    @Test
    @DisplayName("子任务已转移给其他 Agent：reassigned=true + currentAgentId 指向新执行者")
    void shouldMarkReassignedWhenAgentChanged() {
        when(agentInboxService.getUnread(AGENT_ID, 10)).thenReturn(List.of(subTaskInbox(SUB_TASK_ID)));
        when(subTaskService.getById(SUB_TASK_ID)).thenReturn(subTask(OTHER_AGENT));

        McpToolService.PullTasksResult result = mcpToolService.pullTasks(AGENT_ID, "EXECUTOR", 10);

        McpToolService.PullTasksResult.Message msg = result.getMessages().get(0);
        assertThat(msg.getReassigned()).isTrue();
        assertThat(msg.getCurrentAgentId()).isEqualTo(OTHER_AGENT);
        // 常规字段不受影响
        assertThat(msg.getSubTaskId()).isEqualTo(SUB_TASK_ID);
        assertThat(msg.getType()).isEqualTo("sub_task.reassigned");
    }

    @Test
    @DisplayName("子任务仍在本 Agent 名下：reassigned 保持 null（不下发标记）")
    void shouldNotMarkWhenAgentUnchanged() {
        when(agentInboxService.getUnread(AGENT_ID, 10)).thenReturn(List.of(subTaskInbox(SUB_TASK_ID)));
        when(subTaskService.getById(SUB_TASK_ID)).thenReturn(subTask(AGENT_ID));

        McpToolService.PullTasksResult result = mcpToolService.pullTasks(AGENT_ID, "EXECUTOR", 10);

        McpToolService.PullTasksResult.Message msg = result.getMessages().get(0);
        assertThat(msg.getReassigned()).isNull();
        assertThat(msg.getCurrentAgentId()).isNull();
    }

    @Test
    @DisplayName("子任务执行者已清空（回收）：reassigned=true + currentAgentId 为 null")
    void shouldMarkReassignedWhenAgentCleared() {
        when(agentInboxService.getUnread(AGENT_ID, 10)).thenReturn(List.of(subTaskInbox(SUB_TASK_ID)));
        when(subTaskService.getById(SUB_TASK_ID)).thenReturn(subTask(null));

        McpToolService.PullTasksResult result = mcpToolService.pullTasks(AGENT_ID, "EXECUTOR", 10);

        McpToolService.PullTasksResult.Message msg = result.getMessages().get(0);
        assertThat(msg.getReassigned()).isTrue();
        assertThat(msg.getCurrentAgentId()).isNull();
    }
}
