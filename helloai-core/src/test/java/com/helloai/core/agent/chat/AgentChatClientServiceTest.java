package com.helloai.core.agent.chat;

import com.helloai.common.config.AgentExecutionProperties;
import com.helloai.common.constant.AgentRole;
import com.helloai.core.agent.chat.ProviderChatClientFactory;
import com.helloai.core.entity.Agent;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.beans.factory.ObjectProvider;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * AgentChatClientService 单元测试。
 */
@DisplayName("AgentChatClientService")
class AgentChatClientServiceTest {

    @Test
    @DisplayName("空 systemPrompt 时仍可走通 mock ChatClient")
    void shouldGenerateWhenSystemPromptIsBlank() {
        AgentExecutionProperties properties = new AgentExecutionProperties();
        properties.setEnabled(true);
        properties.setMockMode(true);
        properties.setProvider("mock");
        properties.setModel("helloai-mock-executor");
        properties.setMockResponsePrefix("[mock-executor]");

        ObjectProvider<ChatClient.Builder> builderProvider = mock(ObjectProvider.class);
        ObjectProvider<List<ProviderChatClientFactory>> factoriesProvider = mock(ObjectProvider.class);
        AgentChatClientService service = new AgentChatClientService(properties, builderProvider, factoriesProvider);

        Agent agent = new Agent();
        agent.setId(101L);
        agent.setName("mock-agent");
        agent.setRole(AgentRole.EXECUTOR);

        ChatResponse response = service.generate(agent, null, "请输出一句最小验证结果");

        assertThat(response).isNotNull();
        assertThat(response.getResult()).isNotNull();
        assertThat(response.getResult().getOutput()).isNotNull();
        assertThat(response.getResult().getOutput().getText()).contains("[mock-executor]");
    }
}
