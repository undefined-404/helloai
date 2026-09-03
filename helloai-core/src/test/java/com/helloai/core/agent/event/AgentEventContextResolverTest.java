package com.helloai.core.agent.event;

import com.helloai.core.task.entity.SubTask;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * {@link AgentEventContextResolver} 单元测试（Phase 0 B2）。
 */
class AgentEventContextResolverTest {

    @Test
    void runIdUsesFixedRoundOne() {
        assertEquals("run-42-1", AgentEventContextResolver.resolveRunId(42L));
        assertEquals("run-1-1", AgentEventContextResolver.resolveRunId(1L));
    }

    @Test
    void turnStartsAtOneWhenCountersEmpty() {
        SubTask subTask = new SubTask();
        assertEquals(1, AgentEventContextResolver.resolveTurn(subTask));
    }

    @Test
    void turnAccountsReworkAndReassign() {
        SubTask subTask = new SubTask();
        subTask.setReworkCount(2);
        subTask.setAttemptTotal(3);
        // 1 + rework(2) + attemptTotal(3) = 6
        assertEquals(6, AgentEventContextResolver.resolveTurn(subTask));
    }

    @Test
    void turnTreatsNullCountersAsZero() {
        SubTask subTask = new SubTask();
        subTask.setReworkCount(null);
        subTask.setAttemptTotal(null);
        assertEquals(1, AgentEventContextResolver.resolveTurn(subTask));

        subTask.setReworkCount(1);
        subTask.setAttemptTotal(null);
        assertEquals(2, AgentEventContextResolver.resolveTurn(subTask));
    }

    @Test
    void turnReturnsOneForNullSubTask() {
        assertEquals(1, AgentEventContextResolver.resolveTurn(null));
    }
}