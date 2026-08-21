package com.helloai.core.agent.quality;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.helloai.common.config.AgentQualityProperties;
import com.helloai.core.agent.chat.provider.LlmProviderChatClientFactoryRegistry;
import com.helloai.core.agent.service.PlatformProviderConfigService;
import com.helloai.core.system.entity.LlmProvider;
import com.helloai.core.system.service.LlmProviderQueryService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.ObjectProvider;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * executorDoneIssues LLM 语义对比评估器单元测试（反馈回路第 1 层，Phase 1.5）。
 *
 * <p>覆盖：协议解析（doneIssues/reason 提取、非文本项过滤、空列表合法）、
 * JSON 围栏容错（stripFence 纯函数）、降级跳过（入参缺失/无平台凭证/自动选
 * Provider/LLM 失败/独立超时/输出不可解析/工厂路由异常）。</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ExecutorIssueResolutionAssessor LLM 语义对比评估")
class ExecutorIssueResolutionAssessorTest {

    @Mock
    private LlmProviderChatClientFactoryRegistry chatClientFactoryRegistry;
    @Mock
    private ObjectProvider<LlmProviderChatClientFactoryRegistry> chatClientFactoryRegistryProvider;
    @Mock
    private PlatformProviderConfigService platformProviderConfigService;
    @Mock
    private LlmProviderQueryService llmProviderQueryService;
    @Mock
    private AgentQualityProperties qualityProperties;
    @Mock
    private ChatClient chatClient;
    @Mock
    private ChatClient.ChatClientRequestSpec requestSpec;
    @Mock
    private ChatClient.CallResponseSpec callResponseSpec;

    private ExecutorIssueResolutionAssessor assessor;

    @BeforeEach
    void setUp() {
        // lenient：stripFence 等纯函数用例不触碰 provider，避免 UnnecessaryStubbing
        lenient().when(chatClientFactoryRegistryProvider.getIfAvailable())
                .thenReturn(chatClientFactoryRegistry);
        assessor = new ExecutorIssueResolutionAssessor(chatClientFactoryRegistryProvider,
                platformProviderConfigService, llmProviderQueryService, qualityProperties,
                new ObjectMapper());
    }

    /** 打通「凭证 → 客户端 → 调用链」路径，仅留 content() 返回值由各用例定制。 */
    private void stubHappyPath(String providerCode, String apiKey) {
        when(qualityProperties.getExecutorDoneIssuesProvider()).thenReturn(providerCode);
        when(qualityProperties.getExecutorDoneIssuesTimeoutSeconds()).thenReturn(30);
        when(platformProviderConfigService.getApiKey(providerCode)).thenReturn(apiKey);
        when(chatClientFactoryRegistry.createChatClient(providerCode, apiKey, null, null))
                .thenReturn(chatClient);
        when(chatClient.prompt()).thenReturn(requestSpec);
        when(requestSpec.user(anyString())).thenReturn(requestSpec);
        when(requestSpec.call()).thenReturn(callResponseSpec);
    }

    private LlmProvider provider(String code) {
        LlmProvider p = new LlmProvider();
        p.setProviderCode(code);
        return p;
    }

    // ════════════════════════════════════════════════════════════
    //  降级跳过（best-effort：任何异常/缺失不得抛出）
    // ════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("降级跳过")
    class DegradeSkip {

        @Test
        @DisplayName("issues 为 null → 返回 null 且不触碰任何依赖")
        void skipWhenIssuesNull() {
            assertThat(assessor.assess(null, "output")).isNull();
            verifyNoInteractions(platformProviderConfigService, llmProviderQueryService,
                    chatClientFactoryRegistry);
        }

        @Test
        @DisplayName("issues 为空列表 → 返回 null")
        void skipWhenIssuesEmpty() {
            assertThat(assessor.assess(List.of(), "output")).isNull();
            verifyNoInteractions(platformProviderConfigService, llmProviderQueryService,
                    chatClientFactoryRegistry);
        }

        @Test
        @DisplayName("产出正文为空 → 返回 null")
        void skipWhenOutputBlank() {
            assertThat(assessor.assess(List.of("issue1"), "   ")).isNull();
            verifyNoInteractions(platformProviderConfigService, llmProviderQueryService,
                    chatClientFactoryRegistry);
        }

        @Test
        @DisplayName("无任何已启用 Provider → 返回 null（不查凭证）")
        void skipWhenNoEnabledProvider() {
            when(llmProviderQueryService.listEnabled()).thenReturn(List.of());

            assertThat(assessor.assess(List.of("issue1"), "output")).isNull();
            verify(platformProviderConfigService, never()).getApiKey(anyString());
        }

        @Test
        @DisplayName("指定 Provider 无平台凭证 → 返回 null（不回退其它 Provider）")
        void skipWhenConfiguredProviderLacksApiKey() {
            when(qualityProperties.getExecutorDoneIssuesProvider()).thenReturn("deepseek");
            when(platformProviderConfigService.getApiKey("deepseek")).thenReturn(null);

            assertThat(assessor.assess(List.of("issue1"), "output")).isNull();
            verify(llmProviderQueryService, never()).listEnabled();
        }

        @Test
        @DisplayName("启用 Provider 均无平台凭证 → 返回 null")
        void skipWhenAllProvidersLackApiKey() {
            when(llmProviderQueryService.listEnabled())
                    .thenReturn(List.of(provider("deepseek"), provider("moonshot")));
            when(platformProviderConfigService.getApiKey("deepseek")).thenReturn(null);
            when(platformProviderConfigService.getApiKey("moonshot")).thenReturn("  ");

            assertThat(assessor.assess(List.of("issue1"), "output")).isNull();
            verify(chatClientFactoryRegistry, never())
                    .createChatClient(anyString(), anyString(), any(), any());
        }

        @Test
        @DisplayName("Provider 选择查询异常 → 返回 null")
        void skipWhenProviderSelectionThrows() {
            when(llmProviderQueryService.listEnabled()).thenThrow(new RuntimeException("db down"));

            assertThat(assessor.assess(List.of("issue1"), "output")).isNull();
        }

        @Test
        @DisplayName("LLM 输出不可解析 → 返回 null（不抛异常）")
        void skipOnInvalidJson() {
            stubHappyPath("deepseek", "sk-x");
            when(callResponseSpec.content()).thenReturn("这不是 JSON");

            assertThat(assessor.assess(List.of("issue1"), "output")).isNull();
        }

        @Test
        @DisplayName("LLM 底层调用失败 → 返回 null（不抛异常）")
        void skipOnLlmFailure() {
            stubHappyPath("deepseek", "sk-x");
            when(callResponseSpec.content()).thenThrow(new RuntimeException("llm down"));

            assertThat(assessor.assess(List.of("issue1"), "output")).isNull();
        }

        @Test
        @DisplayName("工厂路由异常 → 返回 null（不抛异常）")
        void skipOnFactoryRouteFailure() {
            when(qualityProperties.getExecutorDoneIssuesProvider()).thenReturn("deepseek");
            when(platformProviderConfigService.getApiKey("deepseek")).thenReturn("sk-x");
            when(chatClientFactoryRegistry.createChatClient("deepseek", "sk-x", null, null))
                    .thenThrow(new RuntimeException("route error"));

            assertThat(assessor.assess(List.of("issue1"), "output")).isNull();
        }

        @Test
        @DisplayName("独立超时（默认 30s 可配置，最小 clamp 1s）→ 返回 null")
        void skipOnTimeout() {
            when(qualityProperties.getExecutorDoneIssuesProvider()).thenReturn("deepseek");
            // 不 stub timeout：mock 默认 0 → Math.max(0, 1) = 1s，加速超时验证
            when(platformProviderConfigService.getApiKey("deepseek")).thenReturn("sk-x");
            when(chatClientFactoryRegistry.createChatClient("deepseek", "sk-x", null, null))
                    .thenReturn(chatClient);
            when(chatClient.prompt()).thenReturn(requestSpec);
            when(requestSpec.user(anyString())).thenReturn(requestSpec);
            when(requestSpec.call()).thenReturn(callResponseSpec);
            when(callResponseSpec.content()).thenAnswer(inv -> {
                Thread.sleep(3000);
                return "{\"doneIssues\":[]}";
            });

            assertThat(assessor.assess(List.of("issue1"), "output")).isNull();
        }
    }

    // ════════════════════════════════════════════════════════════
    //  协议解析（严格 JSON {"doneIssues":[...],"reason":"..."}）
    // ════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("协议解析")
    class ProtocolParse {

        @Test
        @DisplayName("围栏包裹 JSON → 正确解析 doneIssues 与 reason")
        void parsesFencedJson() {
            stubHappyPath("deepseek", "sk-x");
            when(callResponseSpec.content()).thenReturn(
                    "```json\n{\"doneIssues\":[\"缺少单元测试\"],\"reason\":\"已补充单测\"}\n```");

            ExecutorIssueResolutionAssessor.IssueResolutionResult result =
                    assessor.assess(List.of("缺少单元测试"), "产出正文");

            assertThat(result).isNotNull();
            assertThat(result.getDoneIssues()).containsExactly("缺少单元测试");
            assertThat(result.getReason()).isEqualTo("已补充单测");
        }

        @Test
        @DisplayName("无围栏纯 JSON → 同样可解析")
        void parsesPlainJson() {
            stubHappyPath("deepseek", "sk-x");
            when(callResponseSpec.content())
                    .thenReturn("{\"doneIssues\":[\"a\",\"b\"],\"reason\":\"ok\"}");

            ExecutorIssueResolutionAssessor.IssueResolutionResult result =
                    assessor.assess(List.of("a", "b"), "产出正文");

            assertThat(result).isNotNull();
            assertThat(result.getDoneIssues()).containsExactly("a", "b");
        }

        @Test
        @DisplayName("doneIssues 中非文本项被过滤（数字/布尔/null）")
        void filtersNonTextualDoneItems() {
            stubHappyPath("deepseek", "sk-x");
            when(callResponseSpec.content())
                    .thenReturn("{\"doneIssues\":[1,true,\"x\",null],\"reason\":\"\"}");

            ExecutorIssueResolutionAssessor.IssueResolutionResult result =
                    assessor.assess(List.of("x"), "产出正文");

            assertThat(result).isNotNull();
            assertThat(result.getDoneIssues()).containsExactly("x");
            assertThat(result.getReason()).isEmpty();
        }

        @Test
        @DisplayName("doneIssues 为空列表 → 合法结果（全部未解决）")
        void allowsEmptyDoneIssues() {
            stubHappyPath("deepseek", "sk-x");
            when(callResponseSpec.content())
                    .thenReturn("{\"doneIssues\":[],\"reason\":\"均未解决\"}");

            ExecutorIssueResolutionAssessor.IssueResolutionResult result =
                    assessor.assess(List.of("x"), "产出正文");

            assertThat(result).isNotNull();
            assertThat(result.getDoneIssues()).isEmpty();
        }
    }

    // ════════════════════════════════════════════════════════════
    //  stripFence 围栏容错（纯函数）
    // ════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("围栏容错 stripFence")
    class FenceTolerance {

        @Test
        @DisplayName("```json 围栏 → 取出围栏内内容")
        void stripsJsonFence() {
            assertThat(ExecutorIssueResolutionAssessor.stripFence("```json\n{}\n```")).isEqualTo("{}");
        }

        @Test
        @DisplayName("裸 ``` 围栏 → 取出围栏内内容")
        void stripsPlainFence() {
            assertThat(ExecutorIssueResolutionAssessor.stripFence("```\n{}\n```")).isEqualTo("{}");
        }

        @Test
        @DisplayName("围栏标记大小写不敏感")
        void fenceCaseInsensitive() {
            assertThat(ExecutorIssueResolutionAssessor.stripFence("```JSON\n{}\n```")).isEqualTo("{}");
        }

        @Test
        @DisplayName("无围栏 → 原样返回（trim）")
        void passthroughWithoutFence() {
            assertThat(ExecutorIssueResolutionAssessor.stripFence("  {\"a\":1}  ")).isEqualTo("{\"a\":1}");
        }

        @Test
        @DisplayName("null → 空串")
        void nullReturnsEmpty() {
            assertThat(ExecutorIssueResolutionAssessor.stripFence(null)).isEmpty();
        }
    }

    // ════════════════════════════════════════════════════════════
    //  自动选择 Provider（平台级凭证）
    // ════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("自动选择 Provider")
    class AutoSelectProvider {

        @Test
        @DisplayName("未指定时取第一个「已启用且有平台凭证」的 Provider")
        void selectsFirstEnabledProviderWithKey() {
            when(llmProviderQueryService.listEnabled())
                    .thenReturn(List.of(provider("deepseek"), provider("moonshot")));
            when(platformProviderConfigService.getApiKey("deepseek")).thenReturn(null);
            when(platformProviderConfigService.getApiKey("moonshot")).thenReturn("sk-m");
            when(qualityProperties.getExecutorDoneIssuesTimeoutSeconds()).thenReturn(30);
            when(chatClientFactoryRegistry.createChatClient("moonshot", "sk-m", null, null))
                    .thenReturn(chatClient);
            when(chatClient.prompt()).thenReturn(requestSpec);
            when(requestSpec.user(anyString())).thenReturn(requestSpec);
            when(requestSpec.call()).thenReturn(callResponseSpec);
            when(callResponseSpec.content())
                    .thenReturn("{\"doneIssues\":[\"i1\"],\"reason\":\"done\"}");

            ExecutorIssueResolutionAssessor.IssueResolutionResult result =
                    assessor.assess(List.of("i1"), "output");

            assertThat(result).isNotNull();
            assertThat(result.getDoneIssues()).containsExactly("i1");
            verify(chatClientFactoryRegistry).createChatClient("moonshot", "sk-m", null, null);
        }
    }
}
