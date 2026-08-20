package com.helloai.core.agent.service;

import com.helloai.common.config.AgentFallbackProperties;
import com.helloai.common.config.AgentHealthProperties;
import com.helloai.common.constant.AgentAccessType;
import com.helloai.common.constant.AgentOnlineStatus;
import com.helloai.common.constant.AgentRole;
import com.helloai.common.constant.AgentStatus;
import com.helloai.core.agent.entity.Agent;
import com.helloai.core.agent.mapper.AgentMapper;
import com.helloai.core.agent.observability.ExternalAgentFailureTracker;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.OffsetDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * {@link ExternalAgentFailureTracker} 单元测试（N11 Phase 2C）。
 *
 * <p>覆盖：</p>
 * <ul>
 *   <li>recordFailure / recordSuccess / markFallbackTriggered 调用映射到对应 SQL；</li>
 *   <li>enabled=false 时整套计数短路为 no-op；</li>
 *   <li>findFallbackCandidates 透传 threshold + cooldownCutoff；</li>
 *   <li>shouldFallback 纯函数式判定（threshold + cooldown + accessType）；</li>
 *   <li>agentId 为 null 时不抛异常。</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
class ExternalAgentFailureTrackerTest {

    @Mock
    private AgentMapper agentMapper;

    private AgentFallbackProperties properties;
    private AgentHealthProperties healthProperties;
    private ExternalAgentFailureTracker tracker;

    @BeforeEach
    void setUp() {
        properties = new AgentFallbackProperties();
        properties.setEnabled(true);
        properties.setFailureThreshold(3);
        properties.setCooldownMinutes(10);
        properties.setScanIntervalMs(60_000L);

        // §4.1：共享 5 分钟健康阈值；测试用 5 分钟以贴近真实链路
        healthProperties = new AgentHealthProperties();
        healthProperties.setOfflineMinutes(5);

        tracker = new ExternalAgentFailureTracker(agentMapper, properties, healthProperties);
    }

    // ═══════════════════════════════════════════════════════════════
    //  计数侧
    // ═══════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("recordFailure / recordSuccess / markFallbackTriggered")
    class Counting {

        @Test
        @DisplayName("recordFailure 调用 incrementConsecutiveFailure")
        void shouldRecordFailure() {
            when(agentMapper.incrementConsecutiveFailure(eq(1L), any(OffsetDateTime.class))).thenReturn(1);

            tracker.recordFailure(1L);

            verify(agentMapper, times(1)).incrementConsecutiveFailure(eq(1L), any(OffsetDateTime.class));
        }

        @Test
        @DisplayName("recordSuccess 调用 resetConsecutiveFailure")
        void shouldRecordSuccess() {
            when(agentMapper.resetConsecutiveFailure(eq(2L), any(OffsetDateTime.class))).thenReturn(1);

            tracker.recordSuccess(2L);

            verify(agentMapper, times(1)).resetConsecutiveFailure(eq(2L), any(OffsetDateTime.class));
        }

        @Test
        @DisplayName("markFallbackTriggered 调用 markFallbackTriggered")
        void shouldMarkFallbackTriggered() {
            when(agentMapper.markFallbackTriggered(eq(3L), any(OffsetDateTime.class))).thenReturn(1);

            tracker.markFallbackTriggered(3L);

            verify(agentMapper, times(1)).markFallbackTriggered(eq(3L), any(OffsetDateTime.class));
        }

        @Test
        @DisplayName("agentId 为 null 时整套计数短路")
        void shouldSkipWhenAgentIdNull() {
            tracker.recordFailure(null);
            tracker.recordSuccess(null);
            tracker.markFallbackTriggered(null);

            verify(agentMapper, never()).incrementConsecutiveFailure(any(), any());
            verify(agentMapper, never()).resetConsecutiveFailure(any(), any());
            verify(agentMapper, never()).markFallbackTriggered(any(), any());
        }

        @Test
        @DisplayName("enabled=false 时整套计数短路")
        void shouldSkipWhenDisabled() {
            properties.setEnabled(false);

            tracker.recordFailure(1L);
            tracker.recordSuccess(1L);
            tracker.markFallbackTriggered(1L);

            verify(agentMapper, never()).incrementConsecutiveFailure(any(), any());
            verify(agentMapper, never()).resetConsecutiveFailure(any(), any());
            verify(agentMapper, never()).markFallbackTriggered(any(), any());
        }
    }

    // ═══════════════════════════════════════════════════════════════
    //  扫描侧
    // ═══════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("findFallbackCandidates")
    class FindCandidates {

        @Test
        @DisplayName("透传 threshold + cooldownCutoff + lastSeenCutoff（now - offlineMinutes）到 Mapper")
        void shouldDelegateToMapper() {
            Agent a = cliAgent(1L, 5, null);
            when(agentMapper.selectFallbackCandidates(
                    eq(3), any(OffsetDateTime.class), any(OffsetDateTime.class)))
                    .thenReturn(List.of(a));

            List<Agent> result = tracker.findFallbackCandidates();

            assertThat(result).hasSize(1).containsExactly(a);
            verify(agentMapper, times(1)).selectFallbackCandidates(
                    eq(3), any(OffsetDateTime.class), any(OffsetDateTime.class));
        }

        @Test
        @DisplayName("enabled=false 时返回空列表，不查 DB")
        void shouldReturnEmptyWhenDisabled() {
            properties.setEnabled(false);

            List<Agent> result = tracker.findFallbackCandidates();

            assertThat(result).isEmpty();
            verify(agentMapper, never()).selectFallbackCandidates(anyInt(), any(), any());
        }

        @Test
        @DisplayName("透传 threshold + cooldownCutoff + lastSeenCutoff（now - offlineMinutes）到 Mapper")
        void shouldDelegateThreeParamsToMapper() {
            Agent a = cliAgent(1L, 5, null, OffsetDateTime.now().minusMinutes(2));
            when(agentMapper.selectFallbackCandidates(eq(3), any(OffsetDateTime.class), any(OffsetDateTime.class)))
                    .thenReturn(List.of(a));

            List<Agent> result = tracker.findFallbackCandidates();

            assertThat(result).hasSize(1).containsExactly(a);
            verify(agentMapper, times(1)).selectFallbackCandidates(
                    eq(3), any(OffsetDateTime.class), any(OffsetDateTime.class));
        }

        @Test
        @DisplayName("offlineMinutes <= 0 时 lastSeenCutoff 退化为 MIN，不过滤心跳")
        void shouldBypassHeartbeatFilterWhenThresholdZero() {
            healthProperties.setOfflineMinutes(0);
            Agent a = cliAgent(1L, 5, null, null);
            when(agentMapper.selectFallbackCandidates(eq(3), any(OffsetDateTime.class), any(OffsetDateTime.class)))
                    .thenReturn(List.of(a));

            tracker.findFallbackCandidates();

            // 心跳过滤关闭，但参数仍会透传给 SQL（cutoff 为 MIN）
            verify(agentMapper, times(1)).selectFallbackCandidates(
                    eq(3), any(OffsetDateTime.class), any(OffsetDateTime.class));
        }
    }

    // ═══════════════════════════════════════════════════════════════
    //  shouldFallback 纯函数
    // ═══════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("shouldFallback 纯函数判定")
    class ShouldFallback {

        @Test
        @DisplayName("CLI_CLIENT + failure>=threshold + 无 last_fallback_at → true")
        void shouldReturnTrueWhenFresh() {
            Agent a = cliAgent(1L, 3, null);
            assertThat(tracker.shouldFallback(a)).isTrue();
        }

        @Test
        @DisplayName("CLI_CLIENT + failure<threshold → false")
        void shouldReturnFalseWhenBelowThreshold() {
            Agent a = cliAgent(1L, 2, null);
            assertThat(tracker.shouldFallback(a)).isFalse();
        }

        @Test
        @DisplayName("API_KEY_LLM → false（无论计数）")
        void shouldReturnFalseForApiKeyLlm() {
            Agent a = new Agent();
            a.setId(1L);
            a.setAccessType(AgentAccessType.API_KEY_LLM);
            a.setConsecutiveFailureCount(99);
            assertThat(tracker.shouldFallback(a)).isFalse();
        }

        @Test
        @DisplayName("WEB_BROWSER → false（无论计数）")
        void shouldReturnFalseForWebBrowser() {
            Agent a = new Agent();
            a.setId(1L);
            a.setAccessType(AgentAccessType.WEB_BROWSER);
            a.setConsecutiveFailureCount(99);
            assertThat(tracker.shouldFallback(a)).isFalse();
        }

        @Test
        @DisplayName("CLI_CLIENT + 在 cooldown 内 → false")
        void shouldReturnFalseWhenInCooldown() {
            Agent a = cliAgent(1L, 5, OffsetDateTime.now().minusMinutes(2));
            assertThat(tracker.shouldFallback(a)).isFalse();
        }

        @Test
        @DisplayName("CLI_CLIENT + 已过 cooldown → true")
        void shouldReturnTrueWhenCooldownPassed() {
            Agent a = cliAgent(1L, 5, OffsetDateTime.now().minusMinutes(15));
            assertThat(tracker.shouldFallback(a)).isTrue();
        }

        @Test
        @DisplayName("agent=null → false 不抛异常")
        void shouldReturnFalseForNull() {
            assertThat(tracker.shouldFallback(null)).isFalse();
        }

        @Test
        @DisplayName("consecutiveFailureCount=null 视为 0")
        void shouldTreatNullCountAsZero() {
            Agent a = cliAgent(1L, null, null, OffsetDateTime.now().minusMinutes(2));
            assertThat(tracker.shouldFallback(a)).isFalse();
        }

        // §4.1：心跳新鲜度规则（与 SQL selectFallbackCandidates 完全一致）

        @Test
        @DisplayName("CLI_CLIENT + last_seen_time=null → false（心跳未知视为过期）")
        void shouldReturnFalseWhenLastSeenNull() {
            Agent a = cliAgent(1L, 5, null, null);
            assertThat(tracker.shouldFallback(a)).isFalse();
        }

        @Test
        @DisplayName("CLI_CLIENT + last_seen_time 在阈值内（4 分钟前）→ true")
        void shouldReturnTrueWhenHeartbeatFresh() {
            Agent a = cliAgent(1L, 5, null, OffsetDateTime.now().minusMinutes(4));
            assertThat(tracker.shouldFallback(a)).isTrue();
        }

        @Test
        @DisplayName("CLI_CLIENT + last_seen_time 超过阈值（6 分钟前）→ false")
        void shouldReturnFalseWhenHeartbeatStale() {
            Agent a = cliAgent(1L, 5, null, OffsetDateTime.now().minusMinutes(6));
            assertThat(tracker.shouldFallback(a)).isFalse();
        }

        @Test
        @DisplayName("CLI_CLIENT + 阈值边界（exactly 5 分钟前）→ false（严格 after）")
        void shouldReturnFalseAtBoundary() {
            Agent a = cliAgent(1L, 5, null, OffsetDateTime.now().minusMinutes(5));
            assertThat(tracker.shouldFallback(a)).isFalse();
        }

        @Test
        @DisplayName("offlineMinutes <= 0 时心跳过滤关闭 → null last_seen_time 也可 true")
        void shouldBypassHeartbeatFilterWhenZeroThreshold() {
            healthProperties.setOfflineMinutes(0);
            Agent a = cliAgent(1L, 5, null, null);
            assertThat(tracker.shouldFallback(a)).isTrue();
        }
    }

    // ═══════════════════════════════════════════════════════════════
    //  工具
    // ═══════════════════════════════════════════════════════════════

    private static Agent cliAgent(Long id, Integer failureCount, OffsetDateTime lastFallbackAt) {
        return cliAgent(id, failureCount, lastFallbackAt, OffsetDateTime.now().minusMinutes(2));
    }

    /**
     * 心跳可控构造器。
     *
     * @param lastSeenTime Agent.last_seen_time；null 表示从未心跳
     */
    private static Agent cliAgent(Long id, Integer failureCount,
                                  OffsetDateTime lastFallbackAt,
                                  OffsetDateTime lastSeenTime) {
        Agent a = new Agent();
        a.setId(id);
        a.setName("cli-agent-" + id);
        a.setRole(AgentRole.EXECUTOR);
        a.setAccessType(AgentAccessType.CLI_CLIENT);
        a.setStatus(AgentStatus.ACTIVE);
        a.setOnlineStatus(AgentOnlineStatus.ONLINE);
        a.setConsecutiveFailureCount(failureCount);
        a.setLastFallbackTime(lastFallbackAt);
        a.setLastSeenTime(lastSeenTime);
        return a;
    }
}
