package com.helloai.core.agent.chat;

import com.helloai.core.agent.entity.Agent;
import org.springframework.ai.chat.client.ChatClient;

public interface ProviderChatClientFactory {

    boolean supports(String provider);

    ChatClient createChatClient(String apiKeyPlaintext, Agent agent, String model);
}

