package com.helloai.core.agent.dispatcher;

import com.helloai.common.base.AgentUnavailableException;
import com.helloai.common.base.BizException;
import com.helloai.common.constant.AgentAccessType;
import com.helloai.common.constant.AgentOnlineStatus;
import com.helloai.common.constant.AgentRole;
import com.helloai.common.constant.AgentStatus;
import com.helloai.core.entity.Agent;
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

import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import com.helloai.core.agent.executor.AgentSelector;
import com.helloai.core.observability.CircuitBreakerEventRecorder;
import com.helloai.core.agent.service.AgentService;
import com.helloai.core.task.service.SubTaskService;

/**
 * ResilientDispatcher 单元测试（v2.4 §4.10）。
 *
 * <p>验证熔断保护 + fast-fail + fallback 降级逻辑。
 * 使用真实 Resilience4j CircuitBreakerRegistry（内存模式）测试行为。</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ResilientDispatcher")
class ResilientDispatcherTest {

    @Mock
    private SubTaskService subTaskService;

    @Mock
    private AgentService agentService;

    @Mock
    private AgentSelector agentSelector;

    @Mock
    private CircuitBreakerEventRecorder eventRecorder;

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
                subTaskService,
                agentService,
                agentSelector);
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
            verify(subTaskService, never()).assignNext(anyLong(), anyLong());
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
        @DisplayName("API_KEY_LLM 默认 OFFLINE 也允许调度")
        void shouldAllowOfflineApiKeyLlmAgent() {
            Agent apiAgent = onlineAgent(1L);
            apiAgent.setAccessType(AgentAccessType.API_KEY_LLM);
            apiAgent.setOnlineStatus(AgentOnlineStatus.OFFLINE);
            when(agentService.getById(1L)).thenReturn(apiAgent);

            resilientDispatcher.assignNext(1L, 100L);

            verify(subTaskService).assignNext(eq(1L), eq(100L));
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

            verify(subTaskService).assignNext(eq(1L), eq(100L));
        }

        @Test
        @DisplayName("缺少 agentDispatch 模板配置时回退默认配置")
        void shouldFallbackToDefaultRegistryConfigWhenNamedTemplateMissing() {
            CircuitBreakerRegistry registryWithoutNamedConfig = CircuitBreakerRegistry.of(
                    CircuitBreakerConfig.ofDefaults());
            ResilientDispatcher dispatcherWithoutNamedConfig = new ResilientDispatcher(
                    registryWithoutNamedConfig,
                    eventRecorder,
                    subTaskService,
                    agentService,
                    agentSelector);
            Agent online = onlineAgent(1L);
            when(agentService.getById(1L)).thenReturn(online);

            dispatcherWithoutNamedConfig.assignNext(1L, 100L);

            verify(subTaskService).assignNext(eq(1L), eq(100L));
        }
    }

    @Nested
    @DisplayName("熔断降级 fallback")
    class CircuitBreakerFallback {

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

        @Test
        @DisplayName("Agent 不存在 → fallback 选替代 Agent")
        void shouldFallbackWhenAgentNotFound() {
            // fallback 内会再次调用 getById 获取角色
            when(agentService.getById(1L)).thenReturn(null);

            Agent alternative = onlineAgent(2L);
            when(agentSelector.pickAlternative(eq(1L), eq(null)))
                    .thenReturn(alternative);

            invokeFallback(1L, 100L, new BizException("Agent 不存在: 1"));

            verify(subTaskService).assignNext(eq(2L), eq(100L));
        }

        @Test
        @DisplayName("无替代 Agent → fallback 抛 BizException")
        void shouldThrowWhenNoAlternative() {
            when(agentService.getById(1L)).thenReturn(null);
            when(agentSelector.pickAlternative(eq(1L), eq(null)))
                    .thenReturn(null);

            assertThatThrownBy(() ->
                    invokeFallback(1L, 100L, new BizException("Agent 不存在: 1")))
                    .isInstanceOf(BizException.class)
                    .hasMessageContaining("无可用替代 Agent");
        }
    }
}
