package com.helloai.core.agent.chat.provider;

import com.helloai.common.base.BizException;
import com.helloai.common.config.AgentProviderProperties;
import com.helloai.core.agent.service.PlatformProviderConfigService;
import com.helloai.core.agent.entity.Agent;
import io.micrometer.observation.ObservationRegistry;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.deepseek.DeepSeekChatModel;
import org.springframework.ai.deepseek.DeepSeekChatOptions;
import org.springframework.ai.deepseek.api.DeepSeekApi;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.retry.support.RetryTemplate;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * DeepSeek 专用 ChatClient 工厂（官方 SDK 通道）。
 *
 * <p><b>2026-11-25 起停用（{@link Deprecated}）</b>：不再被
 * {@link LlmProviderChatClientFactoryRegistry} 路由（deepseek 改走 OpenAI 兼容通道）。</p>
 *
 * <p><b>停用原因</b>：`spring-ai-deepseek:1.1.8` 的 {@code DeepSeekChatModel#createRequest}
 * 对 USER 消息只构造 {@code new ChatCompletionMessage(message.getText(), Role.USER)}，
 * <b>没有 media 分支</b>——图片被静默丢弃且 provider 不报错（对照实验与官方文档证明
 * deepseek-flash 本身支持图像理解，详见迁移 V155 头部「② 能力订正的依据」）。
 * 该静默失效违反 I-4「禁假装看过」（模型拿不到像素却可能按文件名编造）。
 * 修复（方案 A）：改走 {@link OpenAiCompatibleProtocolFactory}（OpenAiChatModel 的
 * {@code mapToMediaContent} 可装配图片），能力声明于迁移 V156 加回 image。</p>
 *
 * <p><b>为什么保留本类（不删除）</b>：</p>
 * <ol>
 *   <li>{@code verify-provider-catalog-parity.sh} 检查 5（D-e）以本类
 *       {@link #DEFAULT_MODEL} 常量作为 deepseek 末位兜底的校验载体；</li>
 *   <li>恢复点：若上游 SDK 修复 media 分支（或自建带 image_url 的 ChatModel），
 *       可重新启用本工厂并反向迁移 V156（同步改回能力声明口径）。</li>
 * </ol>
 *
 * <p><b>方案 A 的代价记录</b>：OpenAI 兼容通道（spring-ai-openai）响应侧不解析
 * DeepSeek 的 {@code reasoning_content} ⇒ 丢失 thinking 展示；正文与工具调用不受影响。</p>
 *
 * @deprecated 停用日期 2026-11-25：deepseek 改走 OpenAI 兼容通道（见 V156 与
 *             {@link LlmProviderChatClientFactoryRegistry} 类注释），本类仅作 D-e 守卫载体与恢复点。
 */
@Deprecated
@Component
@RequiredArgsConstructor
public class DeepSeekProviderChatClientFactory implements ProviderChatClientFactory {

    private static final String PROVIDER = "deepseek";
    /** 末位兜底模型：须恒等于目录默认位（`llm_provider_model.is_default=1`）。
     * V155 起为官方现行名 `deepseek-flash`（旧值 'deepseek-v4-flash' 是废弃别名）。 */
    private static final String DEFAULT_MODEL = "deepseek-flash";

    private final ToolCallingManager toolCallingManager;
    private final RetryTemplate retryTemplate;
    private final ObservationRegistry observationRegistry;
    private final AgentProviderProperties providerProperties;
    private final ProviderChatModelCache cache;
    private final PlatformProviderConfigService platformProviderConfigService;

    @Override
    public boolean supports(String provider) {
        return PROVIDER.equalsIgnoreCase(provider);
    }

    @Override
    public ChatClient createChatClient(String apiKeyPlaintext, Agent agent, String model) {
        return ChatClient.create(createChatModel(apiKeyPlaintext, agent, model));
    }

    @Override
    public ChatModel createChatModel(String apiKeyPlaintext, Agent agent, String model) {
        if (apiKeyPlaintext == null || apiKeyPlaintext.isBlank()) {
            throw new BizException("apiKey 不能为空");
        }

        AgentProviderProperties.ProviderConfig config = providerProperties.getConfig(PROVIDER);
        // baseUrl 动态化：sys_config > yml > null（null 时 DeepSeekApi 走官方默认）
        String baseUrl = platformProviderConfigService.getBaseUrl(PROVIDER);

        // N9: 按 (provider, baseUrl, apiKey, protocolType, model) 五元组复用 ChatModel 实例，避免每次请求重
        // 建 RestClient/连接池。idempotencyKey / 上下文 model 切换由 ChatClient 层的
        // options/advisor 覆盖，不影响本缓存 key。protocolType=deepseek 是一个独立桶，与
        // OpenAI 兼容 / Anthropic 兼容 ChatModel 隔离。model 维度保证 Agent 改 model_type
        // 后自动重建实例（此前缺 model 需重启才生效）。
        String cacheKey = ProviderChatModelCache.buildKey(PROVIDER, apiKeyPlaintext, baseUrl, PROVIDER, model);

        return cache.getOrCompute(cacheKey, () -> buildChatModel(apiKeyPlaintext, config, baseUrl, model));
    }

    /**
     * 仅供 {@link #createChatClient} 内部缓存未命中时调用：构造并初始化 DeepSeekChatModel。
     * 与原有逻辑等价，只是拆出来便于测试覆盖。
     */
    private ChatModel buildChatModel(String apiKeyPlaintext,
                                     AgentProviderProperties.ProviderConfig config,
                                     String baseUrl,
                                     String requestedModel) {
        DeepSeekApi.Builder apiBuilder = DeepSeekApi.builder().apiKey(apiKeyPlaintext);
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(Math.max(config.getConnectTimeoutMs(), 1));
        requestFactory.setReadTimeout(Math.max(config.getReadTimeoutMs(), 1));
        apiBuilder.restClientBuilder(RestClient.builder().requestFactory(requestFactory));
        if (baseUrl != null && !baseUrl.isBlank()) {
            apiBuilder.baseUrl(baseUrl);
        }

        // defaultModel 动态化：sys_config > yml > 内置默认
        String dynamicDefaultModel = platformProviderConfigService.getDefaultModel(PROVIDER);
        String effectiveModel = requestedModel != null && !requestedModel.isBlank()
                ? requestedModel
                : (dynamicDefaultModel != null && !dynamicDefaultModel.isBlank()
                        ? dynamicDefaultModel
                        : DEFAULT_MODEL);

        DeepSeekChatOptions options = DeepSeekChatOptions.builder()
                .model(effectiveModel)
                .build();

        return DeepSeekChatModel.builder()
                .deepSeekApi(apiBuilder.build())
                .defaultOptions(options)
                .toolCallingManager(toolCallingManager)
                .retryTemplate(retryTemplate)
                .observationRegistry(observationRegistry)
                .build();
    }
}
