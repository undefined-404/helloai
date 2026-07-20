package com.helloai.core.agent.executor;

import com.helloai.common.constant.AgentOnlineStatus;
import com.helloai.common.constant.AgentAccessType;
import com.helloai.common.constant.AgentRole;
import com.helloai.common.constant.AgentStatus;
import com.helloai.common.config.AgentDispatchProperties;
import com.helloai.core.agent.entity.Agent;
import com.helloai.core.agent.entity.AgentDutyLease;
import com.helloai.common.constant.AgentDutyLeaseStatus;
import com.helloai.common.constant.WorkMode;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import com.helloai.core.agent.service.AgentService;
import com.helloai.core.agent.service.AgentDutyLeaseService;

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

    @Mock
    private AgentDutyLeaseService agentDutyLeaseService;

    private AgentSelector agentSelector;

    @BeforeEach
    void setUp() {
        AgentDispatchProperties props = new AgentDispatchProperties();
        props.setPreferExternal(false);
        props.setRequireIdle(false);
        // 防御式默认 stub：仅在多候选 comparator 排序时被 dutyRank 调用，
        // 单候选用例不会走到，用 lenient() 避开 STRICT_STUBS 下的 UnnecessaryStubbing。
        lenient().when(agentDutyLeaseService.isOnDuty(anyLong())).thenReturn(false);
        agentSelector = new AgentSelector(agentService, circuitBreakerRegistry, props, agentDutyLeaseService);
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
        // v2.6 §4.1：默认心跳新鲜（now），避免现有 helper 出来的 CLI_CLIENT 因
        // last_seen_time=null 被 isHeartbeatFresh 过滤；心跳用例请改用 agentWithHeartbeat(...)
        a.setLastSeenTime(OffsetDateTime.now().minus(2, ChronoUnit.MINUTES));
        return a;
    }

    /**
     * 心跳新鲜度测试专用构造器。明确指定 last_seen_time，方便测试 v2.6 §4.1
     * AgentSelector 的心跳新鲜度过滤逻辑。
     *
     * @param lastSeenMinutesAgo 距今分钟数；null 表示 last_seen_time 为 null
     */
    private Agent agentWithHeartbeat(Long id, Integer score, AgentOnlineStatus onlineStatus,
                                     AgentStatus status, Long lastSeenMinutesAgo) {
        Agent a = agent(id, score, onlineStatus, status);
        if (lastSeenMinutesAgo != null) {
            a.setLastSeenTime(OffsetDateTime.now().minus(lastSeenMinutesAgo, ChronoUnit.MINUTES));
        } else {
            a.setLastSeenTime(null);
        }
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

    @Nested
    @DisplayName("N12 P1 STRICT 独占报锁")
    class StrictDutyFiltering {

        private AgentDutyLease strictLease(Long agentId) {
            AgentDutyLease lease = new AgentDutyLease();
            lease.setAgentId(agentId);
            lease.setStatus(AgentDutyLeaseStatus.ACTIVE);
            lease.setWorkMode(WorkMode.STRICT.name());
            return lease;
        }

        private AgentDutyLease autoLease(Long agentId) {
            AgentDutyLease lease = new AgentDutyLease();
            lease.setAgentId(agentId);
            lease.setStatus(AgentDutyLeaseStatus.ACTIVE);
            lease.setWorkMode(WorkMode.AUTO.name());
            return lease;
        }

        @Test
        @DisplayName("跳过以 STRICT 模式在岗的 Agent，不进入替补池")
        void shouldSkipStrictOnDutyAgent() {
            Agent strictAgent = agent(2L, 90, AgentOnlineStatus.ONLINE, AgentStatus.ACTIVE);
            Agent autoAgent = agent(3L, 60, AgentOnlineStatus.ONLINE, AgentStatus.ACTIVE);

            when(agentService.listByRole(AgentRole.EXECUTOR))
                    .thenReturn(List.of(strictAgent, autoAgent));
            when(agentDutyLeaseService.getActiveLease(2L)).thenReturn(strictLease(2L));
            when(agentDutyLeaseService.getActiveLease(3L)).thenReturn(autoLease(3L));
            when(circuitBreakerRegistry.find("agentDispatch-3")).thenReturn(Optional.empty());

            Agent result = agentSelector.pickAlternative(1L, AgentRole.EXECUTOR);

            assertThat(result).isNotNull();
            assertThat(result.getId()).isEqualTo(3L);
        }

        @Test
        @DisplayName("所有候选都是 STRICT → 返回 null，不出动任何 Agent 顶班")
        void shouldReturnNullWhenAllCandidatesStrict() {
            Agent strict1 = agent(2L, 90, AgentOnlineStatus.ONLINE, AgentStatus.ACTIVE);
            Agent strict2 = agent(3L, 80, AgentOnlineStatus.ONLINE, AgentStatus.ACTIVE);

            when(agentService.listByRole(AgentRole.EXECUTOR))
                    .thenReturn(List.of(strict1, strict2));
            when(agentDutyLeaseService.getActiveLease(2L)).thenReturn(strictLease(2L));
            when(agentDutyLeaseService.getActiveLease(3L)).thenReturn(strictLease(3L));

            Agent result = agentSelector.pickAlternative(1L, AgentRole.EXECUTOR);

            assertThat(result).isNull();
        }

        @Test
        @DisplayName("无租约的 Agent 视为 AUTO，正常参与替补池")
        void shouldTreatNoLeaseAgentAsAuto() {
            Agent strict = agent(2L, 90, AgentOnlineStatus.ONLINE, AgentStatus.ACTIVE);
            Agent noLease = agent(3L, 60, AgentOnlineStatus.ONLINE, AgentStatus.ACTIVE);

            when(agentService.listByRole(AgentRole.EXECUTOR))
                    .thenReturn(List.of(strict, noLease));
            when(agentDutyLeaseService.getActiveLease(2L)).thenReturn(strictLease(2L));
            when(agentDutyLeaseService.getActiveLease(3L)).thenReturn(null);
            when(circuitBreakerRegistry.find("agentDispatch-3")).thenReturn(Optional.empty());

            Agent result = agentSelector.pickAlternative(1L, AgentRole.EXECUTOR);

            assertThat(result).isNotNull();
            assertThat(result.getId()).isEqualTo(3L);
        }

        @Test
        @DisplayName("历史脏数据：work_mode=null/空串/未识别 → lenient 按 AUTO，不阻断选择")
        void shouldLenientParseDirtyWorkMode() {
            Agent dirty = agent(2L, 90, AgentOnlineStatus.ONLINE, AgentStatus.ACTIVE);
            Agent clean = agent(3L, 60, AgentOnlineStatus.ONLINE, AgentStatus.ACTIVE);

            AgentDutyLease dirtyLease = new AgentDutyLease();
            dirtyLease.setAgentId(2L);
            dirtyLease.setStatus(AgentDutyLeaseStatus.ACTIVE);
            dirtyLease.setWorkMode("garbage_legacy_value");

            when(agentService.listByRole(AgentRole.EXECUTOR))
                    .thenReturn(List.of(dirty, clean));
            when(agentDutyLeaseService.getActiveLease(2L)).thenReturn(dirtyLease);
            when(agentDutyLeaseService.getActiveLease(3L)).thenReturn(null);
            when(circuitBreakerRegistry.find("agentDispatch-2")).thenReturn(Optional.empty());
            when(circuitBreakerRegistry.find("agentDispatch-3")).thenReturn(Optional.empty());

            Agent result = agentSelector.pickAlternative(1L, AgentRole.EXECUTOR);

            // dirty 被当作 AUTO，高分胜出 → agent 2
            assertThat(result).isNotNull();
            assertThat(result.getId()).isEqualTo(2L);
        }

        @Test
        @DisplayName("lease 查询异常 → 防御式降级，不阻断选择")
        void shouldFallbackWhenLeaseQueryThrows() {
            Agent a = agent(2L, 90, AgentOnlineStatus.ONLINE, AgentStatus.ACTIVE);

            when(agentService.listByRole(AgentRole.EXECUTOR))
                    .thenReturn(List.of(a));
            when(agentDutyLeaseService.getActiveLease(2L))
                    .thenThrow(new RuntimeException("db down"));
            when(circuitBreakerRegistry.find("agentDispatch-2")).thenReturn(Optional.empty());

            Agent result = agentSelector.pickAlternative(1L, AgentRole.EXECUTOR);

            assertThat(result).isNotNull();
            assertThat(result.getId()).isEqualTo(2L);
        }
    }

    @Nested
    @DisplayName("调度策略 3：prefer-external / force-access-type / require-idle / pickPreferred")
    class DispatchPolicy {

        private AgentSelector policySelector;

        @BeforeEach
        void initPolicySelector() {
            // 重新构造一个“策略全开”的 Selector，覆盖默认 setUp 的 preferExternal=false/requireIdle=false
            AgentDispatchProperties policyProps = new AgentDispatchProperties();
            policyProps.setPreferExternal(true);
            policyProps.setRequireIdle(true);
            policyProps.setForceAccessType(null);
            policySelector = new AgentSelector(agentService, circuitBreakerRegistry, policyProps, agentDutyLeaseService);
        }

        private Agent agentWith(Long id, Integer score,
                                AgentOnlineStatus onlineStatus, AgentStatus status,
                                AgentAccessType accessType) {
            Agent a = agent(id, score, onlineStatus, status);
            a.setAccessType(accessType);
            return a;
        }

        @Test
        @DisplayName("preferExternal=true：CLI_CLIENT 优先于分数更高的 API_KEY_LLM")
        void shouldPreferHigherAccessTypeRankOverHigherScore() {
            Agent cli = agentWith(2L, 80, AgentOnlineStatus.ONLINE, AgentStatus.ACTIVE,
                    AgentAccessType.CLI_CLIENT);
            Agent api = agentWith(3L, 95, AgentOnlineStatus.ONLINE, AgentStatus.ACTIVE,
                    AgentAccessType.API_KEY_LLM);

            when(agentService.listByRole(AgentRole.EXECUTOR))
                    .thenReturn(List.of(cli, api));
            // requireIdle=true 时会调 inProgressCount
            when(agentService.inProgressCount(2L)).thenReturn(0);
            when(agentService.inProgressCount(3L)).thenReturn(0);
            when(circuitBreakerRegistry.find("agentDispatch-2")).thenReturn(Optional.empty());
            when(circuitBreakerRegistry.find("agentDispatch-3")).thenReturn(Optional.empty());

            Agent result = policySelector.pickAlternative(1L, AgentRole.EXECUTOR);

            assertThat(result).isNotNull();
            assertThat(result.getId()).isEqualTo(2L);
            assertThat(result.getAccessType()).isEqualTo(AgentAccessType.CLI_CLIENT);
        }

        @Test
        @DisplayName("forceAccessType=API_KEY_LLM：CLI_CLIENT 被过滤，只剩 API_KEY_LLM")
        void shouldFilterOutNonMatchingAccessType() {
            Agent cli = agentWith(2L, 100, AgentOnlineStatus.ONLINE, AgentStatus.ACTIVE,
                    AgentAccessType.CLI_CLIENT);
            Agent api = agentWith(3L, 60, AgentOnlineStatus.ONLINE, AgentStatus.ACTIVE,
                    AgentAccessType.API_KEY_LLM);

            // 本用例单独构造一个 forceAccessType=API_KEY_LLM、requireIdle=false 的 Selector，
            // 隔离于 initPolicySelector 的 requireIdle=true
            AgentDispatchProperties forceProps = new AgentDispatchProperties();
            forceProps.setPreferExternal(false);
            forceProps.setRequireIdle(false);
            forceProps.setForceAccessType(AgentAccessType.API_KEY_LLM);
            AgentSelector forceSelector =
                    new AgentSelector(agentService, circuitBreakerRegistry, forceProps, agentDutyLeaseService);

            when(agentService.listByRole(AgentRole.EXECUTOR))
                    .thenReturn(List.of(cli, api));
            when(circuitBreakerRegistry.find("agentDispatch-3")).thenReturn(Optional.empty());

            Agent result = forceSelector.pickAlternative(1L, AgentRole.EXECUTOR);

            assertThat(result).isNotNull();
            assertThat(result.getId()).isEqualTo(3L);
            assertThat(result.getAccessType()).isEqualTo(AgentAccessType.API_KEY_LLM);
        }

        @Test
        @DisplayName("requireIdle=true：inProgressCount>0 的 Agent 被跳过")
        void shouldSkipBusyAgentWhenRequireIdleEnabled() {
            Agent busy = agentWith(2L, 100, AgentOnlineStatus.ONLINE, AgentStatus.ACTIVE,
                    AgentAccessType.CLI_CLIENT);
            Agent idle = agentWith(3L, 50, AgentOnlineStatus.ONLINE, AgentStatus.ACTIVE,
                    AgentAccessType.CLI_CLIENT);

            when(agentService.listByRole(AgentRole.EXECUTOR))
                    .thenReturn(List.of(busy, idle));
            when(agentService.inProgressCount(2L)).thenReturn(3); // 忙
            when(agentService.inProgressCount(3L)).thenReturn(0); // 闲
            when(circuitBreakerRegistry.find("agentDispatch-3")).thenReturn(Optional.empty());

            Agent result = policySelector.pickAlternative(1L, AgentRole.EXECUTOR);

            assertThat(result).isNotNull();
            assertThat(result.getId()).isEqualTo(3L);
        }

        @Test
        @DisplayName("pickPreferred：不传 excludeAgentId 时命中首位候选（preferExternal=true）")
        void pickPreferredShouldRespectPreferExternal() {
            Agent cli = agentWith(2L, 70, AgentOnlineStatus.ONLINE, AgentStatus.ACTIVE,
                    AgentAccessType.CLI_CLIENT);
            Agent api = agentWith(3L, 95, AgentOnlineStatus.ONLINE, AgentStatus.ACTIVE,
                    AgentAccessType.API_KEY_LLM);

            when(agentService.listByRole(AgentRole.EXECUTOR))
                    .thenReturn(List.of(api, cli));
            when(agentService.inProgressCount(2L)).thenReturn(0);
            when(agentService.inProgressCount(3L)).thenReturn(0);
            when(circuitBreakerRegistry.find("agentDispatch-2")).thenReturn(Optional.empty());
            when(circuitBreakerRegistry.find("agentDispatch-3")).thenReturn(Optional.empty());

            Agent result = policySelector.pickPreferred(AgentRole.EXECUTOR);

            assertThat(result).isNotNull();
            assertThat(result.getId()).isEqualTo(2L);
            assertThat(result.getAccessType()).isEqualTo(AgentAccessType.CLI_CLIENT);
        }
    }

    /**
     * v2.6 §4.1 心跳语义对齐：AgentSelector 必须按 last_seen_time 新鲜度过滤。
     * 默认阈值 10 分钟，API_KEY_LLM 豁免。
     */
    @Nested
    @DisplayName("v2.6 心跳新鲜度过滤（last_seen_time）")
    class HeartbeatFreshness {

        @Test
        @DisplayName("CLI_CLIENT last_seen_time=null → 视为不新鲜，被跳过")
        void shouldSkipCliAgentWithNullLastSeenTime() {
            Agent nullLastSeen = agentWithHeartbeat(2L, 90, AgentOnlineStatus.ONLINE,
                    AgentStatus.ACTIVE, null);

            when(agentService.listByRole(AgentRole.EXECUTOR))
                    .thenReturn(List.of(nullLastSeen));

            Agent result = agentSelector.pickAlternative(1L, AgentRole.EXECUTOR);

            assertThat(result).isNull();
        }

        @Test
        @DisplayName("CLI_CLIENT last_seen_time=15分钟前（>10min 默认阈值） → 被跳过")
        void shouldSkipCliAgentWithStaleLastSeenTime() {
            Agent stale = agentWithHeartbeat(2L, 90, AgentOnlineStatus.ONLINE,
                    AgentStatus.ACTIVE, 15L);

            when(agentService.listByRole(AgentRole.EXECUTOR))
                    .thenReturn(List.of(stale));

            Agent result = agentSelector.pickAlternative(1L, AgentRole.EXECUTOR);

            assertThat(result).isNull();
        }

        @Test
        @DisplayName("CLI_CLIENT last_seen_time=5分钟前（<10min 默认阈值） → 保留")
        void shouldKeepCliAgentWithFreshLastSeenTime() {
            Agent fresh = agentWithHeartbeat(2L, 90, AgentOnlineStatus.ONLINE,
                    AgentStatus.ACTIVE, 5L);

            when(agentService.listByRole(AgentRole.EXECUTOR))
                    .thenReturn(List.of(fresh));
            when(circuitBreakerRegistry.find("agentDispatch-2")).thenReturn(Optional.empty());

            Agent result = agentSelector.pickAlternative(1L, AgentRole.EXECUTOR);

            assertThat(result).isNotNull();
            assertThat(result.getId()).isEqualTo(2L);
        }

        @Test
        @DisplayName("API_KEY_LLM last_seen_time=null → 豁免，保留（不需运行时心跳）")
        void shouldKeepApiKeyLlmAgentWithNullLastSeenTime() {
            Agent apiAgent = agentWithHeartbeat(2L, 90, AgentOnlineStatus.ONLINE,
                    AgentStatus.ACTIVE, null);
            apiAgent.setAccessType(AgentAccessType.API_KEY_LLM);

            when(agentService.listByRole(AgentRole.EXECUTOR))
                    .thenReturn(List.of(apiAgent));
            when(circuitBreakerRegistry.find("agentDispatch-2")).thenReturn(Optional.empty());

            Agent result = agentSelector.pickAlternative(1L, AgentRole.EXECUTOR);

            assertThat(result).isNotNull();
            assertThat(result.getId()).isEqualTo(2L);
            assertThat(result.getAccessType()).isEqualTo(AgentAccessType.API_KEY_LLM);
        }

        @Test
        @DisplayName("多候选：fresher CLI_CLIENT 战胜 stale CLI_CLIENT")
        void shouldPickFreshOverStaleAmongCliClients() {
            Agent stale = agentWithHeartbeat(2L, 95, AgentOnlineStatus.ONLINE,
                    AgentStatus.ACTIVE, 15L);  // 过期
            Agent freshButLowerScore = agentWithHeartbeat(3L, 70, AgentOnlineStatus.ONLINE,
                    AgentStatus.ACTIVE, 5L);   // 新鲜

            when(agentService.listByRole(AgentRole.EXECUTOR))
                    .thenReturn(List.of(stale, freshButLowerScore));
            when(circuitBreakerRegistry.find("agentDispatch-3")).thenReturn(Optional.empty());

            Agent result = agentSelector.pickAlternative(1L, AgentRole.EXECUTOR);

            // 尽管 stale 分数更高，因心跳过期被过滤掉
            assertThat(result).isNotNull();
            assertThat(result.getId()).isEqualTo(3L);
        }

        @Test
        @DisplayName("heartbeatFreshMinutes=0（关闭过滤） → 所有 CLI_CLIENT 都保留")
        void shouldDisableFilterWhenThresholdZero() {
            AgentDispatchProperties zeroProps = new AgentDispatchProperties();
            zeroProps.setPreferExternal(false);
            zeroProps.setRequireIdle(false);
            zeroProps.setHeartbeatFreshMinutes(0);
            AgentSelector zeroSelector = new AgentSelector(
                    agentService, circuitBreakerRegistry, zeroProps, agentDutyLeaseService);

            Agent stale = agentWithHeartbeat(2L, 90, AgentOnlineStatus.ONLINE,
                    AgentStatus.ACTIVE, 120L);  // 2 小时前
            when(agentService.listByRole(AgentRole.EXECUTOR))
                    .thenReturn(List.of(stale));
            when(circuitBreakerRegistry.find("agentDispatch-2")).thenReturn(Optional.empty());

            Agent result = zeroSelector.pickAlternative(1L, AgentRole.EXECUTOR);

            assertThat(result).isNotNull();
            assertThat(result.getId()).isEqualTo(2L);
        }

        @Test
        @DisplayName("heartbeatFreshMinutes=3 自定义阈值：9 分钟前视为过期")
        void shouldRespectCustomThreshold() {
            AgentDispatchProperties customProps = new AgentDispatchProperties();
            customProps.setPreferExternal(false);
            customProps.setRequireIdle(false);
            customProps.setHeartbeatFreshMinutes(3);
            AgentSelector customSelector = new AgentSelector(
                    agentService, circuitBreakerRegistry, customProps, agentDutyLeaseService);

            Agent stale = agentWithHeartbeat(2L, 90, AgentOnlineStatus.ONLINE,
                    AgentStatus.ACTIVE, 9L);  // 9 分钟前 > 3 分钟阈值
            when(agentService.listByRole(AgentRole.EXECUTOR))
                    .thenReturn(List.of(stale));

            Agent result = customSelector.pickAlternative(1L, AgentRole.EXECUTOR);

            assertThat(result).isNull();
        }
    }
}
