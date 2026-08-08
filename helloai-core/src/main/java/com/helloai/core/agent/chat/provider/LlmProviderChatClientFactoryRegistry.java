package com.helloai.core.agent.chat.provider;

import com.helloai.common.base.BizException;
import com.helloai.core.agent.entity.Agent;
import com.helloai.core.system.entity.LlmProvider;
import com.helloai.core.system.service.LlmProviderQueryService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * LLM Provider ChatClient 路由注册中心（方案B关键类）。
 *
 * <p>按 provider.protocolType 把 ChatClient 创建请求分发给对应的 ProtocolFactory。
 * 替代原 providerChatClientFactoriesProvider.getIfAvailable() 枚举机制。</p>
 *
 * <p>DeepSeek 由于走官方 SDK（DeepSeekChatModel）而非 OpenAI 兼容，保留专用 Factory，
 * 通过 {@code providerCode="deepseek"} 优先匹配。</p>
 *
 * <p>所有 ChatClient 工厂都通过本类对外暴露，调用方无需感知 OpenAI / Anthropic 协议差异。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LlmProviderChatClientFactoryRegistry {

    private final LlmProviderQueryService queryService;
    private final DeepSeekProviderChatClientFactory deepSeekFactory;
    private final OpenAiCompatibleProtocolFactory openAiCompatibleFactory;
    private final AnthropicCompatibleProtocolFactory anthropicCompatibleFactory;

    private volatile Map<String, ProtocolFactory> protocolFactoryMap;

    private Map<String, ProtocolFactory> protocolFactoryMap() {
        Map<String, ProtocolFactory> map = protocolFactoryMap;
        if (map == null) {
            synchronized (this) {
                map = protocolFactoryMap;
                if (map == null) {
                    map = List.<ProtocolFactory>of(openAiCompatibleFactory, anthropicCompatibleFactory)
                            .stream()
                            .collect(Collectors.toMap(ProtocolFactory::protocolType, Function.identity()));
                    protocolFactoryMap = map;
                }
            }
        }
        return map;
    }

    /**
     * 按 providerCode 创建 ChatClient。
     *
     * <p>路由优先级：DeepSeek 专用 Factory &gt; 通用 OpenAI 兼容 Factory &gt; 通用 Anthropic 兼容 Factory。</p>
     *
     * @param providerCode    provider 唯一标识（如 deepseek / moonshot / custom-gpt-4）
     * @param apiKeyPlaintext API Key 明文
     * @param agent           Agent 实体（用于上下文）
     * @param model           请求模型（可空，使用 DB 配置的默认模型）
     * @return ChatClient
     * @throws BizException 当 provider 不存在 / 未启用 / 协议不支持时
     */
    public ChatClient createChatClient(String providerCode, String apiKeyPlaintext, Agent agent, String model) {
        LlmProvider provider = queryService.findByCode(providerCode)
                .orElseThrow(() -> new BizException("Provider 未找到或未启用: " + providerCode));

        // DeepSeek 走官方 SDK（DeepSeekChatModel），优先匹配专用 Factory
        if ("deepseek".equalsIgnoreCase(provider.getProviderCode()) && deepSeekFactory.supports(provider.getProviderCode())) {
            return deepSeekFactory.createChatClient(apiKeyPlaintext, agent, model);
        }

        ProtocolFactory factory = protocolFactoryMap().get(provider.getProtocolType());
        if (factory == null) {
            throw new BizException("不支持的 protocol_type: " + provider.getProtocolType()
                    + "（provider=" + provider.getProviderCode() + "）");
        }
        return factory.createChatClient(provider, apiKeyPlaintext, agent, model);
    }

    /**
     * 通用协议工厂接口（按协议类型聚合 ChatClient 构建逻辑）。
     */
    public interface ProtocolFactory {

        /**
         * 协议类型标识（OPENAI_COMPATIBLE / ANTHROPIC_COMPATIBLE）。
         */
        String protocolType();

        /**
         * 根据 LlmProvider 配置创建 ChatClient。
         */
        ChatClient createChatClient(LlmProvider provider, String apiKeyPlaintext, Agent agent, String model);
    }
}
