package com.helloai.core.agent.service.impl;

import com.helloai.core.agent.chat.AgentProviderResolver;
import com.helloai.core.agent.chat.ChatResponseContentExtractor;
import com.helloai.core.agent.chat.LlmStreamChunk;
import com.helloai.core.agent.chat.ConcurrencyGuardedChatModel;
import com.helloai.core.agent.chat.LlmCallConcurrencyGuard;
import com.helloai.core.agent.service.AgentChatClientService;
import com.helloai.common.base.BizException;
import com.helloai.common.config.AgentExecutionProperties;
import com.helloai.core.agent.chat.provider.LlmProviderChatClientFactoryRegistry;
import com.helloai.core.agent.entity.Agent;
import com.helloai.core.agent.support.ImageInputCapabilityPolicy;
import com.helloai.core.system.service.LlmProviderModelQueryService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
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
import org.springframework.ai.content.Media;
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
@Slf4j
@Service
@RequiredArgsConstructor
public class AgentChatClientServiceImpl implements AgentChatClientService {

    /**
     * 模型看不到图时的显式说明（不变量 I-4「禁假装看过」的落地产物）。
     *
     * <p><b>`VU-10` 起文案本体外移到 {@link ImageInputCapabilityPolicy#MEDIA_NOT_VISIBLE_NOTE}</b>：
     * 执行面 {@code AgentRuntimeContextAssembler} 也要注入同一句，两处各写一份必然漂移
     * （一边说「看不到」、另一边忘了说，模型就会凭文件名编造图片内容）。
     * 本常量保留为别名，使既有测试断言与调用点零改动。</p>
     */
    static final String MEDIA_NOT_VISIBLE_NOTE =
            ImageInputCapabilityPolicy.MEDIA_NOT_VISIBLE_NOTE;

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
     * 模型能力查询（`VU-9` 不变量 I-2 的数据源）。
     *
     * <p>注入的是 system 域的查询服务接口（不是 Mapper、不是实体）：{@code agent → system}
     * 属 §6 允许的向下依赖，且该服务已由 {@code AgentSkillPolicyService} 等多处消费，
     * 属既有稳定契约。</p>
     *
     * <p><b>`VU-10` 起本类不再直接用它</b>：判定已收口到 {@link ImageInputCapabilityPolicy}，
     * 执行面需要同一判定。字段保留是因为 {@link #buildChatModel} 等路径仍按 provider
     * 建模型（与本判定无关），且去掉字段会扩大本批改动面。</p>
     */
    private final LlmProviderModelQueryService llmProviderModelQueryService;

    /**
     * 「本次请求能不能带图」的唯一判定（`VU-10` 收口）。
     *
     * <p>与执行面 {@code AgentRuntimeContextAssembler} 共用同一实现——两边各写一份会让
     * 同一模型在两条链上表现不一致（判据 4）。</p>
     */
    private final ImageInputCapabilityPolicy imageInputCapabilityPolicy;

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
        return generate(agent, systemPrompt, userPrompt, provider, apiKeyPlaintext, temperature, List.of());
    }

    @Override
    public ChatResponse generate(Agent agent, String systemPrompt, String userPrompt,
                                 String provider, String apiKeyPlaintext, Double temperature,
                                 List<Media> media) {
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
            return doGenerate(agent, systemPrompt, userPrompt, provider, apiKeyPlaintext, temperature, media);
        } finally {
            if (throttled) {
                llmCallConcurrencyGuard.release();
            }
        }
    }

    private ChatResponse doGenerate(Agent agent, String systemPrompt, String userPrompt,
                                    String provider, String apiKeyPlaintext, Double temperature,
                                    List<Media> media) {
        ChatClient chatClient = buildChatClient(agent, provider, apiKeyPlaintext);

        ChatClient.ChatClientRequestSpec prompt = chatClient.prompt();
        if (StringUtils.hasText(systemPrompt)) {
            prompt = prompt.system(systemPrompt);
        }
        // temperature 仅显式传入时覆盖模型默认（null = 不附加 options，现有链路行为不变）
        if (temperature != null) {
            prompt = prompt.options(ChatOptions.builder().temperature(temperature).build());
        }
        return applyUserMessage(prompt, agent, userPrompt, media)
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
    public Flux<LlmStreamChunk> generateStream(Agent agent, String systemPrompt, String userPrompt) {
        return generateStream(agent, systemPrompt, userPrompt, null, null);
    }

    public Flux<LlmStreamChunk> generateStream(Agent agent, String systemPrompt, String userPrompt,
                                               String provider, String apiKeyPlaintext) {
        return generateStream(agent, systemPrompt, userPrompt, provider, apiKeyPlaintext, List.of());
    }

    @Override
    public Flux<LlmStreamChunk> generateStream(Agent agent, String systemPrompt, String userPrompt,
                                               String provider, String apiKeyPlaintext, List<Media> media) {
        if (!executionProperties.isEnabled()) {
            throw new BizException("平台内 Agent 执行链已关闭");
        }
        boolean throttled = !executionProperties.isMockMode();
        if (throttled) {
            llmCallConcurrencyGuard.acquire();
        }
        return Flux.defer(() -> doGenerateStream(agent, systemPrompt, userPrompt, provider, apiKeyPlaintext, media))
                .doFinally(signal -> {
                    if (throttled) {
                        llmCallConcurrencyGuard.release();
                    }
                });
    }

    /**
     * 流式主实现：与 {@link #doGenerate} 同构组装 ChatClient，末端由
     * {@code call().chatResponse()} 换成 {@code stream().chatResponse()}。
     *
     * <p><b>为什么不是 {@code stream().content()}</b>（2026-10-18 修复）：
     * {@code content()} 只吐正文，推理模型的思考过程（DeepSeek {@code reasoningContent} /
     * Anthropic thinking block）被整体丢弃 ⇒ 前端「思考中」永远空白、用户只能干等。
     * 改走 {@code chatResponse()} 后每个增量 ChatResponse 交
     * {@link ChatResponseContentExtractor#extract} 分离——与同步链路**同一把尺子**，
     * 避免两条路径的 thinking 判定漂移。</p>
     *
     * <p>空块过滤：mock 分片不产生空串，真实 provider 首帧/流间隙可能出现空块
     * （正文或思考都为空）——只丢空块，保留空白与换行（它们是正文的一部分）。</p>
     */
    private Flux<LlmStreamChunk> doGenerateStream(Agent agent, String systemPrompt, String userPrompt,
                                                  String provider, String apiKeyPlaintext, List<Media> media) {
        ChatClient chatClient = buildChatClient(agent, provider, apiKeyPlaintext);

        ChatClient.ChatClientRequestSpec prompt = chatClient.prompt();
        if (StringUtils.hasText(systemPrompt)) {
            prompt = prompt.system(systemPrompt);
        }
        return applyUserMessage(prompt, agent, userPrompt, media)
                .stream()
                .chatResponse()
                .flatMapIterable(response -> {
                    ChatResponseContentExtractor.ExtractedContent extracted =
                            ChatResponseContentExtractor.extract(response);
                    // 思考增量先于正文增量：推理模型同一帧里两者可能都有，
                    // 前端按「思考→正文」顺序渲染才符合阅读直觉
                    List<LlmStreamChunk> chunks = new ArrayList<>(2);
                    if (!extracted.thinking().isEmpty()) {
                        chunks.add(LlmStreamChunk.reasoning(extracted.thinking()));
                    }
                    if (!extracted.text().isEmpty()) {
                        chunks.add(LlmStreamChunk.content(extracted.text()));
                    }
                    return chunks;
                });
    }

    /**
     * 组装 user 消息（同步 / 流式共用的<b>唯一</b>入口，`VU-9`）。
     *
     * <p><b>为什么抽出这一步</b>：媒体相关的判定有三条（能力 / 空媒体保原样 / 图文同条），
     * 同步与流式各写一份必然漂移——这正是 §2 #20「同一判据只修了一个消费方」的教训。
     * 两处调用本方法，判定只有一份。</p>
     *
     * <p><b>能力判定为什么在这里（不变量 I-2）</b>：{@code agent} 是<b>本次实际要调用的</b>
     * Agent（可能已被 {@code AgentModelPin} 或会话级 {@code modelType} 覆盖过），
     * 从这里解析出的 {@code modelType} 才等于「请求真正发往的模型」。
     * 计划里的原话是「前端选中 ≠ 实际调用」，写成先判后发就复刻了
     * OpenCode {@code #60736}（维护者判定：note 是每次构建请求时按实际使用的模型加的）。</p>
     *
     * <p><b>三态降级（不变量 I-4）</b>：</p>
     * <ul>
     *   <li><b>能看</b> —— 模型声明 {@code input} 含 {@code image} ⇒ 图以 {@code media(...)} 随正文送出；</li>
     *   <li><b>看不到（显式降级）</b> —— 模型不支持图文、或装载后一张都不剩 ⇒ 仍送纯文本，
     *       并<b>在正文后追加一行显式说明</b>，让模型知道「有图但你看不到」而不是「用户没给图」。
     *       这一行是「禁假装看过」的落地：模型对着一张不存在的图编内容是当前最坏的失败形态；</li>
     *   <li><b>无媒体</b> —— 走原 {@code .user(String)}，请求体逐字不变（回归面不放大）。</li>
     * </ul>
     *
     * <p><b>装载失败一律降级不外抛</b>：单张图读取异常在
     * {@code AttachmentMediaAssembler} 内已逐条 catch；若整批都失败，走上面的「看不到」分支。</p>
     */
    private ChatClient.ChatClientRequestSpec applyUserMessage(ChatClient.ChatClientRequestSpec spec,
                                                              Agent agent, String userPrompt, List<Media> media) {
        String text = userPrompt != null ? userPrompt : "";
        if (media == null || media.isEmpty()) {
            // 零媒体：与原实现逐字一致，不调带 media 的重载
            return spec.user(text);
        }
        if (!supportsImageInput(agent)) {
            //  ★I-4：降级必须显式。原样送文本 + 明确告知「图在但看不到」，
            //  绝不让模型把「没有图」当成事实继续编。
            log.info("模型不支持图片输入，{} 张图降级为纯文本并显式告知: modelType={}",
                    media.size(), agent == null ? null : agent.getModelType());
            return spec.user(text + "\n\n" + MEDIA_NOT_VISIBLE_NOTE);
        }
        return spec.user(user -> user.text(text).media(media.toArray(new Media[0])));
    }

    /**
     * 模型元数据是否声明可读图片。
     *
     * <p><b>判定本体已外移到 {@link ImageInputCapabilityPolicy}</b>（`VU-10`）：执行面
     * {@code AgentRuntimeContextAssembler} 需要同一判定，两处各写一份必然分叉
     * （判据 4）——最坏形态是同一条链「对话面能看图、执行面不能看」。
     * 本方法只做委托，保留调用点可读性（{@code applyUserMessage} 里语义自解释）。</p>
     */
    private boolean supportsImageInput(Agent agent) {
        return imageInputCapabilityPolicy.supportsImageInput(agent);
    }
    /**
     * 构建底层 ChatModel（P0-B-2）：mock 模式返回 MockChatModel；真实模式经
     * providerRegistry.createChatModel 返回缓存实例（与 buildChatClient 共享同一模型）。
     *
     * <p>与 {@link #buildChatClient} 的凭据路径差异：容器内 ChatClient.Builder 无
     * ChatModel 出口，本方法要求显式 provider + API Key（Runtime AgentLoop 循环调用需要），
     * 缺失时抛 BizException（契约化，不静默回退）。</p>
     */
    @Override
    public ChatModel buildChatModel(Agent agent, String provider, String apiKeyPlaintext) {
        ChatModel raw = buildRawChatModel(agent, provider, apiKeyPlaintext);
        // ★G8：出口统一包并发限流装饰器 —— 执行链（RuntimeTurnExecutor →
        // ChatModelToolLoop / ContextCompactor / FinalReportFenceHook）此前拿的是裸 ChatModel，
        // 完全绕过 LlmCallConcurrencyGuard（其 javadoc 却声称覆盖「执行」）⇒ maxConcurrentLlmCalls
        // 对最高频路径失效。在此包装即全覆盖，且与 chat 路径（buildChatClient 的独立缝、
        // 已自带守卫）不重复获取许可（避免信号量死锁）。
        // mock 模式不占许可（本地即时返回无上游压力），与 chat 路径判定口径一致。
        return new ConcurrencyGuardedChatModel(
                raw, llmCallConcurrencyGuard, !executionProperties.isMockMode());
    }

    /**
     * 原 {@code buildChatModel} 主体（未包装版）。
     *
     * <p>拆出仅为让「装配裸模型」与「叠加并发限流」两件事可分别测试；
     * 生产调用方应走 {@link #buildChatModel}（带限流）。</p>
     */
    private ChatModel buildRawChatModel(Agent agent, String provider, String apiKeyPlaintext) {
        if (executionProperties.isMockMode()) {
            return new MockChatModel(
                    executionProperties.getProvider(),
                    executionProperties.getModel(),
                    executionProperties.getMockResponsePrefix(),
                    agent);
        }
        if (apiKeyPlaintext != null && !apiKeyPlaintext.isBlank()) {
            String effectiveProvider = provider != null && !provider.isBlank()
                    ? provider : executionProperties.getProvider();
            String model = AgentProviderResolver.resolveModel(agent, null);
            return providerRegistry.createChatModel(effectiveProvider, apiKeyPlaintext, agent, model);
        }
        throw new BizException("Runtime 循环需可解析的 provider + API Key（真实模式）；"
                + "容器内 ChatClient.Builder 无 ChatModel 出口，请走显式凭据或 mock 模式");
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
