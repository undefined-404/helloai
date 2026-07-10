package com.helloai.core.service;

import com.helloai.common.constant.AgentOnlineStatus;
import com.helloai.common.constant.AgentAccessType;
import com.helloai.common.constant.AgentRole;
import com.helloai.common.constant.AgentStatus;
import com.helloai.core.entity.Agent;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/**
 * AgentSelector 单元测试（v2.4 §4.10）。
 *
 * <p>验证 pickAlternative 的过滤逻辑：
 * SLEEPING/OFFLINE/DISABLED/熔断中的 Agent 被正确跳过，
 * 按 score DESC 选最高分替代。</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("AgentSelector.pickAlternative")
class AgentSelectorTest {

    @Mock
    private AgentService agentService;

    @Mock
    private CircuitBreakerRegistry circuitBreakerRegistry;

    private AgentSelector agentSelector;

    @BeforeEach
    void setUp() {
        agentSelector = new AgentSelector(agentService, circuitBreakerRegistry);
    }

    // ════════════════════════════════════════════════════════════
    //  Helper
    // ════════════════════════════════════════════════════════════

    private Agent agent(Long id, Integer score, AgentOnlineStatus onlineStatus, AgentStatus status) {
        Agent a = new Agent();
        a.setId(id);
        a.setName("agent-" + id);
        a.setRole(AgentRole.EXECUTOR);
        a.setAccessType(AgentAccessType.CLI_CLIENT);
        a.setScore(score);
        a.setOnlineStatus(onlineStatus);
        a.setStatus(status);
        return a;
    }

    // ════════════════════════════════════════════════════════════
    //  Tests
    // ════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("正常场景")
    class HappyPath {

        @Test
        @DisplayName("跳过 excludeAgentId，选最高分")
        void shouldSkipExcludedAndPickHighestScore() {
            Agent excluded = agent(1L, 90, AgentOnlineStatus.ONLINE, AgentStatus.ACTIVE);
            Agent middle = agent(2L, 80, AgentOnlineStatus.ONLINE, AgentStatus.ACTIVE);
            Agent best = agent(3L, 95, AgentOnlineStatus.ONLINE, AgentStatus.ACTIVE);

            when(agentService.listByRole(AgentRole.EXECUTOR))
                    .thenReturn(List.of(excluded, middle, best));
            when(circuitBreakerRegistry.find("agentDispatch-2")).thenReturn(Optional.empty());
            when(circuitBreakerRegistry.find("agentDispatch-3")).thenReturn(Optional.empty());

            Agent result = agentSelector.pickAlternative(1L, AgentRole.EXECUTOR);

            assertThat(result).isNotNull();
            assertThat(result.getId()).isEqualTo(3L);
            assertThat(result.getScore()).isEqualTo(95);
        }
    }

    @Nested
    @DisplayName("过滤 SLEEPING / OFFLINE")
    class StatusFiltering {

        @Test
        @DisplayName("跳过 SLEEPING Agent")
        void shouldSkipSleeping() {
            Agent sleeping = agent(2L, 80, AgentOnlineStatus.SLEEPING, AgentStatus.ACTIVE);
            Agent online = agent(3L, 70, AgentOnlineStatus.ONLINE, AgentStatus.ACTIVE);

            when(agentService.listByRole(AgentRole.EXECUTOR))
                    .thenReturn(List.of(sleeping, online));
            when(circuitBreakerRegistry.find("agentDispatch-3")).thenReturn(Optional.empty());

            Agent result = agentSelector.pickAlternative(1L, AgentRole.EXECUTOR);

            assertThat(result).isNotNull();
            assertThat(result.getId()).isEqualTo(3L);
        }

        @Test
        @DisplayName("跳过 OFFLINE Agent")
        void shouldSkipOffline() {
            Agent offline = agent(2L, 90, AgentOnlineStatus.OFFLINE, AgentStatus.ACTIVE);
            Agent online = agent(3L, 60, AgentOnlineStatus.ONLINE, AgentStatus.ACTIVE);

            when(agentService.listByRole(AgentRole.EXECUTOR))
                    .thenReturn(List.of(offline, online));
            when(circuitBreakerRegistry.find("agentDispatch-3")).thenReturn(Optional.empty());

            Agent result = agentSelector.pickAlternative(1L, AgentRole.EXECUTOR);

            assertThat(result).isNotNull();
            assertThat(result.getId()).isEqualTo(3L);
        }

        @Test
        @DisplayName("API_KEY_LLM 的 OFFLINE 不参与过滤")
        void shouldKeepOfflineApiKeyLlmCandidate() {
            Agent offlineApi = agent(2L, 90, AgentOnlineStatus.OFFLINE, AgentStatus.ACTIVE);
            offlineApi.setAccessType(AgentAccessType.API_KEY_LLM);
            Agent onlineCli = agent(3L, 60, AgentOnlineStatus.ONLINE, AgentStatus.ACTIVE);

            when(agentService.listByRole(AgentRole.EXECUTOR))
                    .thenReturn(List.of(offlineApi, onlineCli));
            when(circuitBreakerRegistry.find("agentDispatch-2")).thenReturn(Optional.empty());
            when(circuitBreakerRegistry.find("agentDispatch-3")).thenReturn(Optional.empty());

            Agent result = agentSelector.pickAlternative(1L, AgentRole.EXECUTOR);

            assertThat(result).isNotNull();
            assertThat(result.getId()).isEqualTo(2L);
        }

        @Test
        @DisplayName("跳过 DISABLED Agent")
        void shouldSkipDisabled() {
            Agent disabled = agent(2L, 90, AgentOnlineStatus.ONLINE, AgentStatus.DISABLED);
            Agent active = agent(3L, 60, AgentOnlineStatus.ONLINE, AgentStatus.ACTIVE);

            when(agentService.listByRole(AgentRole.EXECUTOR))
                    .thenReturn(List.of(disabled, active));
            when(circuitBreakerRegistry.find("agentDispatch-3")).thenReturn(Optional.empty());

            Agent result = agentSelector.pickAlternative(1L, AgentRole.EXECUTOR);

            assertThat(result).isNotNull();
            assertThat(result.getId()).isEqualTo(3L);
        }
    }

    @Nested
    @DisplayName("过滤熔断中的 Agent")
    class CircuitBreakerFiltering {

        @Test
        @DisplayName("跳过熔断器 OPEN 的 Agent")
        void shouldSkipCircuitOpenAgent() {
            Agent broken = agent(2L, 90, AgentOnlineStatus.ONLINE, AgentStatus.ACTIVE);
            Agent healthy = agent(3L, 70, AgentOnlineStatus.ONLINE, AgentStatus.ACTIVE);

            when(agentService.listByRole(AgentRole.EXECUTOR))
                    .thenReturn(List.of(broken, healthy));
            when(circuitBreakerRegistry.find("agentDispatch-3")).thenReturn(Optional.empty());

            // Mock: agent 2 熔断器已打开
            CircuitBreaker mockOpenCb = org.mockito.Mockito.mock(CircuitBreaker.class);
            when(mockOpenCb.getState()).thenReturn(CircuitBreaker.State.OPEN);
            when(circuitBreakerRegistry.find("agentDispatch-2"))
                    .thenReturn(Optional.of(mockOpenCb));

            Agent result = agentSelector.pickAlternative(1L, AgentRole.EXECUTOR);

            assertThat(result).isNotNull();
            assertThat(result.getId()).isEqualTo(3L);
        }

        @Test
        @DisplayName("熔断器 CLOSED/HALF_OPEN 的 Agent 视为可用")
        void shouldAcceptCircuitClosedAgent() {
            Agent recovering = agent(2L, 85, AgentOnlineStatus.ONLINE, AgentStatus.ACTIVE);

            when(agentService.listByRole(AgentRole.EXECUTOR))
                    .thenReturn(List.of(recovering));
            CircuitBreaker mockHalfOpenCb = org.mockito.Mockito.mock(CircuitBreaker.class);
            when(mockHalfOpenCb.getState()).thenReturn(CircuitBreaker.State.HALF_OPEN);
            when(circuitBreakerRegistry.find("agentDispatch-2"))
                    .thenReturn(Optional.of(mockHalfOpenCb));

            Agent result = agentSelector.pickAlternative(1L, AgentRole.EXECUTOR);

            assertThat(result).isNotNull();
            assertThat(result.getId()).isEqualTo(2L);
        }
    }

    @Nested
    @DisplayName("边界场景")
    class EdgeCases {

        @Test
        @DisplayName("所有候选都被过滤 → 返回 null")
        void shouldReturnNullWhenNoCandidates() {
            Agent sleeping = agent(2L, 80, AgentOnlineStatus.SLEEPING, AgentStatus.ACTIVE);
            Agent offline = agent(3L, 70, AgentOnlineStatus.OFFLINE, AgentStatus.ACTIVE);

            when(agentService.listByRole(AgentRole.EXECUTOR))
                    .thenReturn(List.of(sleeping, offline));

            Agent result = agentSelector.pickAlternative(1L, AgentRole.EXECUTOR);

            assertThat(result).isNull();
        }

        @Test
        @DisplayName("role=null 时使用 listActive")
        void shouldUseListActiveWhenRoleIsNull() {
            Agent active = agent(2L, 75, AgentOnlineStatus.ONLINE, AgentStatus.ACTIVE);

            when(agentService.listActive()).thenReturn(List.of(active));
            when(circuitBreakerRegistry.find("agentDispatch-2")).thenReturn(Optional.empty());

            Agent result = agentSelector.pickAlternative(1L, null);

            assertThat(result).isNotNull();
            assertThat(result.getId()).isEqualTo(2L);
        }

        @Test
        @DisplayName("score 为 null 的 Agent 排在最后")
        void shouldHandleNullScore() {
            Agent withScore = agent(2L, 50, AgentOnlineStatus.ONLINE, AgentStatus.ACTIVE);
            Agent nullScore = agent(3L, null, AgentOnlineStatus.ONLINE, AgentStatus.ACTIVE);

            when(agentService.listByRole(AgentRole.EXECUTOR))
                    .thenReturn(List.of(withScore, nullScore));
            when(circuitBreakerRegistry.find("agentDispatch-2")).thenReturn(Optional.empty());

            Agent result = agentSelector.pickAlternative(1L, AgentRole.EXECUTOR);

            assertThat(result).isNotNull();
            assertThat(result.getId()).isEqualTo(2L);
        }
    }
}
