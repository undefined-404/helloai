package com.helloai.core.agent.runtime.sandbox.impl;

import com.helloai.common.constant.TimelineEventCode;
import com.helloai.core.agent.port.TaskTimelinePort;
import com.helloai.core.agent.runtime.sandbox.ExecuteResponse;
import com.helloai.core.agent.runtime.sandbox.SandboxCallLedger;
import com.helloai.core.agent.runtime.sandbox.SandboxCallOrigin;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * {@link SandboxObservation} 单测（`SL-9`，2026-11-13）。
 *
 * <p>覆盖 `SR-5` / `SL-4` 的观测契约：台账字段、timeline 载荷、{@code callId} 回填、
 * 无观测归属时不伪造 ID，以及「超时 / SDK 异常同样入账」的口径。</p>
 */
@DisplayName("SandboxObservation")
class SandboxObservationTest {

    private SandboxObservation observation(SandboxCallLedger ledger, TaskTimelinePort timeline) {
        return new SandboxObservation(ledger, timeline);
    }

    @Test
    @DisplayName("SR-5 台账：一次调用 ⇒ 台账条目字段齐全 + timeline 事件（digest 与输出一致）")
    void recordShouldLedgerAndEmitTimeline() {
        TaskTimelinePort timeline = mock(TaskTimelinePort.class);
        SandboxCallLedger ledger = new SandboxCallLedger();
        SandboxObservation observation = observation(ledger, timeline);
        ExecuteResponse response = new ExecuteResponse("hello\n", 0, false);
        AtomicReference<String> eventType = new AtomicReference<>();
        AtomicReference<Map<String, Object>> payload = new AtomicReference<>();
        doAnswer(invocation -> {
            eventType.set(invocation.getArgument(2));
            payload.set(invocation.getArgument(5));
            return null;
        }).when(timeline).recordEvent(any(), any(), any(), any(), any(), any());

        observation.record(10L, "echo hello", response);

        List<SandboxCallLedger.Entry> entries = ledger.recent(10L);
        assertThat(entries).hasSize(1);
        SandboxCallLedger.Entry entry = entries.get(0);
        assertThat(entry.command()).isEqualTo("echo hello");
        assertThat(entry.exitCode()).isZero();
        assertThat(entry.truncated()).isFalse();
        assertThat(entry.digest()).isEqualTo(response.digest());
        assertThat(entry.at()).isNotNull();

        assertThat(eventType.get()).isEqualTo(TimelineEventCode.SANDBOX_COMMAND_EXECUTED.code());
        assertThat(payload.get())
                .containsEntry("command", "echo hello")
                .containsEntry("commandChars", 10)
                .containsEntry("exitCode", 0)
                .containsEntry("truncated", false)
                .containsEntry("digest", response.digest());
    }

    @Test
    @DisplayName("SR-5 台账：超时(124) / SDK 异常(-1) 同样入账")
    void ledgerShouldRecordFailedAndTimedOutCalls() {
        SandboxCallLedger ledger = new SandboxCallLedger();
        SandboxObservation observation = observation(ledger,
                (taskId, subTaskId, eventType, role, agentId, payload) -> {
                });

        observation.record(11L, "sleep 999",
                new ExecuteResponse("timeout", ExecuteResponse.EXIT_CODE_TIMEOUT, false));
        observation.record(11L, "boom",
                new ExecuteResponse("sandbox execution failed", ExecuteResponse.EXIT_CODE_ERROR, false));

        assertThat(ledger.recent(11L))
                .extracting(SandboxCallLedger.Entry::exitCode)
                .containsExactly(ExecuteResponse.EXIT_CODE_TIMEOUT, ExecuteResponse.EXIT_CODE_ERROR);
    }

    @Test
    @DisplayName("SR-5 台账：无观测归属 ⇒ 不入账、不发事件（Reviewer / 终报告判定路径）")
    void ledgerShouldSkipWhenContextIdMissing() {
        TaskTimelinePort timeline = mock(TaskTimelinePort.class);
        SandboxCallLedger ledger = new SandboxCallLedger();

        observation(ledger, timeline).record(null, "echo hi", new ExecuteResponse("hi", 0, false));

        assertThat(ledger.recent(10L)).isEmpty();
        verifyNoInteractions(timeline);
    }

    @Test
    @DisplayName("SL-4 record：结果回填 callId；台账条目带 origin/round；timeline 同源")
    void recordShouldBackfillCallIdAndCarryOriginRound() {
        TaskTimelinePort timeline = mock(TaskTimelinePort.class);
        SandboxCallLedger ledger = new SandboxCallLedger();
        SandboxObservation observation = observation(ledger, timeline);
        AtomicReference<Map<String, Object>> payload = new AtomicReference<>();
        doAnswer(invocation -> {
            payload.set(invocation.getArgument(5));
            return null;
        }).when(timeline).recordEvent(any(), any(), any(), any(), any(), any());

        ExecuteResponse response = observation.record(10L, "echo hi",
                new ExecuteResponse("hi", 0, false), SandboxCallOrigin.REVIEWER, 2);

        assertThat(response.callId()).startsWith("c");
        SandboxCallLedger.Entry entry = ledger.recent(10L).get(0);
        assertThat(entry.callId()).isEqualTo(response.callId());
        assertThat(entry.origin()).isEqualTo("reviewer");
        assertThat(entry.round()).isEqualTo(2);
        assertThat(payload.get())
                .containsEntry("callId", response.callId())
                .containsEntry("origin", "reviewer")
                .containsEntry("round", 2);
    }

    @Test
    @DisplayName("SL-4 record：无观测归属 ⇒ callId 为 null（不伪造 ID）、不发事件")
    void recordShouldLeaveCallIdNullWithoutContext() {
        TaskTimelinePort timeline = mock(TaskTimelinePort.class);
        SandboxObservation observation = observation(new SandboxCallLedger(), timeline);

        ExecuteResponse response = observation.record(null, "echo hi",
                new ExecuteResponse("hi", 0, false));

        assertThat(response.callId()).isNull();
        verifyNoInteractions(timeline);
    }

    @Test
    @DisplayName("SL-7 超时事件：写 SANDBOX_COMMAND_TIMED_OUT；无归属 / 写失败都不抛异常")
    void timedOutShouldEmitEventAndNeverThrow() {
        TaskTimelinePort timeline = mock(TaskTimelinePort.class);
        AtomicReference<String> eventType = new AtomicReference<>();
        AtomicReference<Map<String, Object>> payload = new AtomicReference<>();
        doAnswer(invocation -> {
            eventType.set(invocation.getArgument(2));
            payload.set(invocation.getArgument(5));
            return null;
        }).when(timeline).recordEvent(any(), any(), any(), any(), any(), any());
        SandboxObservation observation = observation(new SandboxCallLedger(), timeline);

        observation.timedOut(10L, "npm install", 300L);
        observation.timedOut(null, "npm install", 300L);

        assertThat(eventType.get()).isEqualTo(TimelineEventCode.SANDBOX_COMMAND_TIMED_OUT.code());
        assertThat(payload.get())
                .containsEntry("command", "npm install")
                .containsEntry("timeoutSeconds", 300L);

        TaskTimelinePort broken = mock(TaskTimelinePort.class);
        doAnswer(invocation -> {
            throw new IllegalStateException("db down");
        }).when(broken).recordEvent(any(), any(), any(), any(), any(), any());
        observation(new SandboxCallLedger(), broken).timedOut(10L, "npm install", 300L);
    }
}
