package com.helloai.core.agent.service.impl;

import com.helloai.core.agent.chat.AgentProviderResolver;
import com.helloai.core.agent.service.LlmProviderCatalogService;
import com.helloai.core.agent.service.LlmProviderCatalogService.ProviderCatalogItem;
import com.helloai.core.agent.service.PlatformProviderConfigService;
import com.helloai.common.base.BizException;
import com.helloai.common.config.AgentExecutionProperties;
import com.helloai.core.agent.chat.provider.LlmProviderChatClientFactoryRegistry;
import com.helloai.core.agent.entity.Agent;
import com.helloai.core.system.entity.LlmProvider;
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
 * 平台内 API_KEY_LLM Agent 时选择；执行链密钥由 AgentLlmCredentialResolver
 * 按 modelType 关联平台级凭证实时解析（内部 LLM Agent 不持有 AGENT 级快照，
 * 系统管理轮换 API Key 后已注册 Agent 实时生效）。</p>
 *
 * <p>新增 provider 仅需在 {@code llm_provider} 表插入一条记录，无需新增 Java 类
 * （仅 OpenAI / Anthropic 兼容协议范围内）。后续扩展 Gemini 原生协议时再补 Registry。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LlmProviderCatalogServiceImpl implements LlmProviderCatalogService {

    private final LlmProviderQueryService llmProviderQueryService;
    private final AgentExecutionProperties executionProperties;
    private final LlmProviderChatClientFactoryRegistry factoryRegistry;
    private final PlatformProviderConfigService platformProviderConfigService;

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
     * API_KEY_LLM Agent 注册后的平台凭证核对（尽力而为，不阻断注册）。
     *
     * <p>按 Agent.modelType 前缀（缺省回退 {@code helloai.execution.provider}）解析
     * provider；provider 未生效时仅记录日志跳过——内部 LLM Agent 执行链不再依赖
     * AGENT 级密钥快照（旧版注册时会把平台密钥复制进 credential_vault，导致
     * 系统管理轮换 API Key 后已注册 Agent 仍用旧 Key），执行密钥由
     * AgentLlmCredentialResolver 按 modelType 关联平台级凭证实时解析。</p>
     *
     * @return true 表示本次执行了凭证核对（语义保留，兼容旧调用方）
     */
    public boolean provisionPlatformCredential(Agent agent) {
        String provider = AgentProviderResolver.resolveProvider(agent, executionProperties.getProvider());
        if (!isProviderAvailable(provider)) {
            log.warn("Provider '{}' 未生效，跳过平台凭证核对: agentId={}", provider, agent.getId());
            return false;
        }
        log.info("内部 LLM Agent 平台凭证核对通过（执行链实时解析平台级 API Key）: agentId={}, provider={}",
                agent.getId(), provider);
        return true;
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
