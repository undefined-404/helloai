package com.helloai.core.agent.executor;

import com.helloai.common.config.AgentExecutionProperties;
import com.helloai.common.constant.AgentAccessType;
import com.helloai.common.constant.AgentRole;
import com.helloai.core.agent.AgentLlmCredentialResolver;
import com.helloai.core.agent.domain.AgentTask;
import com.helloai.core.agent.entity.Agent;
import com.helloai.core.agent.service.AgentChatClientService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * ApiKeyAgentExecutor 单元测试。
 */
@DisplayName("ApiKeyAgentExecutor")
class ApiKeyAgentExecutorTest {

    @Test
    @DisplayName("执行异常时不会因为调试 payload 空值再次抛 NPE")
    void shouldPropagateOriginalExceptionWhenChatClientThrowsWithoutMessage() {
        AgentExecutionProperties properties = new AgentExecutionProperties();
        properties.setMockMode(true);
        properties.setProvider("mock");

        AgentChatClientService chatClientService = mock(AgentChatClientService.class);
        AgentLlmCredentialResolver agentLlmCredentialResolver = mock(AgentLlmCredentialResolver.class);
        ApiKeyAgentExecutor executor = new ApiKeyAgentExecutor(
                chatClientService,
                agentLlmCredentialResolver,
                properties
        );

        Agent agent = new Agent();
        agent.setId(11L);
        agent.setRole(AgentRole.EXECUTOR);
        agent.setAccessType(AgentAccessType.API_KEY_LLM);
        agent.setModelType("deepseek:deepseek-chat");

        AgentTask task = AgentTask.builder()
                .subTaskId(22L)
                .systemPrompt("system")
                .userPrompt("user")
                .build();

        RuntimeException root = new RuntimeException();
        // LOG-20260901-001 后 generate 为 6 参重载（含 temperature）；stub 需同步，否则 mock 返回 null 触发 NPE
        when(chatClientService.generate(agent, "system", "user", "deepseek", null, null))
                .thenThrow(root);

        assertThatThrownBy(() -> executor.execute(agent, task))
                .isSameAs(root);
    }
}
