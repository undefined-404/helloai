package com.helloai.core.agent.service.impl;

import com.helloai.core.agent.chat.AgentProviderResolver;
import com.helloai.core.agent.chat.LlmCallConcurrencyGuard;
import com.helloai.core.agent.service.AgentChatClientService;
import com.helloai.common.base.BizException;
import com.helloai.common.config.AgentExecutionProperties;
import com.helloai.core.agent.chat.provider.LlmProviderChatClientFactoryRegistry;
import com.helloai.core.agent.entity.Agent;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.DefaultUsage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import reactor.core.publisher.Flux;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * Agent ChatClient 服务。
 *
 * <p>先以稳定 mock 模式接入 Spring AI ChatClient，确保平台内执行链路可在本地稳定验证。
 * 后续切真实 Provider 时，复用这里的 ChatClient 组装入口即可。</p>
 */
@Service
@RequiredArgsConstructor
public class AgentChatClientServiceImpl implements AgentChatClientService {

    private final AgentExecutionProperties executionProperties;
    // 存在性探测必须保持 ObjectProvider（完全惰性）：ChatClientAutoConfiguration 的
    // chatClientBuilder 候选始终存在，但其依赖的 ChatModel 由 DeepSeekChatAutoConfiguration
    // 提供、已被 application.yml 排除（api-key 置空 fail-fast 防护）——若改 Optional 注入，
    // Spring 会立即解析候选并创建 chatClientBuilder → 启动期直接炸（§6.138 回归暴露）；
    // ObjectProvider 运行期 getIfAvailable 返回 null 由下方 BizException 兜底。
    private final ObjectProvider<ChatClient.Builder> chatClientBuilderProvider;
    private final LlmProviderChatClientFactoryRegistry providerRegistry;
    private final LlmCallConcurrencyGuard llmCallConcurrencyGuard;

    /**
     * 使用 Spring AI ChatClient 生成回复。
     */
    public ChatResponse generate(Agent agent, String systemPrompt, String userPrompt) {
        return generate(agent, systemPrompt, userPrompt, null, null);
    }

    public ChatResponse generate(Agent agent, String systemPrompt, String userPrompt,
                                 String provider, String apiKeyPlaintext) {
        return generate(agent, systemPrompt, userPrompt, provider, apiKeyPlaintext, null);
    }

    public ChatResponse generate(Agent agent, String systemPrompt, String userPrompt,
                                 String provider, String apiKeyPlaintext, Double temperature) {
        if (!executionProperties.isEnabled()) {
            throw new BizException("平台内 Agent 执行链已关闭");
        }
        // 并发限流（对话并发优化 B 项）：仅真实 Provider 模式占用许可，
        // mock 模式本地即时返回无上游压力；finally 保证异常路径也释放。
        boolean throttled = !executionProperties.isMockMode();
        if (throttled) {
            llmCallConcurrencyGuard.acquire();
        }
        try {
            return doGenerate(agent, systemPrompt, userPrompt, provider, apiKeyPlaintext, temperature);
        } finally {
            if (throttled) {
                llmCallConcurrencyGuard.release();
            }
        }
    }

    private ChatResponse doGenerate(Agent agent, String systemPrompt, String userPrompt,
                                    String provider, String apiKeyPlaintext, Double temperature) {
        ChatClient chatClient = buildChatClient(agent, provider, apiKeyPlaintext);

        ChatClient.ChatClientRequestSpec prompt = chatClient.prompt();
        if (StringUtils.hasText(systemPrompt)) {
            prompt = prompt.system(systemPrompt);
        }
        // temperature 仅显式传入时覆盖模型默认（null = 不附加 options，现有链路行为不变）
        if (temperature != null) {
            prompt = prompt.options(ChatOptions.builder().temperature(temperature).build());
        }
        return prompt
                .user(userPrompt != null ? userPrompt : "")
                .call()
                .chatResponse();
    }

    /**
     * 流式生成：token 增量以 {@link Flux} 输出，订阅时发起调用。
     *
     * <p>限流语义与同步 {@link #generate(Agent, String, String, String, String)} 一致：
     * 仅真实 Provider 模式占用许可，mock 模式不占；许可在订阅前获取、doFinally 保证
     * 完成/异常/取消路径都释放（流式时长不定，不能依赖 try/finally 的同步释放）。</p>
     */
    public Flux<String> generateStream(Agent agent, String systemPrompt, String userPrompt) {
        return generateStream(agent, systemPrompt, userPrompt, null, null);
    }

    public Flux<String> generateStream(Agent agent, String systemPrompt, String userPrompt,
                                       String provider, String apiKeyPlaintext) {
        if (!executionProperties.isEnabled()) {
            throw new BizException("平台内 Agent 执行链已关闭");
        }
        boolean throttled = !executionProperties.isMockMode();
        if (throttled) {
            llmCallConcurrencyGuard.acquire();
        }
        return Flux.defer(() -> doGenerateStream(agent, systemPrompt, userPrompt, provider, apiKeyPlaintext))
                .doFinally(signal -> {
                    if (throttled) {
                        llmCallConcurrencyGuard.release();
                    }
                });
    }

    /**
     * 流式主实现：与 {@link #doGenerate} 同构组装 ChatClient，仅末端由
     * {@code call().chatResponse()} 换成 {@code stream().content()}（正文增量）。
     *
     * <p>空串帧过滤：mock 分片不产生空串，真实 provider 首帧/流间隙可能出现空串帧；
     * 只丢弃空串、保留空白与换行（它们是正文的一部分）。</p>
     */
    private Flux<String> doGenerateStream(Agent agent, String systemPrompt, String userPrompt,
                                          String provider, String apiKeyPlaintext) {
        ChatClient chatClient = buildChatClient(agent, provider, apiKeyPlaintext);

        ChatClient.ChatClientRequestSpec prompt = chatClient.prompt();
        if (StringUtils.hasText(systemPrompt)) {
            prompt = prompt.system(systemPrompt);
        }
        return prompt
                .user(userPrompt != null ? userPrompt : "")
                .stream()
                .content()
                .filter(token -> token != null && !token.isEmpty());
    }

    /**
     * 组装 ChatClient（同步与流式共用入口）。
     *
     * <p>mock 模式直接 ChatClient.create 包最小 mock 模型；真实模式优先显式
     * provider + API Key 走 registry，否则容器内 ChatClient.Builder。</p>
     */
    private ChatClient buildChatClient(Agent agent, String provider, String apiKeyPlaintext) {
        if (executionProperties.isMockMode()) {
            return ChatClient.create(new MockChatModel(
                    executionProperties.getProvider(),
                    executionProperties.getModel(),
                    executionProperties.getMockResponsePrefix(),
                    agent));
        }
        if (apiKeyPlaintext != null && !apiKeyPlaintext.isBlank()) {
            String effectiveProvider = provider != null && !provider.isBlank()
                    ? provider : executionProperties.getProvider();
            String model = AgentProviderResolver.resolveModel(agent, null);
            return providerRegistry.createChatClient(effectiveProvider, apiKeyPlaintext, agent, model);
        }
        ChatClient.Builder builder = chatClientBuilderProvider.getIfAvailable();
        if (builder == null) {
            throw new BizException("未检测到 ChatClient.Builder，请接入 Spring AI Provider starter 并配置 API Key");
        }
        return builder.build();
    }

    /**
     * 最小 mock ChatModel。
     */
    private static final class MockChatModel implements ChatModel {

        private final String provider;
        private final String model;
        private final String prefix;
        private final Agent agent;

        private MockChatModel(String provider, String model, String prefix, Agent agent) {
            this.provider = provider;
            this.model = model;
            this.prefix = prefix;
            this.agent = agent;
        }

        /**
         * 分片伪流式：对 {@link #call(Prompt)} 的同一份完整 content 按固定块长切片，
         * 每片以固定延时发射，保证流式链路与同步链路输出完全一致（测试可断言拼接文本相等）。
         *
         * <p>不带 usage/metadata（流式为增量帧，prompt/completion token 计数在分片粒度无意义；
         * 消耗统计仅同步路径承担）。分片按 char 切，emoji 等代理对可能被切断，mock 用途可接受。</p>
         */
        @Override
        public Flux<ChatResponse> stream(Prompt prompt) {
            ChatResponse full = call(prompt);
            String content = full.getResult() != null && full.getResult().getOutput() != null
                    ? full.getResult().getOutput().getText() : "";
            List<String> chunks = new ArrayList<>();
            for (int i = 0; i < content.length(); i += MOCK_STREAM_CHUNK_SIZE) {
                chunks.add(content.substring(i, Math.min(content.length(), i + MOCK_STREAM_CHUNK_SIZE)));
            }
            return Flux.fromIterable(chunks)
                    .map(chunk -> new ChatResponse(
                            List.of(new Generation(new AssistantMessage(chunk))),
                            null))
                    .delayElements(Duration.ofMillis(MOCK_STREAM_DELAY_MS));
        }

        @Override
        public ChatResponse call(Prompt prompt) {
            String userText = prompt.getUserMessage() != null ? prompt.getUserMessage().getText() : "";
            String systemText = prompt.getSystemMessage() != null ? prompt.getSystemMessage().getText() : "";
            String content = String.format(
                    "%s agent=%s role=%s model=%s%nSYSTEM:%s%nUSER:%s",
                    prefix,
                    agent.getName(),
                    agent.getRole(),
                    model,
                    abbreviate(systemText),
                    abbreviate(userText)
            );
            int promptTokens = Math.max(1, (systemText.length() + userText.length()) / 4);
            int completionTokens = Math.max(1, content.length() / 4);

            return new ChatResponse(
                    List.of(new Generation(new AssistantMessage(content))),
                    ChatResponseMetadata.builder()
                            .model(provider + ":" + model)
                            .usage(new DefaultUsage(promptTokens, completionTokens, promptTokens + completionTokens))
                            .build()
            );
        }

        private String abbreviate(String value) {
            if (value == null || value.isBlank()) {
                return "(empty)";
            }
            return value.length() <= 240 ? value : value.substring(0, 240) + "...";
        }
    }

    /** 伪流式分片大小（字符）。 */
    private static final int MOCK_STREAM_CHUNK_SIZE = 8;

    /** 伪流式分片间延时（毫秒）。 */
    private static final long MOCK_STREAM_DELAY_MS = 20L;
}
