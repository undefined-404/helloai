package com.helloai.core.agent.executor;

import com.helloai.core.agent.domain.AgentResult;
import com.helloai.core.agent.domain.AgentTask;
import com.helloai.core.entity.Agent;

import java.util.Map;
import java.util.Objects;

/**
 * 平台内 Agent 执行接口。
 *
 * <p>T3 先立统一抽象，让调度核心只依赖接口，不感知具体底层是 CLI、LLM 直调还是未来的浏览器桥接。</p>
 */
public interface AgentExecutor {

    /**
     * 同步执行任务。
     */
    AgentResult execute(Agent agent, AgentTask task);

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
