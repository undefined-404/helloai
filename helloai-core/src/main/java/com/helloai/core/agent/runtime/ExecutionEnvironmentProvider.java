package com.helloai.core.agent.runtime;

import com.helloai.common.constant.AgentAccessType;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 执行环境解析器（Phase 1 Step 4；长期思路 P0-1 SandboxProvider 的当期落位）。
 *
 * <p>职责：按 Agent 接入类型解析 {@link ExecutionEnvironment}，供消费侧注入
 * {@link AgentContext#environment}（契约供电）。路由契约与 {@code AgentExecutorRouter}
 * 同构（实现列表 + supports 过滤取首个命中）；未匹配返回 null（调用方保持
 * Phase 0 的 null 语义，不阻断执行链）。</p>
 *
 * <p>边界（坑 4 结论）：只做 RemoteAgent / LocalProcess 两类；DockerSandbox 推迟 P2、
 * K8sSandbox 推迟 P3——后续新环境注册为实现类即可，不改动本解析器。</p>
 */
@Component
@RequiredArgsConstructor
public class ExecutionEnvironmentProvider {

    private final List<ExecutionEnvironment> environments;

    /**
     * 按接入类型解析执行环境。
     *
     * @param accessType Agent 接入类型（可空）
     * @return 首个命中实现；accessType 为 null 或无命中时返回 null（契约：不抛异常）
     */
    public ExecutionEnvironment resolve(AgentAccessType accessType) {
        if (accessType == null) {
            return null;
        }
        return environments.stream()
                .filter(environment -> environment.supports(accessType))
                .findFirst()
                .orElse(null);
    }
}
