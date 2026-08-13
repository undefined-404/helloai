package com.helloai.core.agent.service;

import com.helloai.core.agent.entity.Agent;
import org.springframework.ai.chat.model.ChatResponse;

/**
 * Spring AI ChatClient 调用入口（平台内 Agent 执行链的 LLM 交互层）。
 *
 * <p>支持两种凭据路径：调用方显式传入 provider + API Key 明文（外部 Agent 凭据注入），
 * 或使用 Spring 容器内已配置的 ChatClient.Builder（平台级默认通道）。</p>
 */
public interface AgentChatClientService {

    /**
     * 使用 Spring AI ChatClient 生成回复（默认平台通道）。
     */
    ChatResponse generate(Agent agent, String systemPrompt, String userPrompt);

    /**
     * 使用 Spring AI ChatClient 生成回复。
     *
     * @param provider       显式指定 provider；null/空 = 走执行属性默认 provider
     * @param apiKeyPlaintext 明文 API Key；null/空 = 走容器内已配置的 ChatClient.Builder
     */
    ChatResponse generate(Agent agent, String systemPrompt, String userPrompt,
                          String provider, String apiKeyPlaintext);
}
