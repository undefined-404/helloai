package com.helloai.core.agent.chat;

import com.helloai.common.base.BizException;
import com.helloai.common.config.AgentExecutionProperties;
import com.helloai.common.config.AgentProviderProperties;
import com.helloai.core.agent.chat.provider.ProviderChatClientFactory;
import com.helloai.core.agent.entity.Agent;
import com.helloai.core.system.service.CredentialVaultBindingService;
import com.helloai.core.system.service.CredentialVaultService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * LLM Provider 目录服务。
 *
 * <p>基于 {@code helloai.providers.<name>.*} 配置枚举"已生效"的 provider：
 * 已配置平台级 api-key 且存在对应 {@link ProviderChatClientFactory} 实现。
 * 供前端手动注册平台内 API_KEY_LLM Agent 时选择，并在注册后把平台级密钥
 * 绑定进 credential_vault（满足 AgentSelector 的凭证可用性检查）。</p>
 *
 * <p>后续集成 minimax / kimi / 通义千问等：新增一段 yml 配置 + 一个
 * ProviderChatClientFactory 实现类，即自动出现在目录中，无需改本类。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LlmProviderCatalogService {

    private final AgentProviderProperties providerProperties;
    private final AgentExecutionProperties executionProperties;
    private final ObjectProvider<List<ProviderChatClientFactory>> providerChatClientFactoriesProvider;
    private final CredentialVaultService credentialVaultService;
    private final CredentialVaultBindingService credentialVaultBindingService;

    /**
     * Provider 目录项。
     *
     * @param provider        provider 名称（小写）
     * @param defaultModel    默认模型
     * @param apiKeyConfigured 是否已配置平台级 api-key
     * @param factorySupported 是否存在对应 ProviderChatClientFactory 实现
     * @param available       是否可用于手动注册（apiKeyConfigured && factorySupported）
     */
    public record ProviderCatalogItem(String provider, String defaultModel,
                                      boolean apiKeyConfigured, boolean factorySupported,
                                      boolean available) {
    }

    /**
     * 枚举全部已配置的 provider 及其可用性。
     */
    public List<ProviderCatalogItem> listProviders() {
        List<ProviderChatClientFactory> factories =
                providerChatClientFactoriesProvider.getIfAvailable(List::of);
        List<ProviderCatalogItem> items = new ArrayList<>();
        for (Map.Entry<String, AgentProviderProperties.ProviderConfig> entry
                : providerProperties.getProviders().entrySet()) {
            String name = entry.getKey().toLowerCase(Locale.ROOT);
            AgentProviderProperties.ProviderConfig config = entry.getValue();
            boolean apiKeyConfigured = config.hasApiKey();
            boolean factorySupported = factories.stream().anyMatch(f -> f.supports(name));
            items.add(new ProviderCatalogItem(name, config.getDefaultModel(),
                    apiKeyConfigured, factorySupported, apiKeyConfigured && factorySupported));
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
        String apiKey = providerProperties.getConfig(provider).getApiKey();
        credentialVaultBindingService.bindAgentApiKey(agentId, provider, apiKey, null,
                "平台配置自动绑定（helloai.providers." + provider + ".api-key）");
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
}
