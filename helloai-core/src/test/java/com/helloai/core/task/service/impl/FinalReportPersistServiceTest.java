package com.helloai.core.task.service.impl;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.helloai.common.constant.FinalReportStatus;
import com.helloai.core.agent.service.AgentOutboxService;
import com.helloai.core.shared.event.TaskFinalReportGeneratedEvent;
import com.helloai.core.task.entity.Task;
import com.helloai.core.task.service.TaskService;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.context.ApplicationEventPublisher;

import java.time.OffsetDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * FinalReportPersistService 单元测试（§12.2 报告写回与审查触发同事务）。
 *
 * <p>覆盖：审查开启时写回 REVIEWING + Outbox 落库 + 事件发布三者齐备；
 * 审查关闭时只写回 DONE 且不产生任何审查触发；
 * CAS 未命中（状态被接管）时既不发布事件也不写 Outbox。</p>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("FinalReportPersistService 报告写回与审查触发同事务")
class FinalReportPersistServiceTest {

    private static final Long TASK_ID = 1L;
    private static final Long PLANNER_ID = 99L;
    private static final OffsetDateTime NOW = OffsetDateTime.parse("2026-09-28T19:10:41.245320+08:00");

    @Mock
    private TaskService taskService;
    @Mock
    private AgentOutboxService agentOutboxService;
    @Mock
    private ApplicationEventPublisher applicationEventPublisher;

    private FinalReportPersistService persistService;

    /** 显式构造 {@link LambdaUpdateWrapper} 需要 Task 的 TableInfo 缓存（单测无 Spring 上下文）。 */
    @BeforeAll
    static void initTableInfo() {
        TableInfoHelper.initTableInfo(new org.apache.ibatis.builder.MapperBuilderAssistant(
                new MybatisConfiguration(), ""), Task.class);
    }

    @BeforeEach
    void setUp() {
        persistService = new FinalReportPersistService(taskService, agentOutboxService, applicationEventPublisher);
        when(taskService.update(any())).thenReturn(true);
    }

    @Test
    @DisplayName("审查开启：写回 REVIEWING + Outbox 落库 + AFTER_COMMIT 事件发布，三者齐备")
    void shouldWriteReviewingAndEnqueueWhenReviewEnabled() {
        boolean ok = persistService.persistAndRequestReview(
                TASK_ID, "# 报告正文", PLANNER_ID, NOW, 1, 8, true);

        assertThat(ok).isTrue();
        LambdaUpdateWrapper<Task> wrapper = captureUpdate();
        // 写回列齐备：状态 / 正文 / 生成者 / 时间 + §12.1 prev 槽换入
        assertThat(wrapper.getSqlSet())
                .contains("final_report_status")
                .contains("final_report")
                .contains("final_report_agent_id")
                .contains("final_report_time")
                .contains("final_report_prev");
        // 审查请求经 Outbox 持久化（重启不丢），并携带陈旧守卫锚点
        verify(agentOutboxService).createReportReviewEvent(TASK_ID, NOW, 1, "# 报告正文".length());
        // L1：事务内发布，由 @TransactionalEventListener(AFTER_COMMIT) 承接
        ArgumentCaptor<TaskFinalReportGeneratedEvent> eventCaptor =
                ArgumentCaptor.forClass(TaskFinalReportGeneratedEvent.class);
        verify(applicationEventPublisher).publishEvent(eventCaptor.capture());
        TaskFinalReportGeneratedEvent event = eventCaptor.getValue();
        assertThat(event.getTaskId()).isEqualTo(TASK_ID);
        assertThat(event.getAttempt()).isEqualTo(1);
        assertThat(event.getReportTime()).isEqualTo(NOW);
        assertThat(event.getSectionCount()).isEqualTo(8);
    }

    @Test
    @DisplayName("审查关闭：只写回 DONE，不写 Outbox、不发事件（无审查链存在）")
    void shouldOnlyWriteDoneWhenReviewDisabled() {
        boolean ok = persistService.persistAndRequestReview(
                TASK_ID, "# 报告正文", PLANNER_ID, NOW, 1, 8, false);

        assertThat(ok).isTrue();
        captureUpdate();
        verify(agentOutboxService, never()).createReportReviewEvent(any(), any(), anyInt(), anyInt());
        verify(applicationEventPublisher, never()).publishEvent(any(TaskFinalReportGeneratedEvent.class));
    }

    @Test
    @DisplayName("CAS 未命中（状态被接管）：返回 false，且不写 Outbox、不发事件（双写原子性）")
    void shouldAbortBothWhenCasMisses() {
        when(taskService.update(any())).thenReturn(false);

        boolean ok = persistService.persistAndRequestReview(
                TASK_ID, "# 报告正文", PLANNER_ID, NOW, 1, 8, true);

        assertThat(ok).isFalse();
        verify(agentOutboxService, never()).createReportReviewEvent(any(), any(), anyInt(), anyInt());
        verify(applicationEventPublisher, never()).publishEvent(any(TaskFinalReportGeneratedEvent.class));
    }

    @SuppressWarnings("unchecked")
    private LambdaUpdateWrapper<Task> captureUpdate() {
        ArgumentCaptor<LambdaUpdateWrapper<Task>> captor =
                ArgumentCaptor.forClass(LambdaUpdateWrapper.class);
        verify(taskService).update(captor.capture());
        return captor.getValue();
    }
}
