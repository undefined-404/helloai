package com.helloai.core.agent;

import com.helloai.common.config.AgentExecutionProperties;
import com.helloai.common.constant.AgentAccessType;
import com.helloai.core.agent.chat.AgentProviderResolver;
import com.helloai.core.agent.entity.Agent;
import com.helloai.core.agent.service.PlatformProviderConfigService;
import com.helloai.core.system.service.CredentialVaultBindingService;
import com.helloai.core.system.service.CredentialVaultService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * API_KEY_LLM Agent 执行凭证解析器（平台级优先，Agent 级兜底）。
 *
 * <p>设计约束（修复"更换平台 API Key 后内部 LLM Agent 仍用旧 Key"缺陷）：</p>
 * <ul>
 *   <li>内部 LLM Agent 不再持有 AGENT 级 API Key 快照：注册链路不再向
 *       credential_vault 复制平台密钥（见 LlmProviderCatalogService），
 *       执行密钥一律先查平台级（模型配置）凭证——系统管理中轮换 API Key 后
 *       已注册 Agent 实时生效，无需删除重建。</li>
 *   <li>Agent 级凭证仅作兜底：兼容历史存量快照与管理端手工绑定的自定义密钥
 *       （{@code CredentialController.bindApiKeyByAgentId} 链路），
 *       仅当平台级未配置时生效。</li>
 * </ul>
 *
 * <p>执行链 / 探活链 / 选人链共用本解析器，保证三处语义一致。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AgentLlmCredentialResolver {

    private final AgentExecutionProperties executionProperties;
    private final PlatformProviderConfigService platformProviderConfigService;
    private final CredentialVaultBindingService credentialVaultBindingService;
    private final CredentialVaultService credentialVaultService;

    /**
     * 解析 API_KEY_LLM Agent 执行用的 API Key 明文。
     *
     * <p>优先级：平台级（{@code llm_provider} 模型配置 / credential_vault PLATFORM 级，
     * 实时生效）&gt; Agent 级（历史快照或手工绑定）&gt; null。</p>
     *
     * @param agent 内部 LLM Agent（accessType=API_KEY_LLM）
     * @return 明文密钥；均未配置时返回 null
     */
    public String resolveApiKey(Agent agent) {
        if (agent == null) {
            return null;
        }
        String provider = AgentProviderResolver.resolveProvider(agent, executionProperties.getProvider());
        String platformKey = platformProviderConfigService.getApiKey(provider);
        if (platformKey != null && !platformKey.isBlank()) {
            return platformKey;
        }
        String agentKey = credentialVaultBindingService.getAgentApiKeyPlaintext(agent.getId(), provider);
        if (agentKey != null && !agentKey.isBlank()) {
            log.debug("平台级未配置 {} 密钥，回退 Agent 级凭证: agentId={}", provider, agent.getId());
            return agentKey;
        }
        return null;
    }

    /**
     * API_KEY_LLM 候选的凭证可用性判定（选人链用）。
     *
     * <p>平台级已配置 或 Agent 级存在启用态凭证即视为可用（执行链解析顺序一致）；
     * 其它 accessType 不依赖凭证，直接放行。防御式：查询异常降级为不可用
     * （选中无凭证 Agent 必败，排除更安全）。</p>
     */
    public boolean hasUsableCredential(Agent agent) {
        if (agent == null || agent.getAccessType() != AgentAccessType.API_KEY_LLM) {
            return true;
        }
        try {
            String provider = AgentProviderResolver.resolveProvider(agent, executionProperties.getProvider());
            boolean usable = platformProviderConfigService.isApiKeyConfigured(provider)
                    || credentialVaultService.hasActiveAgentCredential(agent.getId());
            if (!usable) {
                log.debug("Agent {} 无可用凭证（平台级与 Agent 级均未配置），跳过选人", agent.getId());
            }
            return usable;
        } catch (Exception e) {
            log.debug("hasUsableCredential fallback to false for agent {}: {}",
                    agent.getId(), e.getMessage());
            return false;
        }
    }
}