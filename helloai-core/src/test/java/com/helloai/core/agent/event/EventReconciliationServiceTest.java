package com.helloai.core.agent.event;

import com.helloai.common.constant.SubTaskStatus;
import com.helloai.core.agent.event.impl.EventReconciliationServiceImpl;
import com.helloai.core.agent.mapper.AgentEventMapper;
import com.helloai.core.task.entity.SubTask;
import com.helloai.core.task.service.SubTaskService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.OffsetDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 事件流对账服务单元测试（Phase 0 B3）：
 * 状态 → 期望末条事件映射、事件缺失/错位检出、无事件语义状态跳过、窗口与 limit 透传。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("EventReconciliationService")
class EventReconciliationServiceTest {

    @Mock
    private SubTaskService subTaskService;

    @Mock
    private AgentEventMapper agentEventMapper;

    private EventReconciliationService reconciliationService;

    @BeforeEach
    void setUp() {
        reconciliationService = new EventReconciliationServiceImpl(subTaskService, agentEventMapper);
        // 默认：候选源为空（各用例按需 stub），事件末条默认 task_assigned
        // LENIENT：跳过状态/窗口透传用例不使用部分默认 stub，避免 UnnecessaryStubbing
        when(subTaskService.listRecentlyChanged(any(OffsetDateTime.class), anyInt())).thenReturn(List.of());
        when(agentEventMapper.selectLastEventTypeBySubTaskId(anyLong())).thenReturn("task_assigned");
    }

    @Test
    @DisplayName("全链路一致：五个有事件语义状态末条事件匹配 → 0 不一致")
    void shouldReportZeroWhenAllLastEventsMatch() {
        when(subTaskService.listRecentlyChanged(any(OffsetDateTime.class), anyInt())).thenReturn(List.of(
                subTask(1L, SubTaskStatus.ASSIGNED),
                subTask(2L, SubTaskStatus.IN_PROGRESS),
                subTask(3L, SubTaskStatus.REVIEW),
                subTask(4L, SubTaskStatus.REWORK),
                subTask(5L, SubTaskStatus.DONE)));

        when(agentEventMapper.selectLastEventTypeBySubTaskId(anyLong()))
                .thenReturn("task_assigned")
                .thenReturn("agent_started")
                .thenReturn("review_started")
                .thenReturn("rework_started")
                .thenReturn("review_approved");

        assertThat(reconciliationService.reconcile(100)).isZero();
    }

    @Test
    @DisplayName("事件缺失：业务表 ASSIGNED 但事件流为空 → 1 不一致")
    void shouldDetectMissingEvent() {
        when(subTaskService.listRecentlyChanged(any(OffsetDateTime.class), anyInt()))
                .thenReturn(List.of(subTask(1L, SubTaskStatus.ASSIGNED)));
        when(agentEventMapper.selectLastEventTypeBySubTaskId(anyLong())).thenReturn(null);

        assertThat(reconciliationService.reconcile(100)).isEqualTo(1);
    }

    @Test
    @DisplayName("事件错位：DONE 但末条事件是 review_rejected → 1 不一致")
    void shouldDetectWrongLastEvent() {
        when(subTaskService.listRecentlyChanged(any(OffsetDateTime.class), anyInt()))
                .thenReturn(List.of(subTask(1L, SubTaskStatus.DONE)));
        when(agentEventMapper.selectLastEventTypeBySubTaskId(anyLong())).thenReturn("review_rejected");

        assertThat(reconciliationService.reconcile(100)).isEqualTo(1);
    }

    @Test
    @DisplayName("IN_PROGRESS 执行链任一末条事件均视为一致（step 1-4 递增）")
    void shouldAcceptAnyInProgressChainEvent() {
        for (String eventType : List.of("agent_started", "context_built", "tool_call_started", "tool_call_completed")) {
            when(subTaskService.listRecentlyChanged(any(OffsetDateTime.class), anyInt()))
                    .thenReturn(List.of(subTask(1L, SubTaskStatus.IN_PROGRESS)));
            when(agentEventMapper.selectLastEventTypeBySubTaskId(anyLong())).thenReturn(eventType);

            assertThat(reconciliationService.reconcile(100))
                    .as("IN_PROGRESS + %s 应一致", eventType)
                    .isZero();
        }
    }

    @Test
    @DisplayName("REVIEW 阶段 AGENT_COMPLETED 与 REVIEW_STARTED 均为合法末条事件")
    void shouldAcceptBothReviewBoundaryEvents() {
        for (String eventType : List.of("agent_completed", "review_started")) {
            when(subTaskService.listRecentlyChanged(any(OffsetDateTime.class), anyInt()))
                    .thenReturn(List.of(subTask(1L, SubTaskStatus.REVIEW)));
            when(agentEventMapper.selectLastEventTypeBySubTaskId(anyLong())).thenReturn(eventType);

            assertThat(reconciliationService.reconcile(100))
                    .as("REVIEW + %s 应一致", eventType)
                    .isZero();
        }
    }

    @Test
    @DisplayName("无事件语义状态跳过：不查事件、不报不一致")
    void shouldSkipStatusesWithoutEventSemantics() {
        for (SubTaskStatus status : List.of(SubTaskStatus.PENDING_PLAN_REVIEW, SubTaskStatus.PENDING,
                SubTaskStatus.PAUSED, SubTaskStatus.BLOCKED, SubTaskStatus.CANCELLED, SubTaskStatus.DEAD_LETTER)) {
            when(subTaskService.listRecentlyChanged(any(OffsetDateTime.class), anyInt()))
                    .thenReturn(List.of(subTask(1L, status)));

            assertThat(reconciliationService.reconcile(100))
                    .as("状态 %s 应跳过", status)
                    .isZero();
        }
        verify(agentEventMapper, never()).selectLastEventTypeBySubTaskId(anyLong());
    }

    @Test
    @DisplayName("窗口与 limit 透传：since ≈ now-10min，limit 原样传给候选查询")
    void shouldPassWindowAndLimitToSubTaskService() {
        reconciliationService.reconcile(42);

        ArgumentCaptor<OffsetDateTime> sinceCaptor = ArgumentCaptor.forClass(OffsetDateTime.class);
        ArgumentCaptor<Integer> limitCaptor = ArgumentCaptor.forClass(Integer.class);
        verify(subTaskService).listRecentlyChanged(sinceCaptor.capture(), limitCaptor.capture());

        OffsetDateTime since = sinceCaptor.getValue();
        assertThat(since).isAfter(OffsetDateTime.now().minusMinutes(11));
        assertThat(since).isBeforeOrEqualTo(OffsetDateTime.now().minusMinutes(10));
        assertThat(limitCaptor.getValue()).isEqualTo(42);
    }

    private SubTask subTask(Long id, SubTaskStatus status) {
        SubTask subTask = new SubTask();
        subTask.setId(id);
        subTask.setStatus(status);
        return subTask;
    }
}