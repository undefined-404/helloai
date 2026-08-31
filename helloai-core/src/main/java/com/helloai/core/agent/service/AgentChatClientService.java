package com.helloai.core.agent.service;

import com.helloai.core.agent.entity.Agent;
import org.springframework.ai.chat.model.ChatResponse;
import reactor.core.publisher.Flux;

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

    /**
     * 流式生成回复（默认平台通道）：以 token 增量 {@link Flux} 输出，订阅时发起调用。
     *
     * <p>语义与 {@link #generate(Agent, String, String)} 完全一致，仅传输形态不同：
     * mock 模式走 {@code MockChatModel.stream()} 分片伪流式（输出与同步 call 一致），
     * 真实模式走 {@code ChatClient.prompt().stream().content()}；并发限流与错误语义同同步路径。</p>
     */
    Flux<String> generateStream(Agent agent, String systemPrompt, String userPrompt);

    /**
     * 流式生成回复（显式 provider / 明文 API Key，语义同 {@link #generate(Agent, String, String, String, String)}）。
     */
    Flux<String> generateStream(Agent agent, String systemPrompt, String userPrompt,
                                String provider, String apiKeyPlaintext);
}
