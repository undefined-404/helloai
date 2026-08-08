package com.helloai.core.agent.chat;

import com.helloai.common.base.BizException;
import com.helloai.common.config.AgentExecutionProperties;
import com.helloai.core.agent.chat.provider.LlmProviderChatClientFactoryRegistry;
import com.helloai.core.agent.entity.Agent;
import com.helloai.core.system.entity.LlmProvider;
import com.helloai.core.system.service.CredentialVaultBindingService;
import com.helloai.core.system.service.CredentialVaultService;
import com.helloai.core.system.service.LlmProviderQueryService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * LLM Provider 目录服务（方案B）。
 *
 * <p>基于 {@code llm_provider} 表枚举"已生效"的 provider：已配置平台级 api-key 且存在对应
 * ChatClient 工厂实现（DeepSeek 专用 / OpenAI 兼容 / Anthropic 兼容）。供前端手动注册
 * 平台内 API_KEY_LLM Agent 时选择，并在注册后把平台级密钥绑定进 credential_vault
 * （满足 AgentSelector 的凭证可用性检查）。</p>
 *
 * <p>新增 provider 仅需在 {@code llm_provider} 表插入一条记录，无需新增 Java 类
 * （仅 OpenAI / Anthropic 兼容协议范围内）。后续扩展 Gemini 原生协议时再补 Registry。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LlmProviderCatalogService {

    private final LlmProviderQueryService llmProviderQueryService;
    private final AgentExecutionProperties executionProperties;
    private final LlmProviderChatClientFactoryRegistry factoryRegistry;
    private final CredentialVaultService credentialVaultService;
    private final CredentialVaultBindingService credentialVaultBindingService;
    private final PlatformProviderConfigService platformProviderConfigService;

    /**
     * Provider 目录项。
     *
     * @param provider        provider 唯一标识（小写）
     * @param providerName    显示名
     * @param protocolType    协议类型
     * @param defaultModel    默认模型
     * @param apiKeyConfigured 是否已配置平台级 api-key
     * @param factorySupported 是否存在对应 ChatClient 工厂实现
     * @param available       是否可用于手动注册（apiKeyConfigured && factorySupported && enabled）
     */
    public record ProviderCatalogItem(String provider, String providerName, String protocolType,
                                      String defaultModel,
                                      boolean apiKeyConfigured, boolean factorySupported,
                                      boolean available) {
    }

    /**
     * 枚举全部已配置的 provider 及其可用性。
     *
     * <p>数据源：{@code llm_provider} 表 + PlatformProviderConfigService 的 API Key 可用性。
     * 工厂支持性：通过 {@link LlmProviderChatClientFactoryRegistry#createChatClient} 试探路由
     * （不实际创建 ChatClient，仅判断 protocolType 是否被任何 Factory 支持）。</p>
     */
    public List<ProviderCatalogItem> listProviders() {
        List<LlmProvider> providers = llmProviderQueryService.listAll();
        List<ProviderCatalogItem> items = new ArrayList<>(providers.size());
        for (LlmProvider p : providers) {
            String name = p.getProviderCode().toLowerCase(Locale.ROOT);
            boolean enabled = Integer.valueOf(1).equals(p.getEnabled());
            boolean factorySupported = isFactorySupported(p);
            boolean apiKeyConfigured = platformProviderConfigService.isApiKeyConfigured(name);
            items.add(new ProviderCatalogItem(
                    name,
                    p.getProviderName(),
                    p.getProtocolType(),
                    p.getDefaultModel(),
                    apiKeyConfigured,
                    factorySupported,
                    enabled && apiKeyConfigured && factorySupported));
        }
        return items;
    }

    /**
     * 判断 provider 是否"已生效"（可用于手动注册平台内 LLM Agent）。
     */
    public boolean isProviderAvailable(String provider) {
        if (provider == null || provider.isBlank()) {
            return false;
        }
        return listProviders().stream()
                .anyMatch(item -> item.provider().equalsIgnoreCase(provider) && item.available());
    }

    /**
     * 为缺少凭证的 API_KEY_LLM Agent 补绑平台级密钥。
     *
     * <p>幂等保护：Agent 对该 provider 已有 ACTIVE 凭证时直接跳过，
     * 不覆盖手动绑定/轮换过的自定义密钥（幂等注册复用场景）。</p>
     *
     * @return true 表示本次实际执行了绑定
     */
    public boolean bindPlatformApiKeyIfAbsent(Long agentId, String provider) {
        if (!isProviderAvailable(provider)) {
            throw new BizException("Provider '" + provider + "' 未配置平台 API Key 或缺少 Factory 实现，无法注册平台内 LLM Agent");
        }
        if (credentialVaultService.getActiveAgentApiKey(agentId, provider) != null) {
            log.info("Agent 已有 {} 的启用态凭证，跳过平台密钥补绑: agentId={}", provider, agentId);
            return false;
        }
        String apiKey = platformProviderConfigService.getApiKey(provider);
        if (apiKey == null || apiKey.isBlank()) {
            throw new BizException("Provider '" + provider + "' 未配置平台 API Key，无法注册平台内 LLM Agent");
        }
        credentialVaultBindingService.bindAgentApiKey(agentId, provider, apiKey, null,
                "平台配置自动绑定（平台级凭证/yml 兜底）");
        log.info("平台密钥已自动绑定: agentId={}, provider={}", agentId, provider);
        return true;
    }

    /**
     * API_KEY_LLM Agent 注册后的平台密钥自动供给（尽力而为）。
     *
     * <p>按 Agent.modelType 前缀（缺省回退 {@code helloai.execution.provider}）解析
     * provider；provider 已生效且 Agent 缺凭证时补绑平台密钥，否则仅记录日志跳过，
     * 不阻断注册主流程——脚本注册后自行绑定自定义密钥的既有链路保持不变。</p>
     *
     * @return true 表示本次实际执行了绑定
     */
    public boolean provisionPlatformCredential(Agent agent) {
        String provider = AgentProviderResolver.resolveProvider(agent, executionProperties.getProvider());
        if (!isProviderAvailable(provider)) {
            log.warn("Provider '{}' 未生效，跳过平台密钥自动供给: agentId={}", provider, agent.getId());
            return false;
        }
        return bindPlatformApiKeyIfAbsent(agent.getId(), provider);
    }

    /**
     * 判断 llm_provider 记录是否被某个 ChatClient 工厂支持。
     *
     * <p>仅凭 protocolType 判定：deepseek 走专用 Factory；其他按协议类型分发。
     * 真正的 ChatClient 创建校验留给 {@link LlmProviderChatClientFactoryRegistry}。</p>
     */
    private boolean isFactorySupported(LlmProvider provider) {
        if (provider == null || provider.getProtocolType() == null) {
            return false;
        }
        String type = provider.getProtocolType().toUpperCase(Locale.ROOT);
        if ("deepseek".equalsIgnoreCase(provider.getProviderCode())) {
            return true;
        }
        return "OPENAI_COMPATIBLE".equals(type) || "ANTHROPIC_COMPATIBLE".equals(type);
    }
}
