package com.helloai.core.agent.executor;

import com.helloai.common.config.AgentExecutionProperties;
import com.helloai.common.constant.AgentAccessType;
import com.helloai.common.constant.AgentRole;
import com.helloai.core.agent.AgentLlmCredentialResolver;
import com.helloai.core.agent.domain.AgentTask;
import com.helloai.core.agent.entity.Agent;
import com.helloai.core.agent.service.AgentChatClientService;
import com.helloai.core.agent.support.AttachmentMediaAssembler;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
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
        // VU-9：媒体装载器。本类不覆盖媒体通道，桩成「无媒体」（返回空表而不是 null ——
        // mock 默认返回 null，会让下面的 anyList() 匹配不上，测出来的就不是本用例的事了）
        AttachmentMediaAssembler mediaAssembler = mock(AttachmentMediaAssembler.class);
        when(mediaAssembler.loadInlineImages(anyList())).thenReturn(List.of());
        ApiKeyAgentExecutor executor = new ApiKeyAgentExecutor(
                chatClientService,
                agentLlmCredentialResolver,
                properties,
                mediaAssembler
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
        // LOG-20260901-001 后 generate 为 6 参重载（含 temperature）；VU-9 起执行器改走
        // 第 7 参带媒体的重载 ⇒ 桩必须打在新签名上，否则 mock 返回 null 触发 NPE
        // （判据 30：改完调用点必须回来核对桩的签名）
        when(chatClientService.generate(eq(agent), eq("system"), eq("user"), eq("deepseek"),
                isNull(), isNull(), anyList()))
                .thenThrow(root);

        assertThatThrownBy(() -> executor.execute(agent, task))
                .isSameAs(root);
    }
}
