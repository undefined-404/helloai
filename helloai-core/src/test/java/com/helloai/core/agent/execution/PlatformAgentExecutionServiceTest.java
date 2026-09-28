package com.helloai.core.agent.execution;

import com.helloai.common.config.AgentExecutionProperties;
import com.helloai.common.constant.AgentAccessType;
import com.helloai.common.constant.AgentRole;
import com.helloai.core.agent.AgentLlmCredentialResolver;
import com.helloai.core.agent.service.AgentChatClientService;
import com.helloai.core.agent.skill.AgentSkillSpecService;
import com.helloai.core.agent.chat.LlmCallConcurrencyGuard;
import com.helloai.core.agent.chat.provider.LlmProviderChatClientFactoryRegistry;
import com.helloai.core.agent.domain.AgentResult;
import com.helloai.core.agent.domain.AgentTask;
import com.helloai.core.agent.executor.AgentExecutorRouter;
import com.helloai.core.agent.executor.ApiKeyAgentExecutor;
import com.helloai.core.agent.entity.Agent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.ObjectProvider;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import com.helloai.core.agent.service.HeartbeatService;
import com.helloai.core.agent.service.AgentService;
import com.helloai.core.agent.service.PlatformAgentExecutionService;
import com.helloai.core.agent.service.impl.AgentChatClientServiceImpl;
import com.helloai.core.agent.service.impl.PlatformAgentExecutionServiceImpl;

/**
 * PlatformAgentExecutionService 单元测试。
 *
 * <p>验证 /最小链路：选择执行器 → ChatClient(mock) → 返回结果。</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("PlatformAgentExecutionService")
class PlatformAgentExecutionServiceTest {

    @Mock
    private AgentService agentService;

    @Mock
    private AgentLlmCredentialResolver agentLlmCredentialResolver;

    @Mock
    private HeartbeatService heartbeatService;

    @Mock
    private AgentSkillSpecService agentSkillSpecService;

    private PlatformAgentExecutionService platformAgentExecutionService;

    @BeforeEach
    void setUp() {
        AgentExecutionProperties properties = new AgentExecutionProperties();
        properties.setEnabled(true);
        properties.setMockMode(true);
        properties.setProvider("mock");
        properties.setModel("helloai-mock-executor");
        properties.setMockResponsePrefix("[mock-executor]");

        // 主类保持 ObjectProvider 惰性（§6.139：Optional 注入会立即解析 chatClientBuilder
        // 候选并触发 ChatModel 依赖解析导致启动炸）；mock 模式不触达 Builder，空 provider 即可
        ObjectProvider<ChatClient.Builder> builderProvider = Mockito.mock(ObjectProvider.class);
        LlmProviderChatClientFactoryRegistry registry = Mockito.mock(LlmProviderChatClientFactoryRegistry.class);
        LlmCallConcurrencyGuard guard = new LlmCallConcurrencyGuard(properties);
        AgentChatClientService chatClientService = new AgentChatClientServiceImpl(properties, builderProvider, registry, guard);
        ApiKeyAgentExecutor apiKeyAgentExecutor =
                new ApiKeyAgentExecutor(chatClientService, agentLlmCredentialResolver, properties);
        AgentExecutorRouter router = new AgentExecutorRouter(List.of(apiKeyAgentExecutor));
        platformAgentExecutionService = new PlatformAgentExecutionServiceImpl(
                agentService, router, heartbeatService, properties, agentSkillSpecService);
    }

    /** 最小 API_KEY_LLM Agent（mock 链路可路由到 ApiKeyAgentExecutor）。 */
    private Agent newExecutorAgent() {
        Agent agent = new Agent();
        agent.setId(101L);
        agent.setName("mock-executor-agent");
        agent.setRole(AgentRole.EXECUTOR);
        agent.setAccessType(AgentAccessType.API_KEY_LLM);
        agent.setModelType("mock:helloai-mock-executor");
        agent.setCapabilities(Map.of("supportsPull", false, "maxConcurrentTasks", 5));
        return agent;
    }

    @Test
    @DisplayName("API_KEY_LLM Agent 可走通 mock ChatClient 链路")
    void shouldExecuteViaMockChatClient() {
        Agent agent = new Agent();
        agent.setId(101L);
        agent.setName("mock-executor-agent");
        agent.setRole(AgentRole.EXECUTOR);
        agent.setAccessType(AgentAccessType.API_KEY_LLM);
        agent.setModelType("mock:helloai-mock-executor");
        agent.setCapabilities(Map.of("supportsPull", false, "maxConcurrentTasks", 5));

        AgentTask task = AgentTask.builder()
                .subTaskId(2001L)
                .systemPrompt("你是一个执行者")
                .userPrompt("请输出一句最小验证结果")
                .build();

        AgentResult result = platformAgentExecutionService.executeSync(agent, task);

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getExecutorName()).isEqualTo("ApiKeyAgentExecutor");
        assertThat(result.getOutput()).contains("[mock-executor]");
        assertThat(result.getOutput()).contains("请输出一句最小验证结果");
        assertThat(result.getTokenUsage()).isNotNull();
        verify(heartbeatService).active(101L);
    }

    @Test
    @DisplayName("skills 为空：契约层技能零注入（resolve 不触发，行为不变）")
    void shouldNotInjectWhenSkillsEmpty() {
        Agent agent = newExecutorAgent();
        AgentTask task = AgentTask.builder()
                .subTaskId(2002L)
                .systemPrompt("你是一个执行者")
                .userPrompt("请输出一句最小验证结果")
                .build();

        AgentResult result = platformAgentExecutionService.executeSync(agent, task);

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getOutput()).doesNotContain("平台技能规范（执行速览）");
        verify(agentSkillSpecService, never()).resolve(any());
    }

    @Test
    @DisplayName("skills 非空：契约层 resolve 注入执行速览段到 systemPrompt")
    void shouldInjectResolvedSkillSectionWhenSkillsPresent() {
        Agent agent = newExecutorAgent();
        when(agentSkillSpecService.resolve(anyList())).thenReturn(new AgentSkillSpecService.ResolvedSpec(
                List.of("eng-test"),
                List.of("eng-test"),
                "### 测试规范速览\n- 断言粒度：契约层注入\n"));
        AgentTask task = AgentTask.builder()
                .subTaskId(2003L)
                .systemPrompt("你是一个执行者")
                .userPrompt("请输出一句最小验证结果")
                .skills(List.of("eng-test"))
                .build();

        AgentResult result = platformAgentExecutionService.executeSync(agent, task);

        assertThat(result.isSuccess()).isTrue();
        // MockChatModel 回显 SYSTEM 段（前 240 字符截断），注入标题与速览必在截断范围前排
        assertThat(result.getOutput()).contains("## 平台技能规范（执行速览）");
        assertThat(result.getOutput()).contains("### 测试规范速览");
    }
}
