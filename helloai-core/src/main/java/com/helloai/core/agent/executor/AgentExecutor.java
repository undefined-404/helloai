package com.helloai.core.agent.executor;

import com.helloai.common.base.BizException;
import com.helloai.core.agent.domain.AgentResult;
import com.helloai.core.agent.domain.AgentTask;
import com.helloai.core.agent.entity.Agent;
import reactor.core.publisher.Flux;

import java.util.Map;
import java.util.Objects;

/**
 * 平台内 Agent 执行接口。
 *
 * <p>先立统一抽象，让调度核心只依赖接口，不感知具体底层是 CLI、LLM 直调还是未来的浏览器桥接。</p>
 */
public interface AgentExecutor {

    /**
     * 同步执行任务。
     */
    AgentResult execute(Agent agent, AgentTask task);

    /**
     * 流式执行任务（token 增量 Flux，订阅时发起调用）。
     *
     * <p>默认实现直接拒绝：仅实现流式通道的执行器（当前为 {@link ApiKeyAgentExecutor}）
     * 覆写本方法；上层（{\@link PlatformAgentExecutionService#executeStream}）通过
     * BizException 失败路径回退同步语义，绝不静默吞掉流式请求。</p>
     */
    default Flux<String> executeStream(Agent agent, AgentTask task) {
        throw new BizException(getName() + " 暂不支持流式执行（agentId=" + agent.getId() + "）");
    }

    /**
     * 当前执行器是否支持该 Agent。
     */
    boolean supports(Agent agent);

    /**
     * 执行前能力校验。
     */
    default boolean checkCapability(Agent agent, Map<String, Object> requiredCapabilities) {
        if (requiredCapabilities == null || requiredCapabilities.isEmpty()) {
            return true;
        }
        Map<String, Object> capabilities = agent.getCapabilities();
        if (capabilities == null || capabilities.isEmpty()) {
            return false;
        }
        for (Map.Entry<String, Object> entry : requiredCapabilities.entrySet()) {
            Object actual = capabilities.get(entry.getKey());
            if (!matchesCapability(actual, entry.getValue())) {
                return false;
            }
        }
        return true;
    }

    /**
     * 执行器名称，默认取简单类名。
     */
    default String getName() {
        return getClass().getSimpleName();
    }

    private boolean matchesCapability(Object actual, Object required) {
        if (required == null) {
            return true;
        }
        if (required instanceof Number requiredNumber && actual instanceof Number actualNumber) {
            return Double.compare(actualNumber.doubleValue(), requiredNumber.doubleValue()) >= 0;
        }
        return Objects.equals(actual, required);
    }
}
