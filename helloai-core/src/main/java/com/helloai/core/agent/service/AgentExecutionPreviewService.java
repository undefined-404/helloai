package com.helloai.core.agent.service;

import com.helloai.core.agent.domain.AgentResult;

import java.util.Map;

/**
 * Agent 执行预览服务。
 *
 * <p>作为 T5 最小验证入口的编排层，负责参数收口、默认 prompt 处理与统一执行调用。</p>
 */
public interface AgentExecutionPreviewService {

    /**
     * 触发一次最小执行预览。
     */
    AgentResult preview(Long agentId, Long subTaskId,
                        String systemPrompt, String userPrompt,
                        Map<String, Object> context,
                        Map<String, Object> requiredCapabilities);
}
