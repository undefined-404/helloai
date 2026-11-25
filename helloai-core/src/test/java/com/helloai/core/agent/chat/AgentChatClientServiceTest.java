package com.helloai.core.agent.chat;

import com.helloai.common.config.AgentExecutionProperties;
import com.helloai.common.constant.AgentRole;
import com.helloai.core.agent.chat.provider.LlmProviderChatClientFactoryRegistry;
import com.helloai.core.agent.entity.Agent;
import com.helloai.core.agent.service.AgentChatClientService;
import com.helloai.core.agent.service.impl.AgentChatClientServiceImpl;
import com.helloai.core.agent.support.ImageInputCapabilityPolicy;
import com.helloai.core.system.port.LlmProviderModelProfile;
import com.helloai.core.system.port.ModelCapabilities;
import com.helloai.core.system.service.LlmProviderModelQueryService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.content.Media;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.util.MimeTypeUtils;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * AgentChatClientService 单元测试。覆盖：mock 模式 / 空 systemPrompt 也能走通 /
 * `VU-9` 对话面视觉直读的三态判据。
 */
@DisplayName("AgentChatClientService")
class AgentChatClientServiceTest {

    /**
     * 显式 provider 名（走 {@code providerRegistry} 分支）。
     *
     * <p><b>为什么必须显式给凭据</b>：{@code buildChatClient} 有三条分支——
     * ①mock 模式；②{@code apiKeyPlaintext} 非空走 registry；③容器内 Builder。
     * 本类要在<b>请求体</b>上断言媒体，而 mock 模式的 {@code MockChatModel} 记录不到请求体，
     * 所以②是唯一可用通道。若两参都传 {@code null} 会落到③，测试环境无 Spring 容器
     * ⇒ {@code chatClientBuilderProvider.getIfAvailable()} 返回 null ⇒ 抛
     * 「未检测到 ChatClient.Builder」。这不是产品缺陷，是测试没走对被则路径。</p>
     */
    private static final String TEST_PROVIDER = "deepseek";

    /** 同上。内容无意义，只要求非空白（值仅在 registry 桩里被忽略）。 */
    private static final String TEST_API_KEY = "test-api-key";

    // ---------- 既有判据（不得因 VU-9 改动而变） ----------

    @Test
    @DisplayName("空 systemPrompt 时仍可走通 mock ChatClient")
    void shouldGenerateWhenSystemPromptIsBlank() {
        AgentChatClientService service = newService(null);

        ChatResponse response = service.generate(agent("mock-agent", null), null, "请输出一句最小验证结果");

        assertThat(response).isNotNull();
        assertThat(response.getResult()).isNotNull();
        assertThat(response.getResult().getOutput()).isNotNull();
        assertThat(response.getResult().getOutput().getText()).contains("[mock-executor]");
    }

    @Test
    @DisplayName("mock 模式流式：分片拼接与同步全量一致，流可正常完成")
    void shouldStreamJoinedMatchesSyncOutput() {
        AgentChatClientService service = newService(null);
        Agent agent = agent("mock-agent", null);

        // 流式路径：分片伪流式（MockChatModel.stream），OnNext 拼接后与同步 call 文本严格相等
        // ★2026-10-18：流式块已类型化（LlmStreamChunk）——只拼 CONTENT，思考块（REASONING）
        // 按契约不进正文；mock 模型无 reasoning，故这里同时验证「不会误把正文当思考」
        String joined = service.generateStream(agent, null, "请输出一句最小验证结果")
                .collectList()
                .block()
                .stream()
                .filter(chunk -> chunk.type() == LlmStreamChunk.Type.CONTENT)
                .map(LlmStreamChunk::text)
                .reduce("", String::concat);
        ChatResponse sync = service.generate(agent, null, "请输出一句最小验证结果");

        assertThat(joined).isEqualTo(sync.getResult().getOutput().getText());
        assertThat(joined).contains("[mock-executor]");
    }

    // ---------- VU-9：三态判据（不变量 I-2 / I-4） ----------

    @Test
    @DisplayName("VU-9 判据⑤：零媒体时请求体与改动前逐字一致，且不追加任何降级说明")
    void shouldKeepRequestBodyIdenticalWithoutMedia() {
        RecordingChatModel model = new RecordingChatModel();
        AgentChatClientService service = newServiceWithModel(model, null);

        service.generate(agent("mock-agent", "deepseek:deepseek-flash"), "sys", "纯文本提问",
                TEST_PROVIDER, TEST_API_KEY, null);

        UserMessage sent = model.lastUserMessage();
        assertThat(sent.getMedia()).isEmpty();
        // ★ 关键：正文逐字一致 —— 空媒体不得触发「看不到图」那句说明
        assertThat(sent.getText()).isEqualTo("纯文本提问");
    }

    @Test
    @DisplayName("VU-9 判据①：模型声明 image 能力时，媒体随正文进同一条 UserMessage")
    void shouldAttachMediaWhenModelSupportsImage() {
        RecordingChatModel model = new RecordingChatModel();
        AgentChatClientService service = newServiceWithModel(model, capabilities("text", "image"));

        service.generate(agent("v", "deepseek:deepseek-flash"), "sys", "这张图里是什么",
                TEST_PROVIDER, TEST_API_KEY, null, List.of(imageMedia()));

        UserMessage sent = model.lastUserMessage();
        assertThat(sent.getMedia()).hasSize(1);
        assertThat(sent.getMedia().get(0).getMimeType().toString()).isEqualTo("image/png");
        // 图文同条：不得因带图而把正文丢掉，也不得把图拆成第二条 UserMessage
        assertThat(sent.getText()).isEqualTo("这张图里是什么");
        assertThat(RecordingChatModel.userMessagesOf(model.lastPrompt())).hasSize(1);
    }

    @Test
    @DisplayName("VU-9 判据③：模型不支持图片时降级为纯文本 + 显式说明（禁假装看过）")
    void shouldDegradeExplicitlyWhenModelSupportsTextOnly() {
        RecordingChatModel model = new RecordingChatModel();
        AgentChatClientService service = newServiceWithModel(model, capabilities("text"));

        service.generate(agent("v", "deepseek:deepseek-flash"), "sys", "这张图里是什么",
                TEST_PROVIDER, TEST_API_KEY, null, List.of(imageMedia()));

        UserMessage sent = model.lastUserMessage();
        // 图不送（模型会 400），但必须显式告知「有图但看不到」，并禁止凭文件名猜
        assertThat(sent.getMedia()).isEmpty();
        assertThat(sent.getText()).startsWith("这张图里是什么");
        assertThat(sent.getText()).contains("无法查看图像内容");
        assertThat(sent.getText()).contains("推测图片内容");
    }

    @Test
    @DisplayName("VU-9 判据⑦（不变量 I-2）：能力按「本次实际调用的模型」解析，不按前端选中")
    void shouldResolveCapabilityByActualModel() {
        RecordingChatModel model = new RecordingChatModel();
        // 场景复刻 OpenCode #60736：前端以为选的是视觉模型，但实际路由到的 Agent
        // 绑的是纯文本模型 ⇒ 必须以「实际 modelType」判定 ⇒ 降级而非崩溃
        Agent actualTextOnly = agent("v", "deepseek:deepseek-chat");
        AgentChatClientService service = newServiceWithModel(model, capabilities("text"));

        service.generate(actualTextOnly, "sys", "看图", TEST_PROVIDER, TEST_API_KEY, null, List.of(imageMedia()));

        UserMessage sent = model.lastUserMessage();
        assertThat(sent.getMedia()).isEmpty();
        assertThat(sent.getText()).contains("无法查看图像内容");
    }

    @Test
    @DisplayName("VU-9 判据⑦补：查不到模型配置时 fail-close（按不支持处理，不外抛）")
    void shouldFailCloseWhenProfileMissing() {
        RecordingChatModel model = new RecordingChatModel();
        AgentChatClientService service = newServiceWithModel(model, null);

        service.generate(agent("v", "unknown:model"), "sys", "看图",
                TEST_PROVIDER, TEST_API_KEY, null, List.of(imageMedia()));

        UserMessage sent = model.lastUserMessage();
        assertThat(sent.getMedia()).isEmpty();
        assertThat(sent.getText()).contains("无法查看图像内容");
    }

    @Test
    @DisplayName("VU-9 判据④：流式入口与同步入口同一判据（带图时同样把媒体送出）")
    void shouldAttachMediaOnStreamPathToo() {
        RecordingChatModel model = new RecordingChatModel();
        AgentChatClientService service = newServiceWithModel(model, capabilities("text", "image"));

        service.generateStream(agent("v", "deepseek:deepseek-flash"), "sys", "看图",
                        TEST_PROVIDER, TEST_API_KEY, List.of(imageMedia()))
                .collectList()
                .block();

        UserMessage sent = model.lastUserMessage();
        assertThat(sent.getMedia()).hasSize(1);
        assertThat(sent.getText()).isEqualTo("看图");
    }

    // ---------- 构造辅助 ----------

    private static Agent agent(String name, String modelType) {
        Agent agent = new Agent();
        agent.setId(101L);
        agent.setName(name);
        agent.setRole(AgentRole.EXECUTOR);
        agent.setModelType(modelType);
        return agent;
    }

    /** 声明指定输入模态的能力快照。 */
    private static LlmProviderModelProfile capabilities(String... inputs) {
        return new LlmProviderModelProfile(new ModelCapabilities(
                List.of(inputs), List.of("text"), false, false, false));
    }

    private static Media imageMedia() {
        return Media.builder()
                .mimeType(MimeTypeUtils.parseMimeType("image/png"))
                .data(new ByteArrayResource(new byte[]{1, 2, 3}))
                .name("shot.png")
                .build();
    }

    /** mock 模式 + 指定能力查询结果（null = 查不到模型）。 */
    private static AgentChatClientService newService(LlmProviderModelProfile profile) {
        return buildService(null, profile);
    }

    /** 用「能记录请求体的假 ChatModel」替换 mock 模型，便于在请求体上断言。 */
    private static AgentChatClientService newServiceWithModel(ChatModel model, LlmProviderModelProfile profile) {
        return buildService(model, profile);
    }

    /**
     * 构造被测服务。
     *
     * <p><b>为什么走「显式 provider + 假模型」而不是 mock 模式</b>：mock 模式的
     * {@code buildChatClient} 固定包 {@code MockChatModel}，无法记录请求体，
     * 而本类要断言的正是「媒体是否真的进了请求体」（判据⑧：在请求体上断言，不在 DB 上断言）。
     * 走 {@code apiKeyPlaintext} 非空 + registry 返回假模型这条路，经过的仍是
     * {@link AgentChatClientServiceImpl#generate} 的全部判定，只把底层模型换成可观测实现。</p>
     *
     * @param model   可观测假模型；{@code null} = 走 mock 模式（既有两条用例用）
     * @param profile 能力查询返回的快照；{@code null} = 查不到模型（fail-close 路径）
     */
    private static AgentChatClientService buildService(ChatModel model, LlmProviderModelProfile profile) {
        AgentExecutionProperties properties = new AgentExecutionProperties();
        properties.setEnabled(true);
        properties.setMockMode(model == null);
        properties.setProvider("mock");
        properties.setModel("helloai-mock-executor");
        properties.setMockResponsePrefix("[mock-executor]");

        // 主类保持 ObjectProvider 惰性（§6.139：Optional 注入会立即解析 chatClientBuilder
        // 候选并触发 ChatModel 依赖解析导致启动炸）；mock 模式不触达 Builder，空 provider 即可
        ObjectProvider<ChatClient.Builder> builderProvider = mock(ObjectProvider.class);
        LlmProviderChatClientFactoryRegistry registry = mock(LlmProviderChatClientFactoryRegistry.class);
        if (model != null) {
            when(registry.createChatClient(anyString(), anyString(), any(), any()))
                    .thenReturn(ChatClient.create(model));
        }
        LlmCallConcurrencyGuard guard = new LlmCallConcurrencyGuard(properties);
        LlmProviderModelQueryService queryService = mock(LlmProviderModelQueryService.class);
        if (profile != null) {
            when(queryService.findCapabilityProfileByModelType(anyString())).thenReturn(profile);
        }
        // `VU-10`：能力判定已外移到 ImageInputCapabilityPolicy（执行面共用同一实现）。
        // 这里用**真实策略**包住同一个 queryService 桩 ⇒ 本类既有的「能力命中 / 未命中」
        // 用例判据不变（仍由 profile 控制），同时顺带验证了「策略委托查询服务」这条路。
        ImageInputCapabilityPolicy capabilityPolicy = new ImageInputCapabilityPolicy(queryService);
        return new AgentChatClientServiceImpl(properties, builderProvider, registry, guard,
                queryService, capabilityPolicy);
    }

    /**
     * 记录最后一次请求的可观测 ChatModel。
     *
     * <p>同步与流式都从 {@link #call(Prompt)} 的入参取 {@code Prompt}，
     * 故「媒体是否真的进了请求体」可在这一层断言（呼应判据⑧：
     * 在请求体上断言，不在 DB 上断言）。</p>
     */
    private static final class RecordingChatModel implements ChatModel {

        private Prompt lastPrompt;

        @Override
        public ChatResponse call(Prompt prompt) {
            this.lastPrompt = prompt;
            return new ChatResponse(List.of(new Generation(
                    new org.springframework.ai.chat.messages.AssistantMessage("ok"))), null);
        }

        @Override
        public reactor.core.publisher.Flux<ChatResponse> stream(Prompt prompt) {
            this.lastPrompt = prompt;
            return reactor.core.publisher.Flux.just(call(prompt));
        }

        Prompt lastPrompt() {
            return lastPrompt;
        }

        UserMessage lastUserMessage() {
            for (Message message : lastPrompt().getInstructions()) {
                if (message instanceof UserMessage userMessage) {
                    return userMessage;
                }
            }
            throw new AssertionError("请求体中没有 UserMessage");
        }

        /** 请求体里全部 UserMessage（用于断言「图文同条」而不是「图另起一条」）。 */
        static List<UserMessage> userMessagesOf(Prompt prompt) {
            List<UserMessage> result = new ArrayList<>();
            for (Message message : prompt.getInstructions()) {
                if (message instanceof UserMessage userMessage) {
                    result.add(userMessage);
                }
            }
            return result;
        }
    }
}