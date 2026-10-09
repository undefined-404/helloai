package com.helloai.core.agent.event;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * {@link AgentEventContextResolver} 单元测试。
 *
 * <p>2026-10-01：{@code resolveTurn} 形参由 task 域实体 {@code SubTask} 改为两个计数，
 * 以摘除 agent → task 的反向 import；原「空实体」语义等价折算为「两个计数皆空」。</p>
 */
class AgentEventContextResolverTest {

    @Test
    void runIdUsesFixedRoundOne() {
        assertEquals("run-42-1", AgentEventContextResolver.resolveRunId(42L));
        assertEquals("run-1-1", AgentEventContextResolver.resolveRunId(1L));
    }

    @Test
    void turnStartsAtOneWhenCountersEmpty() {
        assertEquals(1, AgentEventContextResolver.resolveTurn(0, 0));
    }

    @Test
    void turnAccountsReworkAndReassign() {
        // 1 + rework(2) + attemptTotal(3) = 6
        assertEquals(6, AgentEventContextResolver.resolveTurn(2, 3));
    }

    @Test
    void turnTreatsNullCountersAsZero() {
        // rework=1, attempt=null -> 1 + 1 + 0 = 2
        assertEquals(2, AgentEventContextResolver.resolveTurn(1, null));
        // rework=null, attempt=3 -> 1 + 0 + 3 = 4
        assertEquals(4, AgentEventContextResolver.resolveTurn(null, 3));
    }

    @Test
    void turnReturnsOneWhenBothCountersNull() {
        // 原「空 SubTask → 1」语义：调用方的可空实体折算为 (null, null) 后仍返回 1
        assertEquals(1, AgentEventContextResolver.resolveTurn(null, null));
    }
}
