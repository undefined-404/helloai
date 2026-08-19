package com.helloai.core.task.service;

import com.baomidou.mybatisplus.extension.conditions.query.LambdaQueryChainWrapper;
import com.helloai.common.constant.SubTaskStatus;
import com.helloai.common.constant.TaskStatus;
import com.helloai.core.agent.mapper.AgentExecutionRecordMapper;
import com.helloai.core.agent.mapper.AgentInboxMapper;
import com.helloai.core.agent.mapper.AgentMapper;
import com.helloai.core.agent.mapper.ConversationArchiveMapper;
import com.helloai.core.agent.mapper.ConversationMessageMapper;
import com.helloai.core.agent.service.AgentInboxService;
import com.helloai.core.system.mapper.AttachmentMapper;
import com.helloai.core.system.mapper.ModuleMapper;
import com.helloai.core.task.entity.SubTask;
import com.helloai.core.task.entity.Task;
import com.helloai.core.task.entity.TaskTimeline;
import com.helloai.core.task.mapper.ReviewRecordMapper;
import com.helloai.core.task.mapper.SubTaskMapper;
import com.helloai.core.task.mapper.TaskTimelineMapper;
import com.helloai.core.task.service.impl.TaskServiceImpl;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;

/**
 * TaskService 创建/更新单测（A1，V47 收尾）。
 *
 * <p>回归背景：agentPolicy/requiredSkills 此前无法经任务创建/编辑透传落库，
 * A1 补 createTask 五参重载与 updateTask 六参扩展。本测试验证 policy 随创建落库，
 * 以及更新侧"null=不更新（MP NOT_NULL 策略跳过）、空集合=显式清空"的语义。</p>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("TaskService 创建/更新与执行策略透传")
class TaskServiceTest {

    @Mock
    private SubTaskMapper subTaskMapper;
    @Mock
    private ModuleMapper moduleMapper;
    @Mock
    private ReviewRecordMapper reviewRecordMapper;
    @Mock
    private AgentExecutionRecordMapper agentExecutionRecordMapper;
    @Mock
    private AgentInboxMapper agentInboxMapper;
    @Mock
    private TaskTimelineMapper taskTimelineMapper;
    @Mock
    private AttachmentMapper attachmentMapper;
    @Mock
    private ConversationArchiveMapper conversationArchiveMapper;
    @Mock
    private ConversationMessageMapper conversationMessageMapper;
    @Mock
    private AgentMapper agentMapper;
    @Mock
    private AgentInboxService agentInboxService;
    @Mock
    private SubTaskService subTaskService;
    @Mock
    private LambdaQueryChainWrapper<SubTask> subTaskChain;

    private TaskService newSpyService() {
        return spy(new TaskServiceImpl(subTaskMapper, moduleMapper, reviewRecordMapper,
                agentExecutionRecordMapper, agentInboxMapper, taskTimelineMapper,
                attachmentMapper, conversationArchiveMapper, conversationMessageMapper,
                agentMapper, agentInboxService, subTaskService));
    }

    private static Map<String, Object> policy() {
        Map<String, Object> policy = new LinkedHashMap<>();
        policy.put("plannerAgentId", 101L);
        policy.put("executorAgentIds", List.of(201L, 202L));
        policy.put("reviewerAgentId", 301L);
        policy.put("fallbackPolicy", "RESTRICTED");
        policy.put("difficulty", "HIGH");
        return policy;
    }

    @Test
    @DisplayName("createTask 五参：policy/requiredSkills/slaMinutes 随任务落库，状态 PENDING")
    void shouldCreateTaskWithPolicy() {
        TaskService service = newSpyService();
        doReturn(true).when(service).save(any(Task.class));

        Task result = service.createTask("带策略任务", "描述", 120, policy(), List.of("shell", "docker"));

        assertThat(result.getStatus()).isEqualTo(TaskStatus.PENDING);
        assertThat(result.getSlaMinutes()).isEqualTo(120);
        assertThat(result.getAgentPolicy())
                .containsEntry("plannerAgentId", 101L)
                .containsEntry("fallbackPolicy", "RESTRICTED")
                .containsEntry("difficulty", "HIGH");
        assertThat(result.getRequiredSkills()).containsExactly("shell", "docker");
        ArgumentCaptor<Task> captor = ArgumentCaptor.forClass(Task.class);
        verify(service).save(captor.capture());
        assertThat(captor.getValue()).isSameAs(result);
    }

    @Test
    @DisplayName("createTask 三参旧入口：policy/requiredSkills/slaMinutes 均不设置")
    void shouldCreateTaskWithoutPolicy() {
        TaskService service = newSpyService();
        doReturn(true).when(service).save(any(Task.class));

        Task result = service.createTask("普通任务", "描述", null);

        assertThat(result.getStatus()).isEqualTo(TaskStatus.PENDING);
        assertThat(result.getAgentPolicy()).isNull();
        assertThat(result.getRequiredSkills()).isNull();
        assertThat(result.getSlaMinutes()).isNull();
    }

    @Test
    @DisplayName("updateTask 六参：policy 空 Map / requiredSkills 空列表 = 显式清空")
    void shouldClearPolicyOnEmptyCollections() {
        TaskService service = newSpyService();
        Task existing = new Task();
        existing.setId(9L);
        existing.setAgentPolicy(policy());
        existing.setRequiredSkills(List.of("shell"));
        existing.setSlaMinutes(60);
        doReturn(existing).when(service).getById(9L);
        doReturn(true).when(service).updateById(any(Task.class));

        Task result = service.updateTask(9L, "新标题", "新描述", null, Map.of(), List.of());

        assertThat(result).isSameAs(existing);
        assertThat(result.getTitle()).isEqualTo("新标题");
        assertThat(result.getAgentPolicy()).isEmpty();
        assertThat(result.getRequiredSkills()).isEmpty();
        ArgumentCaptor<Task> captor = ArgumentCaptor.forClass(Task.class);
        verify(service).updateById(captor.capture());
        assertThat(captor.getValue().getAgentPolicy()).isEmpty();
        assertThat(captor.getValue().getRequiredSkills()).isEmpty();
        // slaMinutes 传 null：MP NOT_NULL 策略跳过该字段，实体上保持原值（不覆盖）
        assertThat(captor.getValue().getSlaMinutes()).isEqualTo(60);
    }

    @Test
    @DisplayName("updateTask 六参：null 字段随实体原值（保持现状），仅非 null 字段更新")
    void shouldKeepExistingValuesForNullFields() {
        TaskService service = newSpyService();
        Task existing = new Task();
        existing.setId(9L);
        existing.setAgentPolicy(policy());
        existing.setRequiredSkills(List.of("shell"));
        existing.setSlaMinutes(60);
        doReturn(existing).when(service).getById(9L);
        doReturn(true).when(service).updateById(any(Task.class));

        Task result = service.updateTask(9L, "新标题", "新描述", null, null, null);

        assertThat(result.getTitle()).isEqualTo("新标题");
        assertThat(result.getAgentPolicy()).containsEntry("plannerAgentId", 101L);
        assertThat(result.getRequiredSkills()).containsExactly("shell");
        assertThat(result.getSlaMinutes()).isEqualTo(60);
        ArgumentCaptor<Task> captor = ArgumentCaptor.forClass(Task.class);
        verify(service).updateById(captor.capture());
        assertThat(captor.getValue().getAgentPolicy()).containsEntry("plannerAgentId", 101L);
        assertThat(captor.getValue().getRequiredSkills()).containsExactly("shell");
        assertThat(captor.getValue().getSlaMinutes()).isEqualTo(60);
    }

    @Test
    @DisplayName("updateTask：任务不存在返回 null，不触发落库")
    void shouldReturnNullWhenTaskMissing() {
        TaskService service = newSpyService();
        doReturn(null).when(service).getById(999L);

        Task result = service.updateTask(999L, "标题", "描述", null, null, null);

        assertThat(result).isNull();
        verify(service, never()).updateById(any(Task.class));
    }

    @Test
    @DisplayName("updateStatus CANCELLED：级联取消全部未终态子任务并记录 timeline")
    void shouldCancelSubTasksWhenTaskCancelled() {
        TaskService service = newSpyService();
        Task existing = new Task();
        existing.setId(7L);
        existing.setStatus(TaskStatus.PENDING);
        doReturn(existing).when(service).getById(7L);
        doReturn(true).when(service).updateById(any(Task.class));

        SubTask done = new SubTask();
        done.setId(1L);
        done.setStatus(SubTaskStatus.DONE);
        SubTask pending = new SubTask();
        pending.setId(2L);
        pending.setStatus(SubTaskStatus.PENDING);
        SubTask cancelled = new SubTask();
        cancelled.setId(3L);
        cancelled.setStatus(SubTaskStatus.CANCELLED);
        doReturn(subTaskChain).when(subTaskService).lambdaQuery();
        doReturn(subTaskChain).when(subTaskChain).eq(any(), any());
        doReturn(List.of(done, pending, cancelled)).when(subTaskChain).list();

        Task result = service.updateStatus(7L, TaskStatus.CANCELLED);

        assertThat(result.getStatus()).isEqualTo(TaskStatus.CANCELLED);
        // 仅未终态子任务被级联取消（DONE/CANCELLED 跳过）
        verify(subTaskService).changeStatus(eq(2L), eq(SubTaskStatus.CANCELLED), isNull(), anyMap());
        verify(subTaskService, never()).changeStatus(eq(1L), any(), any(), any());
        verify(subTaskService, never()).changeStatus(eq(3L), any(), any(), any());
        ArgumentCaptor<TaskTimeline> tlCaptor = ArgumentCaptor.forClass(TaskTimeline.class);
        verify(taskTimelineMapper).insert(tlCaptor.capture());
        assertThat(tlCaptor.getValue().getEventType()).isEqualTo("task_cancelled");
        assertThat(tlCaptor.getValue().getPayload()).containsEntry("cancelledSubTaskCount", 1);
    }

    @Test
    @DisplayName("updateStatus 非 CANCELLED：仅改状态，不级联子任务、不写 timeline")
    void shouldNotCancelSubTasksForOtherStatus() {
        TaskService service = newSpyService();
        Task existing = new Task();
        existing.setId(8L);
        existing.setStatus(TaskStatus.PENDING);
        doReturn(existing).when(service).getById(8L);
        doReturn(true).when(service).updateById(any(Task.class));

        Task result = service.updateStatus(8L, TaskStatus.DONE);

        assertThat(result.getStatus()).isEqualTo(TaskStatus.DONE);
        verify(subTaskService, never()).lambdaQuery();
        verify(taskTimelineMapper, never()).insert(any(TaskTimeline.class));
    }

    @Test
    @DisplayName("updateStatus：任务不存在返回 null，不触发落库与级联")
    void shouldReturnNullWhenStatusUpdateTaskMissing() {
        TaskService service = newSpyService();
        doReturn(null).when(service).getById(999L);

        Task result = service.updateStatus(999L, TaskStatus.CANCELLED);

        assertThat(result).isNull();
        verify(service, never()).updateById(any(Task.class));
        verify(subTaskService, never()).lambdaQuery();
    }
}
