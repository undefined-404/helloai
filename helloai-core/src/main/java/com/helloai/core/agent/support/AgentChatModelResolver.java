package com.helloai.core.agent.support;

import com.helloai.common.base.BizException;
import com.helloai.common.config.AgentExecutionProperties;
import com.helloai.core.agent.AgentLlmCredentialResolver;
import com.helloai.core.agent.chat.AgentProviderResolver;
import com.helloai.core.agent.entity.Agent;
import com.helloai.core.agent.service.AgentChatClientService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.stereotype.Component;

/**
 * 「按 Agent 建底层模型」的**唯一实现**：provider 选择 + 凭据解析 + 具体 ChatModel 构建。
 *
 * <p><b>为什么抽出来</b>：这段逻辑原先只长在 {@code AgentRuntimeContextAssembler#buildChatModel}
 * （私有）。语义门（`TD-16e-3-2`）要在**确认接口**里用「产出这批草案的 planner agent」自己的模型
 * 做一次判定——那是与执行链完全不同的调用方。复制这六行就会得到两份「provider 从哪来 / 缺凭据
 * 要不要 fail-close」的口径，任一处改动都会让另一处静默偏离（判据 4：同一事实双写必分叉）。</p>
 *
 * <p><b>语义与原来逐字一致</b>：{@code mockMode} 下不解析凭据（由
 * {@link AgentChatClientService#buildChatModel} 返回 Mock 实现）；真实模式下
 * {@code requireVault} 且拿不到 key 时抛 {@link BizException}——调用方据此决定「降级」还是「放行」。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AgentChatModelResolver {

    private final AgentChatClientService agentChatClientService;
    private final AgentLlmCredentialResolver agentLlmCredentialResolver;
    private final AgentExecutionProperties executionProperties;

    /**
     * @return 可直接调用的模型（mock 模式返回 Mock 实现，永不返回 {@code null}）
     * @throws BizException 真实模式 + {@code requireVault} + 无可用凭据
     */
    public ChatModel resolve(Agent agent) {
        if (agent == null) {
            throw new BizException("无法构建模型：agent 为空");
        }
        String provider = AgentProviderResolver.resolveProvider(agent, executionProperties.getProvider());
        String apiKey = null;
        if (!executionProperties.isMockMode()) {
            apiKey = agentLlmCredentialResolver.resolveApiKey(agent);
            if (executionProperties.isRequireVault() && (apiKey == null || apiKey.isBlank())) {
                throw new BizException("未配置可用的平台级或 Agent 级 API Key: agentId=" + agent.getId());
            }
        }
        return agentChatClientService.buildChatModel(agent, provider, apiKey);
    }
}
