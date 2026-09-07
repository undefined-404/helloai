package com.helloai.core.agent.chat.provider;

import com.helloai.core.agent.entity.Agent;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;

public interface ProviderChatClientFactory {

    boolean supports(String provider);

    ChatClient createChatClient(String apiKeyPlaintext, Agent agent, String model);

    /**
     * 创建（或复用缓存）ChatModel（P0-B-2：Runtime AgentLoop 需直接持有底层模型，
     * 而非 ChatClient；与 {@link #createChatClient} 共享同一缓存实例）。
     */
    ChatModel createChatModel(String apiKeyPlaintext, Agent agent, String model);
}

