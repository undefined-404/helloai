package com.helloai.core.agent.dispatcher;

import com.helloai.common.base.AgentUnavailableException;
import com.helloai.common.base.BizException;
import com.helloai.common.config.AgentDispatchProperties;
import com.helloai.common.constant.AgentAccessType;
import com.helloai.common.constant.AgentOnlineStatus;
import com.helloai.common.constant.AgentRole;
import com.helloai.common.constant.AgentStatus;
import com.helloai.core.agent.entity.Agent;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Map;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import com.helloai.core.agent.executor.AgentSelector;
import com.helloai.core.agent.observability.CircuitBreakerEventRecorder;
import com.helloai.core.agent.port.SubTaskCommandPort;
import com.helloai.core.agent.port.SubTaskQueryPort;
import com.helloai.core.agent.port.SubTaskSnapshot;
import com.helloai.core.agent.port.TaskTimelinePort;
import com.helloai.core.agent.service.AgentService;

/**
 * ResilientDispatcher 单元测试。
 *
 * <p>验证熔断保护 + fast-fail + fallback 降级逻辑。
 * 使用真实 Resilience4j CircuitBreakerRegistry（内存模式）测试行为。</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ResilientDispatcher")
class ResilientDispatcherTest {

    @Mock
    private SubTaskQueryPort subTaskQueryPort;

    @Mock
    private SubTaskCommandPort subTaskCommandPort;

    @Mock
    private AgentService agentService;

    @Mock
    private AgentSelector agentSelector;

    @Mock
    private CircuitBreakerEventRecorder eventRecorder;

    @Mock
    private AgentDispatchProperties agentDispatchProperties;

    @Mock
    private TaskTimelinePort taskTimelinePort;

    private CircuitBreakerRegistry circuitBreakerRegistry;
    private ResilientDispatcher resilientDispatcher;

    @BeforeEach
    void setUp() {
        circuitBreakerRegistry = CircuitBreakerRegistry.of(
                CircuitBreakerConfig.ofDefaults());
        // 注册 agentDispatch 配置（生产环境由 application.yml 提供）
        circuitBreakerRegistry.addConfiguration("agentDispatch",
                CircuitBreakerConfig.ofDefaults());
        resilientDispatcher = new ResilientDispatcher(
                circuitBreakerRegistry,
                eventRecorder,
                subTaskQueryPort,
                subTaskCommandPort,
                agentService,
                agentSelector,
                agentDispatchProperties,
                taskTimelinePort);
        // assignNext fast-fail 段新增心跳新鲜度检查，默认桩为“新鲜”，
        // 心跳陈旧场景在具体用例中单独覆盖
        lenient().when(agentSelector.isHeartbeatFresh(any())).thenReturn(true);
    }

    private Agent onlineAgent(Long id) {
        Agent a = new Agent();
        a.setId(id);
        a.setName("agent-" + id);
        a.setRole(AgentRole.EXECUTOR);
        a.setAccessType(AgentAccessType.CLI_CLIENT);
        a.setOnlineStatus(AgentOnlineStatus.ONLINE);
        a.setStatus(AgentStatus.ACTIVE);
        return a;
    }

    private Agent apiKeyLlmAgent(Long id, boolean supportsMcp) {
        Agent a = new Agent();
        a.setId(id);
        a.setName("api-agent-" + id);
        a.setRole(AgentRole.EXECUTOR);
        a.setAccessType(AgentAccessType.API_KEY_LLM);
        a.setOnlineStatus(AgentOnlineStatus.OFFLINE);
        a.setStatus(AgentStatus.ACTIVE);
        a.setCapabilities(Map.of("supportsMCP", supportsMcp));
        return a;
    }

    private SubTaskSnapshot executionDenseSubTask(Long id) {
        return SubTaskSnapshot.builder()
                .id(id)
                .taskId(id + 1000L)
                .content("编写 verify-order-expire.ps1 脚本并执行验证")
                .acceptance("脚本运行通过")
                .deliverable("verify-order-expire.ps1")
                .build();
    }

    /**
     * 通过反射调用 private assignNextFallback，验证降级逻辑。
     * <p>注：@{@link io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker}
     * 注解依赖 Spring AOP 代理，单元测试（直接 new）不触发拦截。
     * 注解行为应在集成测试（@SpringBootTest）中验证。</p>
     */
    private void invokeFallback(Long agentId, Long subTaskId, Throwable t) {
        try {
            java.lang.reflect.Method fallback = ResilientDispatcher.class
                    .getDeclaredMethod("assignNextFallback", Long.class, Long.class, Throwable.class);
            fallback.setAccessible(true);
            fallback.invoke(resilientDispatcher, agentId, subTaskId, t);
        } catch (java.lang.reflect.InvocationTargetException e) {
            // 原样抛出 fallback 方法中的异常
            Throwable cause = e.getCause();
            if (cause instanceof RuntimeException re) {
                throw re;
            }
            throw new RuntimeException(cause);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    // ════════════════════════════════════════════════════════════
    //  Tests
    // ════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("fast-fail: AgentUnavailableException")
    class FastFail {

        @Test
        @DisplayName("SLEEPING Agent → 抛 AgentUnavailableException（不计入熔断）")
        void shouldThrowAgentUnavailableForSleeping() {
            Agent sleeping = onlineAgent(1L);
            sleeping.setOnlineStatus(AgentOnlineStatus.SLEEPING);
            when(agentService.getById(1L)).thenReturn(sleeping);

            assertThatThrownBy(() -> resilientDispatcher.assignNext(1L, 100L))
                    .isInstanceOf(AgentUnavailableException.class)
                    .hasMessageContaining("SLEEPING");

            // SLEEPING 不计入熔断，不应该调用 assignNext
            verify(subTaskCommandPort, never()).assignNext(anyLong(), anyLong());
        }

        @Test
        @DisplayName("OFFLINE Agent → 抛 AgentUnavailableException")
        void shouldThrowAgentUnavailableForOffline() {
            Agent offline = onlineAgent(1L);
            offline.setOnlineStatus(AgentOnlineStatus.OFFLINE);
            when(agentService.getById(1L)).thenReturn(offline);

            assertThatThrownBy(() -> resilientDispatcher.assignNext(1L, 100L))
                    .isInstanceOf(AgentUnavailableException.class)
                    .hasMessageContaining("OFFLINE");
        }

        @Test
        @DisplayName("CLI_CLIENT ONLINE 但心跳陈旧 → 抛 AgentUnavailableException（走 fallback）")
        void shouldThrowAgentUnavailableForStaleHeartbeat() {
            Agent stale = onlineAgent(1L);
            when(agentService.getById(1L)).thenReturn(stale);
            // DB online_status 仍 ONLINE，但心跳新鲜度检查判定已失联
            when(agentSelector.isHeartbeatFresh(stale)).thenReturn(false);

            assertThatThrownBy(() -> resilientDispatcher.assignNext(1L, 100L))
                    .isInstanceOf(AgentUnavailableException.class)
                    .hasMessageContaining("心跳已陈旧");

            verify(subTaskCommandPort, never()).assignNext(anyLong(), anyLong());
        }

        @Test
        @DisplayName("API_KEY_LLM 默认 OFFLINE 也允许调度")
        void shouldAllowOfflineApiKeyLlmAgent() {
            Agent apiAgent = onlineAgent(1L);
            apiAgent.setAccessType(AgentAccessType.API_KEY_LLM);
            apiAgent.setOnlineStatus(AgentOnlineStatus.OFFLINE);
            when(agentService.getById(1L)).thenReturn(apiAgent);

            resilientDispatcher.assignNext(1L, 100L);

            verify(subTaskCommandPort).assignNext(eq(1L), eq(100L));
        }
    }

    @Nested
    @DisplayName("正常分配")
    class NormalDispatch {

        @Test
        @DisplayName("ONLINE Agent → 正常分配")
        void shouldDispatchToOnlineAgent() {
            Agent online = onlineAgent(1L);
            when(agentService.getById(1L)).thenReturn(online);

            resilientDispatcher.assignNext(1L, 100L);

            verify(subTaskCommandPort).assignNext(eq(1L), eq(100L));
        }

        @Test
        @DisplayName("缺少 agentDispatch 模板配置时回退默认配置")
        void shouldFallbackToDefaultRegistryConfigWhenNamedTemplateMissing() {
            CircuitBreakerRegistry registryWithoutNamedConfig = CircuitBreakerRegistry.of(
                    CircuitBreakerConfig.ofDefaults());
            ResilientDispatcher dispatcherWithoutNamedConfig = new ResilientDispatcher(
                    registryWithoutNamedConfig,
                    eventRecorder,
                    subTaskQueryPort,
                    subTaskCommandPort,
                    agentService,
                    agentSelector,
                    agentDispatchProperties,
                    taskTimelinePort);
            Agent online = onlineAgent(1L);
            when(agentService.getById(1L)).thenReturn(online);

            dispatcherWithoutNamedConfig.assignNext(1L, 100L);

            verify(subTaskCommandPort).assignNext(eq(1L), eq(100L));
        }
    }

    @Nested
    @DisplayName("熔断降级 fallback")
    class CircuitBreakerFallback {

        @Test
        @DisplayName("Agent 不存在 → fallback 选替代 Agent")
        void shouldFallbackWhenAgentNotFound() {
            // fallback 内会再次调用 getById 获取角色
            when(agentService.getById(1L)).thenReturn(null);

            Agent alternative = onlineAgent(2L);
            when(agentSelector.pickAlternative(eq(1L), eq(null), any()))
                    .thenReturn(alternative);

            invokeFallback(1L, 100L, new BizException("Agent 不存在: 1"));

            verify(subTaskCommandPort).assignNext(eq(2L), eq(100L));
        }

        @Test
        @DisplayName("无替代 Agent → 抛 409 + 可读原因 + 落 sub_task_dispatch_no_alternative 时间线（REF-5.1）")
        void shouldThrowReadable409AndRecordNoAlternativeTimeline() {
            when(agentService.getById(1L)).thenReturn(null);
            when(agentSelector.pickAlternative(eq(1L), eq(null), any()))
                    .thenReturn(null);
            when(subTaskQueryPort.findById(100L)).thenReturn(
                    SubTaskSnapshot.builder().id(100L).taskId(1100L).build());

            assertThatThrownBy(() ->
                    invokeFallback(1L, 100L, new BizException("Agent 不存在: 1")))
                    .isInstanceOf(BizException.class)
                    .hasMessageContaining("无可用替代 Agent")
                    // 裸 500 → 409：调用方拿到的是「冲突 + 可读原因」，不是「服务器错误」
                    .satisfies(e -> assertThat(((BizException) e).getCode()).isEqualTo(409));

            verify(taskTimelinePort).recordEvent(
                    eq(1100L), eq(100L), eq("sub_task_dispatch_no_alternative"), eq(AgentRole.SYSTEM), isNull(),
                    argThat(p -> Long.valueOf(1L).equals(p.get("excludedAgentId"))
                            && "N/A".equals(p.get("role"))
                            && "no_alternative".equals(p.get("reason"))));
        }

        @Test
        @DisplayName("无替代 Agent + 取不到 subTask 快照 → 仍抛 409（时间线 best-effort 不阻断）")
        void shouldStillThrowReadable409WhenSnapshotMissing() {
            when(agentService.getById(1L)).thenReturn(null);
            when(agentSelector.pickAlternative(eq(1L), eq(null), any()))
                    .thenReturn(null);
            when(subTaskQueryPort.findById(100L)).thenReturn(null);

            assertThatThrownBy(() ->
                    invokeFallback(1L, 100L, new BizException("Agent 不存在: 1")))
                    .isInstanceOf(BizException.class)
                    .satisfies(e -> assertThat(((BizException) e).getCode()).isEqualTo(409));

            verify(taskTimelinePort, never()).recordEvent(
                    anyLong(), anyLong(), eq("sub_task_dispatch_no_alternative"), any(), any(), anyMap());
        }

        @Test
        @DisplayName("fallback 落 sub_task_dispatch_fallback 时间线（preferred → actual + reason，804 落点可观测）")
        void shouldRecordDispatchFallbackTimeline() {
            when(agentService.getById(1L)).thenReturn(null);
            Agent alternative = onlineAgent(2L);
            when(agentSelector.pickAlternative(eq(1L), eq(null), any()))
                    .thenReturn(alternative);
            // 时间线需要 subTask 快照取 taskId
            when(subTaskQueryPort.findById(100L)).thenReturn(
                    SubTaskSnapshot.builder().id(100L).taskId(1100L).build());

            invokeFallback(1L, 100L, new BizException("Agent 不存在: 1"));

            verify(subTaskCommandPort).assignNext(eq(2L), eq(100L));
            verify(taskTimelinePort).recordEvent(
                    eq(1100L), eq(100L), eq("sub_task_dispatch_fallback"), eq(AgentRole.SYSTEM), eq(2L),
                    argThat(p -> Long.valueOf(1L).equals(p.get("preferredAgentId"))
                            && Long.valueOf(2L).equals(p.get("actualAgentId"))
                            && "dispatch_error".equals(p.get("reason"))));
        }

        @Test
        @DisplayName("fallback 取不到 subTask 快照 → 仍完成分配（时间线 best-effort 不阻断）")
        void shouldStillAssignWhenSnapshotMissing() {
            when(agentService.getById(1L)).thenReturn(null);
            Agent alternative = onlineAgent(2L);
            when(agentSelector.pickAlternative(eq(1L), eq(null), any()))
                    .thenReturn(alternative);
            when(subTaskQueryPort.findById(100L)).thenReturn(null);

            invokeFallback(1L, 100L, new BizException("Agent 不存在: 1"));

            verify(subTaskCommandPort).assignNext(eq(2L), eq(100L));
            verify(taskTimelinePort, never()).recordEvent(
                    anyLong(), anyLong(), eq("sub_task_dispatch_fallback"), any(), any(), anyMap());
        }
    }

    @Nested
    @DisplayName("执行密集能力预检")
    class ExecutionDensePrecheck {

        @Test
        @DisplayName("执行密集任务 + 无本机能力 Agent → 拒绝分配 + 标记人工介入")
        void shouldRejectExecutionDenseToAgentWithoutLocalCapability() {
            when(agentDispatchProperties.isFallbackSkipExecutionDense()).thenReturn(true);
            Agent noCap = apiKeyLlmAgent(1L, false);
            when(agentService.getById(1L)).thenReturn(noCap);
            when(subTaskQueryPort.findById(100L)).thenReturn(executionDenseSubTask(100L));
            when(subTaskQueryPort.isExecutionDense(100L)).thenReturn(true);

            assertThatThrownBy(() -> resilientDispatcher.assignNext(1L, 100L))
                    .isInstanceOf(AgentUnavailableException.class)
                    .hasMessageContaining("执行密集任务不匹配无本机能力 Agent");

            verify(subTaskCommandPort, never()).assignNext(anyLong(), anyLong());
            verify(subTaskCommandPort).markManualIntervention(
                    eq(100L), eq("dispatch_skip_execution_dense"), anyMap());
            verify(taskTimelinePort).recordEvent(
                    eq(1100L), eq(100L), eq("sub_task_dispatch_skip_no_capability"),
                    eq(AgentRole.SYSTEM), eq(1L), anyMap());
        }

        @Test
        @DisplayName("执行密集任务 + supportsMCP=true 的 API_KEY_LLM → 正常分配")
        void shouldAllowExecutionDenseToAgentWithMCPCapability() {
            when(agentDispatchProperties.isFallbackSkipExecutionDense()).thenReturn(true);
            Agent withCap = apiKeyLlmAgent(1L, true);
            when(agentService.getById(1L)).thenReturn(withCap);
            when(subTaskQueryPort.findById(100L)).thenReturn(executionDenseSubTask(100L));
            when(subTaskQueryPort.isExecutionDense(100L)).thenReturn(true);

            resilientDispatcher.assignNext(1L, 100L);

            verify(subTaskCommandPort).assignNext(eq(1L), eq(100L));
            verify(subTaskCommandPort, never()).markManualIntervention(anyLong(), anyString(), anyMap());
        }

        @Test
        @DisplayName("fallback 替代 Agent 无本机能力 → 放弃分配 + 标记人工介入")
        void shouldSkipAlternativeWithoutLocalCapabilityInFallback() {
            when(agentDispatchProperties.isFallbackSkipExecutionDense()).thenReturn(true);
            when(subTaskQueryPort.findById(100L)).thenReturn(executionDenseSubTask(100L));
            when(subTaskQueryPort.isExecutionDense(100L)).thenReturn(true);
            when(agentService.getById(1L)).thenReturn(null);

            Agent alternative = apiKeyLlmAgent(2L, false);
            when(agentSelector.pickAlternative(eq(1L), eq(null), any()))
                    .thenReturn(alternative);

            invokeFallback(1L, 100L, new BizException("Agent 不存在: 1"));

            verify(subTaskCommandPort, never()).assignNext(anyLong(), anyLong());
            verify(subTaskCommandPort).markManualIntervention(
                    eq(100L), eq("dispatch_skip_execution_dense"), anyMap());
        }

        @Test
        @DisplayName("fallback 替代 Agent 有本机能力 → 正常分配")
        void shouldAssignToCapableAlternativeInFallback() {
            when(agentDispatchProperties.isFallbackSkipExecutionDense()).thenReturn(true);
            when(subTaskQueryPort.findById(100L)).thenReturn(executionDenseSubTask(100L));
            when(subTaskQueryPort.isExecutionDense(100L)).thenReturn(true);
            when(agentService.getById(1L)).thenReturn(null);

            Agent alternative = apiKeyLlmAgent(2L, true);
            when(agentSelector.pickAlternative(eq(1L), eq(null), any()))
                    .thenReturn(alternative);

            invokeFallback(1L, 100L, new BizException("Agent 不存在: 1"));

            verify(subTaskCommandPort).assignNext(eq(2L), eq(100L));
        }
    }
}
