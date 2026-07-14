package com.helloai.core.service;

import com.helloai.common.base.BizException;
import com.helloai.core.agent.domain.AgentResult;
import com.helloai.core.agent.domain.AgentTask;
import com.helloai.core.entity.Agent;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.Map;
import com.helloai.core.agent.execution.PlatformAgentExecutionService;

/**
 * Agent 执行预览服务。
 *
 * <p>作为 T5 最小验证入口的编排层，负责参数收口、默认 prompt 处理与统一执行调用。</p>
 */
@Service
@RequiredArgsConstructor
public class AgentExecutionPreviewService {

    private static final String DEFAULT_SYSTEM_PROMPT = "You are an internal HelloAI execution agent. "
            + "Respond concisely and focus on task completion.";

    private final AgentService agentService;
    private final PlatformAgentExecutionService platformAgentExecutionService;

    /**
     * 触发一次最小执行预览。
     */
    public AgentResult preview(Long agentId, Long subTaskId,
                               String systemPrompt, String userPrompt,
                               Map<String, Object> context,
                               Map<String, Object> requiredCapabilities) {
        if (userPrompt == null || userPrompt.isBlank()) {
            throw new BizException("userPrompt 不能为空");
        }

        Agent agent = agentService.getById(agentId);
        if (agent == null) {
            throw new BizException("Agent 不存在: " + agentId);
        }

        AgentTask task = AgentTask.builder()
                .subTaskId(subTaskId)
                .systemPrompt(systemPrompt != null && !systemPrompt.isBlank() ? systemPrompt : DEFAULT_SYSTEM_PROMPT)
                .userPrompt(userPrompt)
                .context(context != null ? context : Map.of())
                .requiredCapabilities(requiredCapabilities != null ? requiredCapabilities : Map.of())
                .build();

        return platformAgentExecutionService.executeSync(agent, task);
    }
}
