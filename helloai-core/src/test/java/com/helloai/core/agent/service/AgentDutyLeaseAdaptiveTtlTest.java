package com.helloai.core.agent.service;

import com.helloai.common.config.AgentDutyLeaseProperties;
import com.helloai.common.constant.AgentDutyLeaseStatus;
import com.helloai.core.agent.entity.Agent;
import com.helloai.core.agent.entity.AgentDutyLease;
import com.helloai.core.agent.mapper.AgentMapper;
import com.helloai.core.agent.service.impl.AgentDutyLeaseServiceImpl;
import com.helloai.core.task.entity.SubTask;
import com.helloai.core.task.mapper.SubTaskMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import java.time.OffsetDateTime;
import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link AgentDutyLeaseService} 动态 TTL 自适应单元测试（E1，N12  第 2 段）。
 *
 * <p>覆盖：</p>
 * <ul>
 *   <li>resolveTtlMinutes：显式 TTL 优先；score 线性映射（低分短窗口 / 高分长窗口）；</li>
 *   <li>无 score 时按 consecutive_failure_count 折算表现分；开关关闭 / Agent 缺失兜底默认值；</li>
 *   <li>adaptiveRenew：无 ACTIVE 租约返回 null；在跑任务用最大窗口；空闲按表现分动态窗口。</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("AgentDutyLeaseService 动态 TTL 自适应（E1）")
class AgentDutyLeaseAdaptiveTtlTest {

    private static final long AGENT_ID = 1L;

    @Mock private AgentMapper agentMapper;
    @Mock private SubTaskMapper subTaskMapper;

    private AgentDutyLeaseProperties props;
    private AgentDutyLeaseService service;

    @BeforeEach
    void setUp() {
        props = new AgentDutyLeaseProperties();
        props.setAdaptiveTtlEnabled(true);
        props.setMinTtlMinutes(5);
        props.setMaxTtlMinutes(240);
        props.setDefaultTtlMinutes(30);
        props.setFullScore(100);
        service = spy(new AgentDutyLeaseServiceImpl(
                mock(ApplicationEventPublisher.class), agentMapper, subTaskMapper, props));
    }

    private void stubAgent(Integer score, Integer consecutiveFailures) {
        Agent agent = new Agent();
        agent.setId(AGENT_ID);
        agent.setScore(score);
        agent.setConsecutiveFailureCount(consecutiveFailures);
        when(agentMapper.selectById(AGENT_ID)).thenReturn(agent);
    }

    private AgentDutyLease stubActiveLease() {
        AgentDutyLease lease = new AgentDutyLease();
        lease.setId(10L);
        lease.setAgentId(AGENT_ID);
        lease.setStatus(AgentDutyLeaseStatus.ACTIVE);
        lease.setStartTime(OffsetDateTime.now());
        lease.setExpireTime(OffsetDateTime.now().plusMinutes(30));
        doReturn(lease).when(service).getActiveLease(AGENT_ID);
        doReturn(lease).when(service).renewLease(eq(AGENT_ID), anyInt());
        return lease;
    }

    @Test
    @DisplayName("显式 TTL 永远优先于动态推断")
    void explicitTtlWinsOverAdaptive() {
        // 显式 TTL 直接短路，不触碰 Agent 表现推断（无需 stub agentMapper）
        assertThat(service.resolveTtlMinutes(AGENT_ID, 10)).isEqualTo(10);
    }

    @Test
    @DisplayName("高分 Agent（score=100）得到最大窗口")
    void highScoreGetsMaxWindow() {
        stubAgent(100, 0);
        assertThat(service.resolveTtlMinutes(AGENT_ID, null)).isEqualTo(240);
    }

    @Test
    @DisplayName("低分 Agent（score=0）得到最小窗口")
    void lowScoreGetsMinWindow() {
        stubAgent(0, 0);
        assertThat(service.resolveTtlMinutes(AGENT_ID, null)).isEqualTo(5);
    }

    @Test
    @DisplayName("中分 Agent（score=50）线性映射到中间窗口")
    void midScoreGetsLinearWindow() {
        stubAgent(50, 0);
        // 5 + (240-5) * 50 / 100 = 5 + 117 = 122
        assertThat(service.resolveTtlMinutes(AGENT_ID, null)).isEqualTo(122);
    }

    @Test
    @DisplayName("无 score 且零失败 → 视为满分 → 最大窗口")
    void noScoreZeroFailuresGetsMaxWindow() {
        stubAgent(null, 0);
        assertThat(service.resolveTtlMinutes(AGENT_ID, null)).isEqualTo(240);
    }

    @Test
    @DisplayName("无 score 且连续失败 5 次 → 表现分归零 → 最小窗口")
    void noScoreHighFailuresGetsMinWindow() {
        stubAgent(null, 5);
        assertThat(service.resolveTtlMinutes(AGENT_ID, null)).isEqualTo(5);
    }

    @Test
    @DisplayName("自适应开关关闭 → 兜底默认窗口")
    void disabledAdaptiveFallsBackToDefault() {
        props.setAdaptiveTtlEnabled(false);
        // 开关关闭直接返回默认值，不触碰 Agent 表现推断（无需 stub agentMapper）
        assertThat(service.resolveTtlMinutes(AGENT_ID, null)).isEqualTo(30);
    }

    @Test
    @DisplayName("Agent 记录不存在 → 兜底默认窗口")
    void agentNotFoundFallsBackToDefault() {
        when(agentMapper.selectById(AGENT_ID)).thenReturn(null);
        assertThat(service.resolveTtlMinutes(AGENT_ID, null)).isEqualTo(30);
    }

    @Test
    @DisplayName("adaptiveRenew：无 ACTIVE 租约 → 返回 null 且不续约")
    void adaptiveRenewNoActiveLeaseReturnsNull() {
        doReturn(null).when(service).getActiveLease(AGENT_ID);
        assertThat(service.adaptiveRenew(AGENT_ID)).isNull();
        verify(service, never()).renewLease(eq(AGENT_ID), anyInt());
    }

    @Test
    @DisplayName("adaptiveRenew：有在跑子任务 → 用最大窗口续约")
    void adaptiveRenewWithInFlightUsesMaxWindow() {
        stubActiveLease();
        SubTask inFlight = new SubTask();
        inFlight.setId(99L);
        when(subTaskMapper.selectInFlightByAgent(AGENT_ID, 1)).thenReturn(List.of(inFlight));

        service.adaptiveRenew(AGENT_ID);

        verify(service).renewLease(AGENT_ID, 240);
    }

    @Test
    @DisplayName("adaptiveRenew：空闲 → 按表现分动态窗口续约")
    void adaptiveRenewIdleUsesDynamicWindow() {
        stubActiveLease();
        stubAgent(100, 0);
        when(subTaskMapper.selectInFlightByAgent(AGENT_ID, 1)).thenReturn(Collections.emptyList());

        service.adaptiveRenew(AGENT_ID);

        verify(service).renewLease(AGENT_ID, 240);
    }

    @Test
    @DisplayName("adaptiveRenew：空闲低分 Agent → 短窗口续约")
    void adaptiveRenewIdleLowScoreUsesShortWindow() {
        stubActiveLease();
        stubAgent(0, 0);
        when(subTaskMapper.selectInFlightByAgent(AGENT_ID, 1)).thenReturn(Collections.emptyList());

        service.adaptiveRenew(AGENT_ID);

        verify(service).renewLease(AGENT_ID, 5);
    }
}
