package com.helloai.core.agent.session.service.impl;

import com.helloai.common.constant.SessionStatus;
import com.helloai.core.agent.session.entity.AgentSession;
import com.helloai.core.agent.session.mapper.AgentSessionMapper;
import com.helloai.core.agent.session.service.AgentSessionService.InterruptedSession;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Agent 执行会话服务单元测试（Phase 1 Step 3）：
 * start 幂等（同 turn 复用 / 异 turn append）/ advance / complete / fail /
 * interrupt（ABORT + 返回中断摘要）。纯 Mockito 测试 mapper 层。
 *
 * <p>注意：MyBatis-Plus 3.5.9 BaseMapper 新增 {@code insert(Collection)} /
 * {@code updateById(Collection)} 重载，对 insert/updateById 的 verify 必须用
 * 类型化 matcher（{@code any(AgentSession.class)}），裸 {@code any()} 会歧义。</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("AgentSessionServiceImpl")
class AgentSessionServiceImplTest {

    @Mock
    private AgentSessionMapper agentSessionMapper;

    @InjectMocks
    private AgentSessionServiceImpl agentSessionService;

    @Test
    @DisplayName("start：无 ACTIVE 会话时插入新会话（ACTIVE/step=2/snapshot/runId）")
    void shouldInsertWhenNoActiveSession() {
        when(agentSessionMapper.selectLatestActiveBySubTaskId(22L)).thenReturn(null);

        agentSessionService.start(33L, 22L, 11L, 1, 2, Map.of("skills", List.of("eng"), "depCount", 1));

        ArgumentCaptor<AgentSession> cap = ArgumentCaptor.forClass(AgentSession.class);
        verify(agentSessionMapper).insert(cap.capture());
        AgentSession s = cap.getValue();
        assertThat(s.getSubTaskId()).isEqualTo(22L);
        assertThat(s.getTaskId()).isEqualTo(33L);
        assertThat(s.getAgentId()).isEqualTo(11L);
        assertThat(s.getTurn()).isEqualTo(1);
        assertThat(s.getStep()).isEqualTo(2);
        assertThat(s.getStatus()).isEqualTo(SessionStatus.ACTIVE.name());
        assertThat(s.getRunId()).isEqualTo("run-33-1");
        assertThat(s.getSnapshot()).containsEntry("skills", List.of("eng")).containsEntry("depCount", 1);
    }

    @Test
    @DisplayName("start：同 turn 已存在 ACTIVE → 刷新快照/step（重入复用，不建新行）")
    void shouldUpdateWhenActiveSameTurn() {
        AgentSession existing = new AgentSession();
        existing.setId(9L);
        existing.setSubTaskId(22L);
        existing.setTurn(1);
        existing.setStatus(SessionStatus.ACTIVE.name());
        when(agentSessionMapper.selectLatestActiveBySubTaskId(22L)).thenReturn(existing);

        agentSessionService.start(33L, 22L, 11L, 1, 2, Map.of("depCount", 2));

        ArgumentCaptor<AgentSession> cap = ArgumentCaptor.forClass(AgentSession.class);
        verify(agentSessionMapper).updateById(cap.capture());
        assertThat(cap.getValue().getId()).isEqualTo(9L);
        assertThat(cap.getValue().getStep()).isEqualTo(2);
        assertThat(cap.getValue().getSnapshot()).containsEntry("depCount", 2);
        verify(agentSessionMapper, never()).insert(any(AgentSession.class));
    }

    @Test
    @DisplayName("start：不同 turn 的 ACTIVE（计数器复位场景）→ 插入新行（append 语义）")
    void shouldInsertWhenActiveDifferentTurn() {
        AgentSession existing = new AgentSession();
        existing.setId(9L);
        existing.setSubTaskId(22L);
        existing.setTurn(2);
        existing.setStatus(SessionStatus.ACTIVE.name());
        when(agentSessionMapper.selectLatestActiveBySubTaskId(22L)).thenReturn(existing);

        agentSessionService.start(33L, 22L, 11L, 1, 2, Map.of());

        verify(agentSessionMapper).insert(any(AgentSession.class));
        verify(agentSessionMapper, never()).updateById(any(AgentSession.class));
    }

    @Test
    @DisplayName("start：写入异常 → best-effort 不抛出")
    void shouldNotThrowWhenInsertFails() {
        when(agentSessionMapper.selectLatestActiveBySubTaskId(22L)).thenReturn(null);
        doThrow(new RuntimeException("db down")).when(agentSessionMapper).insert(any(AgentSession.class));

        agentSessionService.start(33L, 22L, 11L, 1, 2, Map.of());
        // 不抛异常即通过（best-effort）
    }

    @Test
    @DisplayName("complete：ACTIVE → COMPLETED（定点更新 status）")
    void shouldMarkCompleted() {
        agentSessionService.complete(22L, 11L, 1);

        verify(agentSessionMapper).completeBySubTaskAndTurn(22L, 1);
    }

    @Test
    @DisplayName("fail：ACTIVE → FAILED + error（定点更新 status + error）")
    void shouldMarkFailedWithError() {
        agentSessionService.fail(22L, 11L, 1, "boom");

        verify(agentSessionMapper).failBySubTaskAndTurn(22L, 1, "boom");
    }

    @Test
    @DisplayName("advance：ACTIVE 会话推进 step（定点更新 step）")
    void shouldAdvanceStep() {
        agentSessionService.advance(22L, 11L, 1, 4);

        verify(agentSessionMapper).advanceStep(22L, 1, 4);
    }

    @Test
    @DisplayName("interrupt：有 ACTIVE 会话 → ABORT 并返回中断摘要（中断点 turn/step）")
    void shouldAbortAndReturnInterrupted() {
        AgentSession active = new AgentSession();
        active.setId(7L);
        active.setSubTaskId(22L);
        active.setAgentId(11L);
        active.setTurn(3);
        active.setStep(2);
        active.setStatus(SessionStatus.ACTIVE.name());
        active.setSnapshot(Map.of("depCount", 1));
        when(agentSessionMapper.selectLatestActiveBySubTaskId(22L)).thenReturn(active);
        when(agentSessionMapper.abortActiveBySubTaskId(22L)).thenReturn(1);

        InterruptedSession interrupted = agentSessionService.interrupt(22L);

        verify(agentSessionMapper).abortActiveBySubTaskId(22L);
        assertThat(interrupted).isNotNull();
        assertThat(interrupted.sessionId()).isEqualTo(7L);
        assertThat(interrupted.agentId()).isEqualTo(11L);
        assertThat(interrupted.turn()).isEqualTo(3);
        assertThat(interrupted.step()).isEqualTo(2);
        assertThat(interrupted.snapshot()).containsEntry("depCount", 1);
    }

    @Test
    @DisplayName("interrupt：无 ACTIVE 会话 → 返回 null 且不触发 ABORT")
    void shouldReturnNullWhenNoActive() {
        when(agentSessionMapper.selectLatestActiveBySubTaskId(22L)).thenReturn(null);

        assertThat(agentSessionService.interrupt(22L)).isNull();
        verify(agentSessionMapper, never()).abortActiveBySubTaskId(any());
    }
}
