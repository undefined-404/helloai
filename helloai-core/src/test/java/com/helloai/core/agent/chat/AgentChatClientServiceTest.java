package com.helloai.core.agent.chat;

import com.helloai.common.config.AgentExecutionProperties;
import com.helloai.common.constant.AgentRole;
import com.helloai.core.agent.chat.provider.LlmProviderChatClientFactoryRegistry;
import com.helloai.core.agent.service.AgentChatClientService;
import com.helloai.core.agent.service.impl.AgentChatClientServiceImpl;
import com.helloai.core.agent.entity.Agent;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.beans.factory.ObjectProvider;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
/**
 * AgentChatClientService 单元测试。覆盖：mock 模式 / 空 systemPrompt 也能走通。
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

        // 主类保持 ObjectProvider 惰性（§6.139：Optional 注入会立即解析 chatClientBuilder
        // 候选并触发 ChatModel 依赖解析导致启动炸）；mock 模式不触达 Builder，空 provider 即可
        ObjectProvider<ChatClient.Builder> builderProvider = mock(ObjectProvider.class);
        LlmProviderChatClientFactoryRegistry registry = mock(LlmProviderChatClientFactoryRegistry.class);
        LlmCallConcurrencyGuard guard = new LlmCallConcurrencyGuard(properties);
        AgentChatClientService service = new AgentChatClientServiceImpl(properties, builderProvider, registry, guard);

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

    @Test
    @DisplayName("mock 模式流式：分片拼接与同步全量一致，流可正常完成")
    void shouldStreamJoinedMatchesSyncOutput() {
        AgentExecutionProperties properties = new AgentExecutionProperties();
        properties.setEnabled(true);
        properties.setMockMode(true);
        properties.setProvider("mock");
        properties.setModel("helloai-mock-executor");
        properties.setMockResponsePrefix("[mock-executor]");

        ObjectProvider<ChatClient.Builder> builderProvider = mock(ObjectProvider.class);
        LlmProviderChatClientFactoryRegistry registry = mock(LlmProviderChatClientFactoryRegistry.class);
        LlmCallConcurrencyGuard guard = new LlmCallConcurrencyGuard(properties);
        AgentChatClientService service = new AgentChatClientServiceImpl(properties, builderProvider, registry, guard);

        Agent agent = new Agent();
        agent.setId(101L);
        agent.setName("mock-agent");
        agent.setRole(AgentRole.EXECUTOR);

        // 流式路径：分片伪流式（MockChatModel.stream），OnNext 拼接后与同步 call 文本严格相等
        String joined = service.generateStream(agent, null, "请输出一句最小验证结果")
                .collectList()
                .block()
                .stream()
                .reduce("", String::concat);
        ChatResponse sync = service.generate(agent, null, "请输出一句最小验证结果");

        assertThat(joined).isEqualTo(sync.getResult().getOutput().getText());
        assertThat(joined).contains("[mock-executor]");
    }
}
