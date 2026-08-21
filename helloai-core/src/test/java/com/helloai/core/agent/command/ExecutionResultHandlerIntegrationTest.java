package com.helloai.core.agent.command;

import com.helloai.common.constant.AgentAccessType;
import com.helloai.common.constant.AgentOnlineStatus;
import com.helloai.common.constant.AgentRole;
import com.helloai.common.constant.AgentStatus;
import com.helloai.common.constant.SubTaskStatus;
import com.helloai.core.agent.domain.AgentResult;
import com.helloai.core.agent.entity.Agent;
import com.helloai.core.agent.service.HeartbeatService;
import com.helloai.core.agent.service.ExecutionArtifactService;
import com.helloai.core.agent.service.AgentService;
import com.helloai.core.agent.service.ConversationService;
import com.helloai.core.agent.observability.ExternalAgentFailureTracker;
import com.helloai.core.agent.output.ExecutionOutputParser;
import com.helloai.core.agent.quality.ExecutorDoneIssuesBackfiller;
import com.helloai.core.task.entity.SubTask;
import com.helloai.core.task.service.SubTaskService;
import com.helloai.core.task.service.TaskTimelineService;
import com.helloai.core.task.service.TaskRunningSpecService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * ExecutionResultHandler AOP 自死锁防护验证（§4.1  锁语义重审）。
 *
 * <p>本测试锁定 afterCommit + 主事务分离的不变量：
 * <ol>
 *   <li>{@code subTaskService.submit()} 触发链路 {@code changeStatus(REVIEW) -> heartbeatService.active()}
 *       时，{@code agent} 行锁被主事务持有</li>
 *   <li>{@code failureTracker.recordSuccess()} 必须在主事务提交后（afterCommit）执行</li>
 *   <li>active() 复用 seen() 双写后行锁更频繁，afterCommit 模式不可豁免</li>
 * </ol>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ExecutionResultHandler AOP 自死锁防护")
class ExecutionResultHandlerIntegrationTest {

    @Mock
    private SubTaskService subTaskService;

    @Mock
    private TaskTimelineService taskTimelineService;

    @Mock
    private ExternalAgentFailureTracker failureTracker;

    @Mock
    private AgentService agentService;

    @Mock
    private HeartbeatService heartbeatService;

    @Mock
    private org.springframework.context.ApplicationEventPublisher applicationEventPublisher;

    @Mock
    private ConversationService conversationService;

    @Mock
    private ExecutionArtifactService executionArtifactService;

    @Mock
    private TaskRunningSpecService taskRunningSpecService;

    @Mock
    private ExecutorDoneIssuesBackfiller executorDoneIssuesBackfiller;

    private ExecutionResultHandler handler;

    @BeforeEach
    void setUp() {
        handler = new ExecutionResultHandler(subTaskService, taskTimelineService, failureTracker, agentService,
                applicationEventPublisher, conversationService, executionArtifactService, taskRunningSpecService,
                new ExecutionOutputParser(), executorDoneIssuesBackfiller);
        // 模拟 Spring @Transactional 已开启（afterCommit 注册需要激活的同步管理器）
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.initSynchronization();
        }
    }

    @AfterEach
    void tearDown() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clear();
        }
    }

    @Test
    @DisplayName("CLI_CLIENT 成功：failureTracker.recordSuccess 必须挂在 afterCommit，不能在主事务内直调")
    void shouldRegisterFailureTrackingOnAfterCommitForCliClientSuccess() {
        // 准备：IN_PROGRESS 子任务 + CLI_CLIENT Agent + 成功结果
        SubTask subTask = new SubTask();
        subTask.setId(22L);
        subTask.setTaskId(33L);
        subTask.setAssignedAgentId(11L);
        subTask.setStatus(SubTaskStatus.IN_PROGRESS);
        when(subTaskService.getById(22L)).thenReturn(subTask);

        Agent cliAgent = cliAgent(11L);
        when(agentService.getById(11L)).thenReturn(cliAgent);

        // 模拟 submit() 后的链式回调：active() 在主事务内执行
        // （@Transactional 默认 PROPAGATION_REQUIRED，join 调用方事务）

        // 执行：handleSuccess
        AgentResult result = AgentResult.success("done", "stop", "CliExecutor", 100);
        handler.handleSuccess(22L, 11L, result);

        // 断言 1：主事务提交前，failureTracker.recordSuccess 已被注册但尚未执行
        //         （afterCommit 回调在 synchronize.afterCommit() 触发后才执行）
        verify(failureTracker, never()).recordSuccess(anyLong());

        // 断言 2：在主链路完成前，failureTracker 没有直接被调用（避免自死锁）
        //         ——失败意味着有人把 recordSuccess 挪回主事务内，会撞 agent 行锁
        verify(failureTracker, never()).recordSuccess(11L);

        // 断言 3：submit 后 timeline event 正常记录（业务主链路已完成）
        verify(taskTimelineService, times(1)).recordEvent(
                eq(33L), eq(22L), eq("sub_task_execute_submit"),
                eq(AgentRole.EXECUTOR), eq(11L), any());
    }

    @Test
    @DisplayName("CLI_CLIENT 成功：afterCommit 触发后 recordSuccess 必须被调用一次")
    void shouldInvokeRecordSuccessOnAfterCommit() {
        SubTask subTask = new SubTask();
        subTask.setId(22L);
        subTask.setTaskId(33L);
        subTask.setAssignedAgentId(11L);
        subTask.setStatus(SubTaskStatus.IN_PROGRESS);
        when(subTaskService.getById(22L)).thenReturn(subTask);
        when(agentService.getById(11L)).thenReturn(cliAgent(11L));

        // 执行
        AgentResult result = AgentResult.success("done", "stop", "CliExecutor", 100);
        handler.handleSuccess(22L, 11L, result);

        // 断言：注册了 afterCommit 同步（同步管理器的 Synchronizations 列表非空）
        // 触发 afterCommit
        TransactionSynchronizationManager.getSynchronizations().forEach(s -> s.afterCommit());

        // afterCommit 之后，failureTracker.recordSuccess 应该被调用一次
        verify(failureTracker, times(1)).recordSuccess(11L);
        verify(failureTracker, never()).recordFailure(11L);
    }

    @Test
    @DisplayName("API_KEY_LLM Agent 成功：failureTracker 不应被调用（仅 CLI_CLIENT 计数）")
    void shouldNotTrackApiKeyLlmAgent() {
        SubTask subTask = new SubTask();
        subTask.setId(22L);
        subTask.setTaskId(33L);
        subTask.setAssignedAgentId(11L);
        subTask.setStatus(SubTaskStatus.IN_PROGRESS);
        when(subTaskService.getById(22L)).thenReturn(subTask);
        Agent apiAgent = cliAgent(11L);
        apiAgent.setAccessType(AgentAccessType.API_KEY_LLM);
        when(agentService.getById(11L)).thenReturn(apiAgent);

        AgentResult result = AgentResult.success("done", "stop", "ApiKeyExecutor", 100);
        handler.handleSuccess(22L, 11L, result);

        verify(failureTracker, never()).recordSuccess(anyLong());
        verify(failureTracker, never()).recordFailure(anyLong());
    }

    @Test
    @DisplayName("CLI_CLIENT 失败：afterCommit 触发后 recordFailure 必须被调用一次")
    void shouldInvokeRecordFailureOnAfterCommit() {
        SubTask subTask = new SubTask();
        subTask.setId(22L);
        subTask.setTaskId(33L);
        subTask.setAssignedAgentId(11L);
        subTask.setStatus(SubTaskStatus.IN_PROGRESS);
        when(subTaskService.getById(22L)).thenReturn(subTask);
        when(agentService.getById(11L)).thenReturn(cliAgent(11L));

        handler.handleFailure(22L, 11L, new RuntimeException("tool timeout"));

        // afterCommit 之前不应调用
        verify(failureTracker, never()).recordFailure(anyLong());

        // 触发 afterCommit
        TransactionSynchronizationManager.getSynchronizations().forEach(s -> s.afterCommit());

        verify(failureTracker, times(1)).recordFailure(11L);
        verify(failureTracker, never()).recordSuccess(11L);
    }

    @Test
    @DisplayName("锁顺序断言：主事务内不调 failureTracker（避免自死锁）")
    void shouldNeverCallFailureTrackerInsideMainTransaction() {
        SubTask subTask = new SubTask();
        subTask.setId(22L);
        subTask.setTaskId(33L);
        subTask.setAssignedAgentId(11L);
        subTask.setStatus(SubTaskStatus.IN_PROGRESS);
        when(subTaskService.getById(22L)).thenReturn(subTask);
        when(agentService.getById(11L)).thenReturn(cliAgent(11L));

        // 执行 handleSuccess
        handler.handleSuccess(22L, 11L,
                AgentResult.success("ok", "stop", "CliExecutor", 50));

        // 关键不变量：主事务结束前 failureTracker.recordSuccess 从未被调用
        // 在测试线程内调用任何 afterCommit 之前
        InOrder order = inOrder(subTaskService, taskTimelineService, failureTracker);
        order.verify(subTaskService).getById(22L);
        order.verify(subTaskService).updateById(any(SubTask.class));
        order.verify(subTaskService).submit(22L);
        order.verify(taskTimelineService).recordEvent(
                eq(33L), eq(22L), eq("sub_task_execute_submit"),
                eq(AgentRole.EXECUTOR), eq(11L), any());
        order.verify(failureTracker, never()).recordSuccess(anyLong());
        order.verify(failureTracker, never()).recordFailure(anyLong());
    }

    private static Agent cliAgent(Long id) {
        Agent agent = new Agent();
        agent.setId(id);
        agent.setName("cli-agent-" + id);
        agent.setRole(AgentRole.EXECUTOR);
        agent.setStatus(AgentStatus.ACTIVE);
        agent.setOnlineStatus(AgentOnlineStatus.ONLINE);
        agent.setAccessType(AgentAccessType.CLI_CLIENT);
        agent.setConsecutiveFailureCount(0);
        return agent;
    }
}
