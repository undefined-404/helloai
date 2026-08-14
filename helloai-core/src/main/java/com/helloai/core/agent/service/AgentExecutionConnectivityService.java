package com.helloai.core.agent.service;

import com.helloai.core.agent.domain.AgentExecutionConnectivityResult;

/**
 * Agent LLM 连通性验证服务。
 *
 * <p>该服务只验证 vault、provider 与 ChatClient 的最小真实调用链，
 * 不写入 sub_task、execution record 或 timeline，便于快速切分问题边界。</p>
 */
public interface AgentExecutionConnectivityService {

    /**
     * 执行一次最小 LLM 连通性验证。
     */
    AgentExecutionConnectivityResult probe(Long agentId, String systemPrompt, String userPrompt);
}
