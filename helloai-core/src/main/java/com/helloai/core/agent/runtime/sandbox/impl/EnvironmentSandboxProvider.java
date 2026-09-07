package com.helloai.core.agent.runtime.sandbox.impl;

import com.helloai.core.agent.runtime.ExecutionEnvironment;
import com.helloai.core.agent.runtime.ExecutionEnvironmentProvider;
import com.helloai.core.agent.runtime.LocalProcessEnvironment;
import com.helloai.core.agent.runtime.RemoteAgentEnvironment;
import com.helloai.core.agent.runtime.sandbox.ExecutionPolicy;
import com.helloai.core.agent.runtime.sandbox.Sandbox;
import com.helloai.core.agent.runtime.sandbox.SandboxContext;
import com.helloai.core.agent.runtime.sandbox.SandboxProvider;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * {@link SandboxProvider} 第一阶段实现——复用既有 {@link ExecutionEnvironmentProvider}
 * 解析环境，并给出诚实隔离策略（不宣称安全隔离）。
 *
 * <p>策略口径（P1 第一阶段，无真实沙箱）：</p>
 * <ul>
 *   <li>{@link RemoteAgentEnvironment}：网络边界 PARTIAL（外部终端自有网络，非平台强制），其余 NONE；</li>
 *   <li>其余（含 {@link LocalProcessEnvironment}）：全 NONE（本地进程直跑平台主机，无隔离）。</li>
 * </ul>
 * <p>所有环境均不标 ISOLATED——Docker / K8s 安全沙箱 P2/P3 后置（执行方案坑 4）。</p>
 */
@Component
@RequiredArgsConstructor
public class EnvironmentSandboxProvider implements SandboxProvider {

    /** 提供方标识（轻量路由标识，与 AgentExecutor.getName() 同风格）。 */
    public static final String PROVIDER_NAME = "environment";

    private final ExecutionEnvironmentProvider environmentProvider;

    @Override
    public Sandbox resolve(SandboxContext context) {
        if (context == null || context.accessType() == null) {
            return null;
        }
        ExecutionEnvironment environment = environmentProvider.resolve(context.accessType());
        if (environment == null) {
            return null;
        }
        return new Sandbox(PROVIDER_NAME, environment, policyFor(environment));
    }

    /** 按环境类型给诚实策略（当前无 ISOLATED；remote-agent 网络为天然 PARTIAL 非平台强制）。 */
    private ExecutionPolicy policyFor(ExecutionEnvironment environment) {
        if (environment instanceof RemoteAgentEnvironment) {
            return ExecutionPolicy.remoteTerminal();
        }
        return ExecutionPolicy.noIsolation();
    }
}
