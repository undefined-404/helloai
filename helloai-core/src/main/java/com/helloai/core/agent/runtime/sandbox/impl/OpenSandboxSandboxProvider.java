package com.helloai.core.agent.runtime.sandbox.impl;

import com.helloai.common.config.OpenSandboxProperties;
import com.helloai.core.agent.runtime.OpenSandboxEnvironment;
import com.helloai.core.agent.runtime.sandbox.ExecutionPolicy;
import com.helloai.core.agent.runtime.sandbox.Sandbox;
import com.helloai.core.agent.runtime.sandbox.SandboxContext;
import com.helloai.core.agent.runtime.sandbox.SandboxProvider;
import lombok.extern.slf4j.Slf4j;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * OpenSandbox 沙箱提供方（S-3，沙箱集成计划 §3/§4.3/§4.4）。
 *
 * <p>{@link SandboxProvider} 的 OpenSandbox 实现：配置开关
 * {@code helloai.sandbox.opensandbox.enabled=true} 时，解析为带诚实五边界策略
 * （{@link ExecutionPolicy#openSandbox()}）的 {@link Sandbox}；否则返回 null（沿既有
 * null 契约，不抛异常，与 {@link EnvironmentSandboxProvider} 一致）。</p>
 *
 * <p><b>诚实边界</b>：这是平台首次可诚实标注 ISOLATED 的提供方（filesystem /
 * resource / credential），但 network / process 标 PARTIAL，<b>不标全 ISOLATED</b>
 * （《协作规约》§22 + 差距表 §6：不得在无实际隔离能力时宣称「安全沙箱已完成」）。
 * 且本提供方<b>尚未被任何执行链消费</b>——{@code SandboxProvider.resolve} 的消费方
 * {@code RuntimeTurnExecutor} 仅做 best-effort 观测日志（能力预研 + 契约落地）。</p>
 *
 * <p><b>scope=agent 复用</b>（对齐 REF-3.5）：同一 Agent（{@code SandboxContext.agentId}）
 * 复用同一沙箱（{@link #sandboxIdByAgentId} 缓存）；<b>显式回收、不自动销毁</b>——
 * {@link #release(Long)} 时才触发沙箱销毁语义，不依赖沙箱自过期兜底。</p>
 */
@Slf4j
public class OpenSandboxSandboxProvider implements SandboxProvider {

    /** 提供方标识（与 AgentExecutor.getName() 同风格轻量路由标识）。 */
    public static final String PROVIDER_NAME = "opensandbox";

    private final OpenSandboxProperties properties;
    private final OpenSandboxEnvironment environment;
    private final Map<Long, String> sandboxIdByAgentId = new ConcurrentHashMap<>();

    public OpenSandboxSandboxProvider(OpenSandboxProperties properties, OpenSandboxEnvironment environment) {
        this.properties = properties;
        this.environment = environment;
    }

    @Override
    public Sandbox resolve(SandboxContext context) {
        if (context == null || context.accessType() == null) {
            return null;
        }
        if (!properties.isEnabled()) {
            return null;
        }
        // scope=agent：同 Agent 复用登记（对齐 REF-3.5；不重复创建，显式回收）
        if (context.agentId() != null) {
            sandboxIdByAgentId.computeIfAbsent(context.agentId(), id -> {
                log.info("OpenSandboxSandboxProvider: register sandbox scope for agentId={}", id);
                return "<sandbox:" + id + ">";
            });
        }
        return new Sandbox(PROVIDER_NAME, environment, ExecutionPolicy.openSandbox());
    }

    /**
     * 显式回收某 Agent 的沙箱（scope=agent 生命周期语义：{@code delete} 时才销毁，
     * 不自动销毁；对齐 REF-3.5）。
     *
     * @param agentId Agent ID；null 或未登记时静默返回（幂等）
     */
    public void release(Long agentId) {
        if (agentId == null) {
            return;
        }
        String sandboxId = sandboxIdByAgentId.remove(agentId);
        if (sandboxId != null) {
            log.info("OpenSandboxSandboxProvider: release sandbox scope for agentId={}, sandboxId={}",
                    agentId, sandboxId);
        }
    }

    /** 当前已登记的 agent→sandbox 作用域数（观测用）。 */
    public int activeAgentScopes() {
        return sandboxIdByAgentId.size();
    }
}
