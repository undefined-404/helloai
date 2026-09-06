package com.helloai.core.agent.executor;

import com.helloai.common.constant.AgentAccessType;
import com.helloai.core.agent.browser.gateway.BrowserAgentGateway;
import com.helloai.core.agent.domain.AgentResult;
import com.helloai.core.agent.domain.AgentTask;
import com.helloai.core.agent.entity.Agent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * WEB_BROWSER 执行器（N-003，C3-S2）。
 *
 * <p>形态 A 桥接：平台推送 sub_task 执行命令给外部 Browser Agent（自持 Playwright），
 * 同步等待外部服务回传 output（可为 manifest JSON 产物协议，ExecutionOutputParser
 * 物化复用）。调度/选人/心跳豁免已由现有链就绪（AgentSelector/ResilientDispatcher）。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class BrowserAgentExecutor implements AgentExecutor {

    private final BrowserAgentGateway browserAgentGateway;

    @Override
    public AgentResult execute(Agent agent, AgentTask task) {
        try {
            String output = browserAgentGateway.push(agent, task);
            log.info("Browser 执行完成: agentId={}, subTaskId={}, outputLen={}",
                    agent.getId(), task.getSubTaskId(), output == null ? 0 : output.length());
            return AgentResult.success(output, "STOP", getName(), null);
        } catch (Exception e) {
            // 与 ApiKeyAgentExecutor 同契约：推送/等待/超时失败向上抛，由上层记录 FAILED
            log.error("Browser 执行失败: agentId={}, subTaskId={}, err={}",
                    agent.getId(), task.getSubTaskId(), e.getMessage());
            throw e instanceof RuntimeException re ? re : new IllegalStateException(e);
        }
    }

    @Override
    public boolean supports(Agent agent) {
        return agent != null && agent.getAccessType() == AgentAccessType.WEB_BROWSER;
    }
}
