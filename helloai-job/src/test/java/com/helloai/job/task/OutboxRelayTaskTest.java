package com.helloai.job.task;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.helloai.common.config.AgentCommandOutboxRelayProperties;
import com.helloai.common.config.AgentExecutionProperties;
import com.helloai.common.constant.AgentAccessType;
import com.helloai.common.constant.AgentCommandOutboxStatus;
import com.helloai.core.agent.command.ExecutionCommandMqPublisher;
import com.helloai.core.agent.domain.ExecutionCommand;
import com.helloai.core.agent.mqconsumer.ExecutionCommandMqMessage;
import com.helloai.core.agent.entity.AgentCommandOutboxEvent;
import com.helloai.core.agent.service.AgentCommandOutboxService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Phase 2H ②a：{@link OutboxRelayTask} 的单元测试。
 *
 * <p>覆盖：</p>
 * <ol>
 *   <li>Publisher 成功 → markSent；</li>
 *   <li>Publisher 异常但 retryCount &lt; maxRetry → markFailed 并设置 nextRetryAt；</li>
 *   <li>Publisher 异常且 retryCount + 1 ≥ maxRetry → markFinalFailed；</li>
 *   <li>扫描批为空 → 零调用直接退出；</li>
 *   <li>payload 反序列化失败 → markFinalFailed（终态）。</li>
 * </ol>
 *
 * <p>Redis 锁相关通过 {@code redis.opsForValue().setIfAbsent} 模拟获取成功。</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("OutboxRelayTask (Phase 2H ②a)")
class OutboxRelayTaskTest {

    @Mock
    private AgentCommandOutboxService outboxService;

    @Mock
    private ObjectProvider<ExecutionCommandMqPublisher> mqPublisherProvider;

    @Mock
    private ExecutionCommandMqPublisher mqPublisher;

    @Mock
    private AgentCommandOutboxRelayProperties properties;

    @Mock
    private AgentExecutionProperties executionProperties;

    @Mock
    private StringRedisTemplate redis;

    @Mock
    private ValueOperations<String, String> valueOperations;

    @Mock
    private ObjectMapper objectMapper;

    private OutboxRelayTask task;

    private static final String EVENT_ID = "evt-001";
    private static final Long SUB_TASK_ID = 100L;
    private static final Long AGENT_ID = 200L;

    @BeforeEach
    void setUp() {
        // 通过 new + 反射设置 final 字段，避免被 @InjectMocks 反射检查阻断
        task = new OutboxRelayTask(outboxService, mqPublisherProvider, properties,
                executionProperties, redis, objectMapper);
        // Redis 锁默认能拿到
        when(redis.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.setIfAbsent(anyString(), any(), anyLong(), any())).thenReturn(true);
        // properties 默认值（部分路径不会触发 maxRetry/baseBackoff 查询，转 lenient）
        lenient().when(properties.getMaxRetry()).thenReturn(5);
        lenient().when(properties.getBaseBackoffSeconds()).thenReturn(2);
        lenient().when(properties.getBatchLimit()).thenReturn(50);
        lenient().when(properties.getConfirmTimeoutSeconds()).thenReturn(30);
        // Publisher 默认能拿到（个别用例单独 override）
        lenient().when(mqPublisherProvider.getIfAvailable()).thenReturn(mqPublisher);
        lenient().when(outboxService.listExpiredSentForRetry(anyInt())).thenReturn(List.of());
    }

    private AgentCommandOutboxEvent buildPendingRow(long id, int retryCount) {
        AgentCommandOutboxEvent row = new AgentCommandOutboxEvent();
        row.setId(id);
        row.setEventId(EVENT_ID + "-" + id);
        row.setAggregateType("EXECUTION_COMMAND");
        row.setAggregateId("1000");
        row.setStatus(AgentCommandOutboxStatus.PENDING);
        row.setRetryCount(retryCount);
        row.setNextRetryAt(null);
        Map<String, Object> payload = new HashMap<>();
        payload.put("recordId", 1000L);
        payload.put("eventId", row.getEventId());
        payload.put("subTaskId", SUB_TASK_ID);
        payload.put("agentId", AGENT_ID);
        payload.put("trigger", "assigned");
        payload.put("accessType", "API_KEY_LLM");
        row.setPayload(payload);
        return row;
    }

    private ExecutionCommandMqMessage buildMqMessage() {
        return ExecutionCommandMqMessage.builder()
                .recordId(1000L)
                .eventId(EVENT_ID)
                .subTaskId(SUB_TASK_ID)
                .agentId(AGENT_ID)
                .trigger("assigned")
                .accessType(AgentAccessType.API_KEY_LLM.name())
                .build();
    }

    @Test
    @DisplayName("Publisher 成功 → markSent")
    void shouldMarkSentWhenPublisherSuccess() throws Exception {
        AgentCommandOutboxEvent row = buildPendingRow(1L, 0);
        when(outboxService.listReadyForRelay(50)).thenReturn(List.of(row));
        when(objectMapper.convertValue(any(), eq(ExecutionCommandMqMessage.class))).thenReturn(buildMqMessage());
        CorrelationData cd = new CorrelationData("1");
        cd.getFuture().complete(new CorrelationData.Confirm(true, null));
        when(mqPublisher.publishWithCorrelation(any(ExecutionCommand.class), anyString())).thenReturn(cd);

        task.relay();

        ArgumentCaptor<ExecutionCommand> cmdCaptor = ArgumentCaptor.forClass(ExecutionCommand.class);
        verify(mqPublisher, times(1)).publishWithCorrelation(cmdCaptor.capture(), anyString());
        assertNotNull(cmdCaptor.getValue().getEventId());
        verify(outboxService, times(1)).markSent(eq(1L), any());
        verify(outboxService, times(1)).markConfirmed(eq(1L), any());
        verify(outboxService, never()).markFailed(anyLong(), anyString(), anyInt(), any());
        verify(outboxService, never()).markFinalFailed(anyLong(), anyString(), anyInt());
        verify(outboxService, never()).markFailedFromSent(anyLong(), anyString(), anyInt(), any());
        verify(outboxService, never()).markFinalFailedFromSent(anyLong(), anyString(), anyInt());
    }

    @Test
    @DisplayName("Publisher 异常且 retryCount+1 < maxRetry → markFailed 并设置 nextRetryAt")
    void shouldMarkFailedWhenPublisherThrowsAndBelowMaxRetry() throws Exception {
        AgentCommandOutboxEvent row = buildPendingRow(2L, 0);
        when(outboxService.listReadyForRelay(50)).thenReturn(List.of(row));
        when(objectMapper.convertValue(any(), eq(ExecutionCommandMqMessage.class))).thenReturn(buildMqMessage());
        doThrow(new RuntimeException("broker connection refused"))
                .when(mqPublisher).publishWithCorrelation(any(ExecutionCommand.class), anyString());

        OffsetDateTime before = OffsetDateTime.now();
        task.relay();
        OffsetDateTime after = OffsetDateTime.now();

        ArgumentCaptor<OffsetDateTime> nextAtCaptor = ArgumentCaptor.forClass(OffsetDateTime.class);
        verify(outboxService, times(1))
                .markFailed(eq(2L), anyString(), eq(1), nextAtCaptor.capture());
        verify(outboxService, never()).markSent(anyLong(), any());
        verify(outboxService, never()).markFinalFailed(anyLong(), anyString(), anyInt());

        OffsetDateTime nextAt = nextAtCaptor.getValue();
        assertNotNull(nextAt);
        // 退避基准 2s * 2^1 = 4s，最迟不能超过 after+4s+1s 余量
        assertTrue(!nextAt.isBefore(before.plusSeconds(3)) && !nextAt.isAfter(after.plusSeconds(6)),
                "nextRetryAt 应在 [before+3s, after+6s] 区间，实际=" + nextAt);
    }

    @Test
    @DisplayName("Publisher 异常且 retryCount+1 >= maxRetry → markFinalFailed")
    void shouldMarkFinalFailedWhenPublisherThrowsAndReachesMaxRetry() throws Exception {
        AgentCommandOutboxEvent row = buildPendingRow(3L, 4);
        when(outboxService.listReadyForRelay(50)).thenReturn(List.of(row));
        when(objectMapper.convertValue(any(), eq(ExecutionCommandMqMessage.class))).thenReturn(buildMqMessage());
        doThrow(new RuntimeException("broker nack"))
                .when(mqPublisher).publishWithCorrelation(any(ExecutionCommand.class), anyString());

        task.relay();

        verify(outboxService, times(1)).markFinalFailed(eq(3L), anyString(), eq(5));
        verify(outboxService, never()).markSent(anyLong(), any());
        verify(outboxService, never()).markFailed(anyLong(), anyString(), anyInt(), any());
    }

    @Test
    @DisplayName("扫描批为空 → 跳过全部发送与标记动作")
    void shouldSkipWhenEmpty() {
        when(outboxService.listReadyForRelay(50)).thenReturn(List.of());

        task.relay();

        verify(mqPublisher, never()).publishWithCorrelation(any(ExecutionCommand.class), anyString());
        verify(outboxService, never()).markSent(anyLong(), any());
        verify(outboxService, never()).markFailed(anyLong(), anyString(), anyInt(), any());
        verify(outboxService, never()).markFinalFailed(anyLong(), anyString(), anyInt());
    }

    @Test
    @DisplayName("payload 反序列化失败 → markFinalFailed（终态）")
    void shouldMarkFinalFailedWhenPayloadDeserializeFails() throws Exception {
        AgentCommandOutboxEvent row = buildPendingRow(4L, 2);
        when(outboxService.listReadyForRelay(50)).thenReturn(List.of(row));
        when(objectMapper.convertValue(any(), eq(ExecutionCommandMqMessage.class)))
                .thenThrow(new IllegalArgumentException("invalid payload schema"));

        task.relay();

        verify(outboxService, times(1))
                .markFinalFailed(eq(4L), anyString(), eq(2));
        verify(mqPublisher, never()).publishWithCorrelation(any(ExecutionCommand.class), anyString());
        verify(outboxService, never()).markSent(anyLong(), any());
        verify(outboxService, never()).markFailed(anyLong(), anyString(), anyInt(), any());
    }

    @Test
    @DisplayName("Confirm NACK → markFailedFromSent 回退重试")
    void shouldScheduleRetryWhenConfirmNack() throws Exception {
        AgentCommandOutboxEvent row = buildPendingRow(5L, 0);
        when(outboxService.listReadyForRelay(50)).thenReturn(List.of(row));
        when(objectMapper.convertValue(any(), eq(ExecutionCommandMqMessage.class))).thenReturn(buildMqMessage());
        when(outboxService.getById(5L)).thenReturn(row);

        CorrelationData cd = new CorrelationData("5");
        cd.getFuture().complete(new CorrelationData.Confirm(false, "nack"));
        when(mqPublisher.publishWithCorrelation(any(ExecutionCommand.class), anyString())).thenReturn(cd);

        task.relay();

        verify(outboxService, times(1)).markSent(eq(5L), any());
        verify(outboxService, times(1)).markFailedFromSent(eq(5L), anyString(), eq(1), any());
    }

    @Test
    @DisplayName("SENT 超时未确认 → markFailedFromSent 回退重试")
    void shouldScheduleRetryWhenExpiredSent() {
        AgentCommandOutboxEvent sent = new AgentCommandOutboxEvent();
        sent.setId(6L);
        sent.setEventId("evt-006");
        sent.setStatus(AgentCommandOutboxStatus.SENT);
        sent.setRetryCount(0);
        when(outboxService.listExpiredSentForRetry(50)).thenReturn(List.of(sent));
        when(outboxService.listReadyForRelay(50)).thenReturn(List.of());
        when(outboxService.getById(6L)).thenReturn(sent);

        task.relay();

        verify(outboxService, times(1)).markFailedFromSent(eq(6L), anyString(), eq(1), any());
        verify(mqPublisher, never()).publishWithCorrelation(any(ExecutionCommand.class), anyString());
    }
}
