package com.helloai.core.agent.runtime;

import com.helloai.common.config.AgentExecutionProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.model.ChatModel;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * AgentRuntime 路由单元测试（P0-B / P0-B-2）。
 *
 * <p>验证 {@link RuntimeAgentRuntimeRouter} 的切换口径：runtime-enabled=false →
 * LegacyExecutorAdapter（现状零变化）；true 且 ctx 携带 chatModel（可驱动）→ RuntimeTurnExecutor
 * （真身）；true 但 ctx 无模型（dispatch 上下文，P0-B-2 驱动性判断）→ 回落 Legacy，防无效路由。</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("RuntimeAgentRuntimeRouter")
class RuntimeAgentRuntimeRouterTest {

    @Mock
    private LegacyExecutorAdapter legacyExecutorAdapter;

    @Mock
    private RuntimeTurnExecutor runtimeTurnExecutor;

    @Mock
    private AgentExecutionProperties executionProperties;

    @Mock
    private AgentContext ctx;

    @Test
    @DisplayName("runtime-enabled=false（默认）→ 委托 LegacyExecutorAdapter")
    void shouldDelegateToLegacyWhenDisabled() {
        when(executionProperties.isRuntimeEnabled()).thenReturn(false);

        new RuntimeAgentRuntimeRouter(legacyExecutorAdapter, runtimeTurnExecutor, executionProperties)
                .execute(ctx);

        verify(legacyExecutorAdapter).execute(ctx);
    }

    @Test
    @DisplayName("runtime-enabled=true 且 ctx 携带 chatModel → 委托 RuntimeTurnExecutor（真身）")
    void shouldDelegateToRuntimeWhenEnabledAndDrivable() {
        when(executionProperties.isRuntimeEnabled()).thenReturn(true);
        when(ctx.getChatModel()).thenReturn(mock(ChatModel.class));

        new RuntimeAgentRuntimeRouter(legacyExecutorAdapter, runtimeTurnExecutor, executionProperties)
                .execute(ctx);

        verify(runtimeTurnExecutor).execute(ctx);
    }

    @Test
    @DisplayName("runtime-enabled=true 但 ctx 无 chatModel（dispatch 上下文）→ 回落 Legacy（驱动性）")
    void shouldFallbackToLegacyWhenNotDrivable() {
        when(executionProperties.isRuntimeEnabled()).thenReturn(true);
        // ctx.getChatModel() 默认返回 null（未 stub）→ 不可驱动

        new RuntimeAgentRuntimeRouter(legacyExecutorAdapter, runtimeTurnExecutor, executionProperties)
                .execute(ctx);

        verify(legacyExecutorAdapter).execute(ctx);
    }
}
