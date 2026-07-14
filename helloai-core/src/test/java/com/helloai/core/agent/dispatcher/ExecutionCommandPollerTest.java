package com.helloai.core.agent.dispatcher;

import com.helloai.common.config.AgentExecutionProperties;
import com.helloai.common.constant.AgentAccessType;
import com.helloai.common.constant.ExecutionStatus;
import com.helloai.core.agent.domain.ExecutionCommand;
import com.helloai.core.agent.mqconsumer.ExecutionCommandConsumer;
import com.helloai.core.entity.AgentExecutionRecord;
import com.helloai.core.entity.SubTask;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import com.helloai.core.service.AgentExecutionRecordService;
import com.helloai.core.service.SubTaskService;
import com.helloai.core.service.TaskTimelineService;

/**
 * {@link ExecutionCommandPoller} 单元测试。
 *
 * <p>覆盖：</p>
 * <ul>
 *     <li>正常兜底扫描 → markPolled → 构造 ExecutionCommand → consume；</li>
 *     <li>无孤儿记录 → 不触发任何 consume；</li>
 *     <li>孤儿记录缺关键字段 → 跳过 consume；</li>
 *     <li>subTask=null → 跳过 timeline 但仍触发 consume；</li>
 *     <li>consume 抛异常 → 不影响后续记录；</li>
 *     <li>pollerEnabled=false → 跳过扫描；</li>
 *     <li>trigger 前缀正确（poll-recovery: + 原 trigger）。</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
class ExecutionCommandPollerTest {

    @Mock
    private AgentExecutionRecordService agentExecutionRecordService;
    @Mock
    private ExecutionCommandConsumer executionCommandConsumer;
    @Mock
    private TaskTimelineService taskTimelineService;
    @Mock
    private SubTaskService subTaskService;
    @Mock
    private AgentExecutionProperties executionProperties;

    @InjectMocks
    private ExecutionCommandPoller poller;

    @BeforeEach
    void setUp() {
        // lenient: 部分测试（如 shouldSkipEntirePollWhenDisabled）不一定会用到所有 stub
        lenient().when(executionProperties.isPollerEnabled()).thenReturn(true);
        lenient().when(executionProperties.getPollerOrphanThresholdSeconds()).thenReturn(60);
        lenient().when(executionProperties.getPollerBatchSize()).thenReturn(20);
        lenient().when(executionProperties.getPollerIntervalMs()).thenReturn(1000L);
        lenient().when(executionProperties.isPollerMain()).thenReturn(false);
        lenient().when(executionProperties.getConsumerMode()).thenReturn(AgentExecutionProperties.ConsumerMode.EVENT);
    }

    private AgentExecutionRecord orphanRecord(Long recordId, Long subTaskId, Long agentId,
                                              AgentAccessType accessType, String trigger) {
        AgentExecutionRecord record = new AgentExecutionRecord();
        record.setId(recordId);
        record.setEventId("evt-" + recordId);
        record.setSubTaskId(subTaskId);
        record.setAgentId(agentId);
        record.setAccessType(accessType);
        record.setTrigger(trigger);
        record.setStatus(ExecutionStatus.PENDING);
        record.setCreateTime(OffsetDateTime.now().minusMinutes(2));
        return record;
    }

    @Nested
    @DisplayName("HappyPath — 正常兜底扫描")
    class HappyPath {

        @Test
        @DisplayName("单条孤儿记录：markPolled + 构造命令 + 触发 consume + 记录 timeline")
        void shouldProcessSingleOrphan() {
            AgentExecutionRecord orphan = orphanRecord(101L, 22L, 11L, AgentAccessType.API_KEY_LLM, "assigned");
            when(agentExecutionRecordService.listOrphanPending(60, 20)).thenReturn(List.of(orphan));
            SubTask subTask = new SubTask();
            subTask.setId(22L);
            subTask.setTaskId(33L);
            when(subTaskService.getById(22L)).thenReturn(subTask);

            poller.poll();

            verify(agentExecutionRecordService).markPolled(101L);
            verify(taskTimelineService).recordEvent(
                    eq(33L), eq(22L), eq("sub_task_execution_command_poll_recovery"),
                    any(), eq(11L), any());
            ArgumentCaptor<ExecutionCommand> commandCaptor = ArgumentCaptor.forClass(ExecutionCommand.class);
            verify(executionCommandConsumer).consume(commandCaptor.capture());
            ExecutionCommand command = commandCaptor.getValue();
            assertThat(command.getRecordId()).isEqualTo(101L);
            assertThat(command.getEventId()).isEqualTo("evt-101");
            assertThat(command.getSubTaskId()).isEqualTo(22L);
            assertThat(command.getAgentId()).isEqualTo(11L);
            assertThat(command.getAccessType()).isEqualTo(AgentAccessType.API_KEY_LLM);
            assertThat(command.getTrigger()).isEqualTo("poll-recovery:assigned");
        }

        @Test
        @DisplayName("多条孤儿记录：依次 markPolled + 触发 consume，互不影响")
        void shouldProcessMultipleOrphans() {
            AgentExecutionRecord orphan1 = orphanRecord(101L, 22L, 11L, AgentAccessType.API_KEY_LLM, "assigned");
            AgentExecutionRecord orphan2 = orphanRecord(102L, 23L, 12L, AgentAccessType.API_KEY_LLM, "reassigned");
            AgentExecutionRecord orphan3 = orphanRecord(103L, 24L, 13L, AgentAccessType.API_KEY_LLM, "retry");
            when(agentExecutionRecordService.listOrphanPending(60, 20))
                    .thenReturn(List.of(orphan1, orphan2, orphan3));
            when(subTaskService.getById(anyLong())).thenReturn(null);

            poller.poll();

            verify(agentExecutionRecordService).markPolled(101L);
            verify(agentExecutionRecordService).markPolled(102L);
            verify(agentExecutionRecordService).markPolled(103L);
            verify(executionCommandConsumer, times(3)).consume(any(ExecutionCommand.class));
        }

        @Test
        @DisplayName("trigger=null 时 fallback 为 unknown，仍然构造 poll-recovery:unknown 命令")
        void shouldFallbackTriggerWhenNull() {
            AgentExecutionRecord orphan = orphanRecord(101L, 22L, 11L, AgentAccessType.API_KEY_LLM, null);
            when(agentExecutionRecordService.listOrphanPending(60, 20)).thenReturn(List.of(orphan));
            when(subTaskService.getById(22L)).thenReturn(null);

            poller.poll();

            ArgumentCaptor<ExecutionCommand> commandCaptor = ArgumentCaptor.forClass(ExecutionCommand.class);
            verify(executionCommandConsumer).consume(commandCaptor.capture());
            assertThat(commandCaptor.getValue().getTrigger()).isEqualTo("poll-recovery:unknown");
        }
    }

    @Nested
    @DisplayName("SkipPath — 跳过消费")
    class SkipPath {

        @Test
        @DisplayName("无孤儿记录：不调用 consume、不记录 timeline、不 markPolled")
        void shouldNotConsumeWhenNoOrphans() {
            when(agentExecutionRecordService.listOrphanPending(60, 20)).thenReturn(List.of());

            poller.poll();

            verifyNoInteractions(executionCommandConsumer, taskTimelineService);
            verify(agentExecutionRecordService, never()).markPolled(anyLong());
        }

        @Test
        @DisplayName("孤儿记录缺 subTaskId：跳过 consume，不抛异常")
        void shouldSkipWhenSubTaskIdMissing() {
            AgentExecutionRecord orphan = orphanRecord(101L, null, 11L, AgentAccessType.API_KEY_LLM, "assigned");
            when(agentExecutionRecordService.listOrphanPending(60, 20)).thenReturn(List.of(orphan));

            poller.poll();

            verify(agentExecutionRecordService).markPolled(101L);
            verify(executionCommandConsumer, never()).consume(any(ExecutionCommand.class));
            verifyNoInteractions(taskTimelineService);
        }

        @Test
        @DisplayName("孤儿记录缺 agentId：跳过 consume")
        void shouldSkipWhenAgentIdMissing() {
            AgentExecutionRecord orphan = orphanRecord(101L, 22L, null, AgentAccessType.API_KEY_LLM, "assigned");
            when(agentExecutionRecordService.listOrphanPending(60, 20)).thenReturn(List.of(orphan));

            poller.poll();

            verify(agentExecutionRecordService).markPolled(101L);
            verify(executionCommandConsumer, never()).consume(any(ExecutionCommand.class));
        }

        @Test
        @DisplayName("孤儿记录缺 accessType：跳过 consume")
        void shouldSkipWhenAccessTypeMissing() {
            AgentExecutionRecord orphan = orphanRecord(101L, 22L, 11L, null, "assigned");
            when(agentExecutionRecordService.listOrphanPending(60, 20)).thenReturn(List.of(orphan));

            poller.poll();

            verify(agentExecutionRecordService).markPolled(101L);
            verify(executionCommandConsumer, never()).consume(any(ExecutionCommand.class));
        }

        @Test
        @DisplayName("subTask=null：跳过 timeline，但仍触发 consume（用兜底逻辑接管）")
        void shouldConsumeEvenWhenSubTaskIsNull() {
            AgentExecutionRecord orphan = orphanRecord(101L, 22L, 11L, AgentAccessType.API_KEY_LLM, "assigned");
            when(agentExecutionRecordService.listOrphanPending(60, 20)).thenReturn(List.of(orphan));
            when(subTaskService.getById(22L)).thenReturn(null);

            poller.poll();

            verify(agentExecutionRecordService).markPolled(101L);
            verifyNoInteractions(taskTimelineService);
            verify(executionCommandConsumer).consume(any(ExecutionCommand.class));
        }

        @Test
        @DisplayName("poller-enabled=false：跳过整个扫描流程")
        void shouldSkipEntirePollWhenDisabled() {
            when(executionProperties.isPollerEnabled()).thenReturn(false);

            poller.poll();

            verifyNoInteractions(agentExecutionRecordService);
            verifyNoInteractions(executionCommandConsumer);
        }

        @Test
        @DisplayName("consume 抛异常：不中断本批次其他记录的扫描")
        void shouldContinueWhenConsumeThrows() {
            AgentExecutionRecord orphan1 = orphanRecord(101L, 22L, 11L, AgentAccessType.API_KEY_LLM, "assigned");
            AgentExecutionRecord orphan2 = orphanRecord(102L, 23L, 12L, AgentAccessType.API_KEY_LLM, "assigned");
            when(agentExecutionRecordService.listOrphanPending(60, 20))
                    .thenReturn(List.of(orphan1, orphan2));
            when(subTaskService.getById(anyLong())).thenReturn(null);
            // 让第一条 consume 抛异常
            doThrow(new RuntimeException("模拟 LLM 异常"))
                    .when(executionCommandConsumer).consume(argThat(c -> c != null && c.getRecordId() != null && c.getRecordId().equals(101L)));

            poller.poll();

            verify(agentExecutionRecordService).markPolled(101L);
            verify(agentExecutionRecordService).markPolled(102L);
            verify(executionCommandConsumer, times(2)).consume(any(ExecutionCommand.class));
        }

        @Test
        @DisplayName("listOrphanPending 抛异常：异常向上抛出（poll 调度线程的失败由调度框架处理）")
        void shouldPropagateListOrphanPendingException() {
            when(agentExecutionRecordService.listOrphanPending(anyInt(), anyInt()))
                    .thenThrow(new RuntimeException("DB 异常"));

            assertThrows(RuntimeException.class, () -> poller.poll());

            verifyNoInteractions(executionCommandConsumer);
        }
    }

    @Nested
    @DisplayName("PollerMain — POLLER/BOTH 模式走 listAllPending")
    class PollerMain {

        @Test
        @DisplayName("POLLER 模式：调 listAllPending，listOrphanPending 不被调")
        void shouldUseListAllPendingInPollerMode() {
            when(executionProperties.isPollerMain()).thenReturn(true);
            when(executionProperties.getConsumerMode()).thenReturn(AgentExecutionProperties.ConsumerMode.POLLER);
            AgentExecutionRecord pending = orphanRecord(201L, 22L, 11L, AgentAccessType.API_KEY_LLM, "assigned");
            when(agentExecutionRecordService.listAllPending(20)).thenReturn(List.of(pending));
            SubTask subTask = new SubTask();
            subTask.setId(22L);
            subTask.setTaskId(33L);
            when(subTaskService.getById(22L)).thenReturn(subTask);

            poller.poll();

            verify(agentExecutionRecordService).listAllPending(20);
            verify(agentExecutionRecordService, never()).listOrphanPending(anyInt(), anyInt());
            verify(agentExecutionRecordService).markPolled(201L);
            verify(executionCommandConsumer).consume(any(ExecutionCommand.class));
        }

        @Test
        @DisplayName("BOTH 模式：同样调 listAllPending（与事件消费者并行）")
        void shouldUseListAllPendingInBothMode() {
            lenient().when(executionProperties.isPollerMain()).thenReturn(true);
            lenient().when(executionProperties.getConsumerMode()).thenReturn(AgentExecutionProperties.ConsumerMode.BOTH);
            when(agentExecutionRecordService.listAllPending(20)).thenReturn(List.of());

            poller.poll();

            verify(agentExecutionRecordService).listAllPending(20);
            verify(agentExecutionRecordService, never()).listOrphanPending(anyInt(), anyInt());
            verifyNoInteractions(executionCommandConsumer);
        }

        @Test
        @DisplayName("POLLER 模式 + 长 interval：记录 warning 但不阻断流程")
        void shouldWarnOnLongIntervalInPollerMode() {
            when(executionProperties.isPollerMain()).thenReturn(true);
            lenient().when(executionProperties.getConsumerMode()).thenReturn(AgentExecutionProperties.ConsumerMode.POLLER);
            when(executionProperties.getPollerIntervalMs()).thenReturn(10000L);
            when(agentExecutionRecordService.listAllPending(20)).thenReturn(List.of());

            poller.poll();

            // 验证没有异常，且走了 listAllPending（说明 warning 之后还是继续走主路径）
            verify(agentExecutionRecordService).listAllPending(20);
        }

        @Test
        @DisplayName("POLLER 主路径触发：trigger 前缀为 poll-main:，timeline 事件为 sub_task_execution_command_polled_main")
        void shouldUsePollMainPrefixAndTimeline() {
            when(executionProperties.isPollerMain()).thenReturn(true);
            when(executionProperties.getConsumerMode()).thenReturn(AgentExecutionProperties.ConsumerMode.POLLER);
            AgentExecutionRecord pending = orphanRecord(301L, 22L, 11L, AgentAccessType.API_KEY_LLM, "assigned");
            when(agentExecutionRecordService.listAllPending(20)).thenReturn(List.of(pending));
            SubTask subTask = new SubTask();
            subTask.setId(22L);
            subTask.setTaskId(33L);
            when(subTaskService.getById(22L)).thenReturn(subTask);

            poller.poll();

            ArgumentCaptor<ExecutionCommand> commandCaptor = ArgumentCaptor.forClass(ExecutionCommand.class);
            verify(executionCommandConsumer).consume(commandCaptor.capture());
            assertThat(commandCaptor.getValue().getTrigger()).isEqualTo("poll-main:assigned");

            ArgumentCaptor<Map<String, Object>> timelineCaptor = ArgumentCaptor.forClass(Map.class);
            verify(taskTimelineService).recordEvent(
                    eq(33L), eq(22L), eq("sub_task_execution_command_polled_main"),
                    any(), eq(11L), timelineCaptor.capture());
            @SuppressWarnings("unchecked")
            Map<String, Object> captured = timelineCaptor.getValue();
            assertThat(captured).containsEntry("scan", "listAllPending");
        }

        @Test
        @DisplayName("EVENT 模式：调 listOrphanPending，listAllPending 不被调（保留原兜底逻辑）")
        void shouldStillUseListOrphanPendingInEventMode() {
            // 默认是 EVENT 模式
            AgentExecutionRecord orphan = orphanRecord(401L, 22L, 11L, AgentAccessType.API_KEY_LLM, "assigned");
            when(agentExecutionRecordService.listOrphanPending(60, 20)).thenReturn(List.of(orphan));
            when(subTaskService.getById(22L)).thenReturn(null);

            poller.poll();

            verify(agentExecutionRecordService).listOrphanPending(60, 20);
            verify(agentExecutionRecordService, never()).listAllPending(anyInt());
            verify(executionCommandConsumer).consume(any(ExecutionCommand.class));
        }
    }
}