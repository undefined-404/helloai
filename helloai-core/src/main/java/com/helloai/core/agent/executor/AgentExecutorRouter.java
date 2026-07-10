package com.helloai.core.agent.executor;

import com.helloai.common.base.BizException;
import com.helloai.core.entity.Agent;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Agent 执行器路由器。
 */
@Component
@RequiredArgsConstructor
public class AgentExecutorRouter {

    private final List<AgentExecutor> executors;

    /**
     * 为指定 Agent 选择执行器。
     */
    public AgentExecutor route(Agent agent) {
        if (agent == null) {
            throw new BizException("Agent 不存在，无法路由执行器");
        }
        return executors.stream()
                .filter(executor -> executor.supports(agent))
                .findFirst()
                .orElseThrow(() -> new BizException(
                        "未找到匹配的 AgentExecutor: agentId=" + agent.getId()
                                + ", accessType=" + agent.getAccessType()));
    }
}
