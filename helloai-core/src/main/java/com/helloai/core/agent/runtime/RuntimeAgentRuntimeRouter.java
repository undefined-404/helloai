package com.helloai.core.agent.runtime;

import com.helloai.common.config.AgentExecutionProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Primary;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * AgentRuntime 路由（P0-B）：按配置 {@code runtime-enabled} 在 Legacy 与 Runtime 真身间切换。
 *
 * <p>作为唯一 {@code @Primary} AgentRuntime：by-type 注入收敛到本路由；{@code List<AgentRuntime>}
 * 注入经 {@code @Order(1)} 恒为首位——消费者 {@code agentRuntimes.get(0)} 命中本路由（P0-B 前
 * 该位置是 LegacyExecutorAdapter，行为不变）。</p>
 *
 * <p>切换与回滚口径（二进制，不重建已删除的 taskId%100 灰度路由，LOG-20260904-006）：
 * <ul>
 *   <li>{@code runtime-enabled=false}（默认）→ {@link LegacyExecutorAdapter}（现状零变化）；</li>
 *   <li>{@code runtime-enabled=true} → {@link RuntimeTurnExecutor}（AgentLoop 组装真身）；</li>
 *   <li>回滚 = 置回 false 重启。</li>
 * </ul>
 * </p>
 */
@Slf4j
@Component
@Primary
@Order(1)
@RequiredArgsConstructor
public class RuntimeAgentRuntimeRouter implements AgentRuntime {

    private final LegacyExecutorAdapter legacyExecutorAdapter;
    private final RuntimeTurnExecutor runtimeTurnExecutor;
    private final AgentExecutionProperties executionProperties;

    @Override
    public AgentExecutionResult execute(AgentContext ctx) {
        // P0-B-2 驱动性感知：Runtime 真身需 ctx 携带 chatModel（+prompt）才可执行；
        // dispatch 层上下文无模型 → 即使 runtime-enabled=true 也回落 Legacy，防无效路由
        boolean runtimeDrivable = executionProperties.isRuntimeEnabled()
                && ctx != null && ctx.getChatModel() != null;
        return runtimeDrivable ? runtimeTurnExecutor.execute(ctx) : legacyExecutorAdapter.execute(ctx);
    }
}
