package com.helloai.core.agent.command;

import com.helloai.common.constant.AgentRole;
import com.helloai.common.constant.SubTaskStatus;
import com.helloai.common.constant.TimelineEventCode;
import com.helloai.core.agent.port.SubTaskCommandPort;
import com.helloai.core.agent.port.SubTaskQueryPort;
import com.helloai.core.agent.port.SubTaskSnapshot;
import com.helloai.core.agent.port.TaskRunningSpecPort;
import com.helloai.core.agent.port.TaskTimelinePort;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@code ExecutionResultHandler} 的交付物登记闸接线测试（`MA-4`，plan §3 `MA4-6`）。
 *
 * <p>覆盖：判据只在 {@code source=EXTERNAL && success=true} 触发；不通过只 warn + 时间线事件、
 * <b>不阻断</b>（{@code submit} 照常推进）；内部链（{@code INTERNAL}）短路零改动；
 * 判据不可用（{@code evaluate} 返回 null）不登记（判据 9 fail-open）。</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ExecutionResultHandler 交付物登记闸")
class ExecutionResultHandlerDeliveryGateTest {

    @Mock
    private SubTaskQueryPort subTaskQueryPort;
    @Mock
    private SubTaskCommandPort subTaskCommandPort;
    @Mock
    private TaskTimelinePort taskTimelinePort;
    @Mock
    private com.helloai.core.agent.observability.ExternalAgentFailureTracker failureTracker;
    @Mock
    private com.helloai.core.agent.service.AgentService agentService;
    @Mock
    private org.springframework.context.ApplicationEventPublisher applicationEventPublisher;
    @Mock
    private com.helloai.core.agent.service.ConversationService conversationService;
    @Mock
    private com.helloai.core.agent.service.ExecutionArtifactService executionArtifactService;
    @Mock
    private TaskRunningSpecPort taskRunningSpecPort;
    @Mock
    private com.helloai.core.agent.output.ExecutionOutputParser executionOutputParser;
    @Mock
    private com.helloai.core.agent.session.service.AgentSessionService agentSessionService;
    @Mock
    private com.helloai.core.agent.service.AgentExecutionRecordService agentExecutionRecordService;
    @Mock
    private com.helloai.core.agent.event.AgentEventRecorder agentEventRecorder;
    @Mock
    private DeliveryEvidenceEvaluator deliveryEvidenceEvaluator;

    @InjectMocks
    private ExecutionResultHandler executionResultHandler;

    @BeforeEach
    void setUp() {
        lenient().when(executionOutputParser.parse(any(), any()))
                .thenReturn(com.helloai.core.agent.output.ParsedOutput.empty());
    }

    private void snapshotInProgress() {
        when(subTaskQueryPort.findById(77L))
                .thenReturn(SubTaskSnapshot.builder()
                        .id(77L)
                        .taskId(88L)
                        .status(SubTaskStatus.IN_PROGRESS)
                        .title("delivery-gate-test")
                        .context(Map.of())
                        .build());
    }

    private ExecutionResultReport externalReport(String output) {
        ExecutionResultReport report = new ExecutionResultReport();
        report.setSubTaskId(77L);
        report.setAgentId(1L);
        report.setSource("EXTERNAL");
        report.setSuccess(true);
        report.setOutput(output);
        return report;
    }

    @Test
    @DisplayName("EXTERNAL 成功 + 判据不通过 → 记 DELIVERY_GATE_MISS 事件（warn-only，submit 照常）")
    void externalMissRecordsEventAndStillSubmits() {
        snapshotInProgress();
        when(deliveryEvidenceEvaluator.evaluate(eq(77L), eq(null), eq("短"), any()))
                .thenReturn(new DeliveryEvidenceEvaluator.DeliveryEvidenceDetail(
                        false, null, false, false, false, 2));

        executionResultHandler.handleReport(externalReport("短"));

        // 事件落库（事件码 + payload 带三判据明细）
        ArgumentCaptor<Map<String, Object>> payloadCaptor = ArgumentCaptor.forClass(Map.class);
        verify(taskTimelinePort).recordEvent(eq(88L), eq(77L),
                eq(TimelineEventCode.DELIVERY_GATE_MISS.code()),
                eq(AgentRole.EXECUTOR), eq(1L), payloadCaptor.capture());
        Map<String, Object> payload = payloadCaptor.getValue();
        assertThat(payload.get("partsHit")).isEqualTo(false);
        assertThat(payload.get("attachmentHit")).isEqualTo(false);
        assertThat(payload.get("outputHit")).isEqualTo(false);
        assertThat(payload.get("outputLength")).isEqualTo(2);
        // warn-only 不阻断：状态照常推进
        verify(subTaskCommandPort).submit(77L);
    }

    @Test
    @DisplayName("EXTERNAL 成功 + 判据通过 → 不记 DELIVERY_GATE_MISS 事件")
    void externalPassNoEvent() {
        snapshotInProgress();
        when(deliveryEvidenceEvaluator.evaluate(eq(77L), eq(null), eq("有产物正文"), any()))
                .thenReturn(new DeliveryEvidenceEvaluator.DeliveryEvidenceDetail(
                        false, null, false, false, true, 6));

        executionResultHandler.handleReport(externalReport("有产物正文"));

        verify(taskTimelinePort, never()).recordEvent(eq(88L), eq(77L),
                eq(TimelineEventCode.DELIVERY_GATE_MISS.code()), any(), any(), any());
        verify(subTaskCommandPort).submit(77L);
    }

    @Test
    @DisplayName("内部链（source=INTERNAL）→ 判据根本不触发（内部链零改动，即使 output 为空）")
    void internalChainNeverEvaluates() {
        snapshotInProgress();
        ExecutionResultReport report = new ExecutionResultReport();
        report.setSubTaskId(77L);
        report.setAgentId(1L);
        report.setSource("INTERNAL");
        report.setSuccess(true);
        report.setOutput(""); // 内部链即使 output 为空也不触发闸

        executionResultHandler.handleReport(report);

        verify(deliveryEvidenceEvaluator, never()).evaluate(any(), any(), any(), any());
        verify(taskTimelinePort, never()).recordEvent(eq(88L), eq(77L),
                eq(TimelineEventCode.DELIVERY_GATE_MISS.code()), any(), any(), any());
        verify(subTaskCommandPort).submit(77L);
    }

    @Test
    @DisplayName("判据不可用（evaluate 返回 null，如附件端口异常）→ 不登记（fail-open 不误报）")
    void unavailableEvaluationNoEvent() {
        snapshotInProgress();
        when(deliveryEvidenceEvaluator.evaluate(eq(77L), eq(null), eq("短"), any()))
                .thenReturn(null);

        executionResultHandler.handleReport(externalReport("短"));

        verify(taskTimelinePort, never()).recordEvent(eq(88L), eq(77L),
                eq(TimelineEventCode.DELIVERY_GATE_MISS.code()), any(), any(), any());
        verify(subTaskCommandPort).submit(77L);
    }

    @Test
    @DisplayName("EXTERNAL 失败（success=false）→ 判据不触发，走正常失败回写")
    void externalFailureNeverEvaluates() {
        snapshotInProgress();
        ExecutionResultReport report = externalReport("失败原因");
        report.setSuccess(false);

        executionResultHandler.handleReport(report);

        verify(deliveryEvidenceEvaluator, never()).evaluate(any(), any(), any(), any());
        verify(taskTimelinePort, never()).recordEvent(eq(88L), eq(77L),
                eq(TimelineEventCode.DELIVERY_GATE_MISS.code()), any(), any(), any());
        verify(subTaskCommandPort).block(eq(77L), anyString(), eq(1L));
    }
}