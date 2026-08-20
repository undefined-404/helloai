package com.helloai.core.system.service;

import com.helloai.core.system.entity.CredentialVault;

import java.time.OffsetDateTime;

/**
 * 凭证绑定服务接口。
 */
public interface CredentialVaultBindingService {

    CredentialVault bindAgentApiKey(Long agentId, String provider, String apiKeyPlaintext,
                                    OffsetDateTime expiresAt, String remark);

    String getAgentApiKeyPlaintext(Long agentId, String provider);

    /**
     * 轮换 Agent 的 API Key 凭证。
     *
     * <p>AgentHub 旧凭证 → EXPIRED，新凭证 → ACTIVE。</p>
     * <p>与 {@link #bindAgentApiKey} 的区别：</p>
     * <ul>
     *   <li>bindAgentApiKey：旧凭证 → DISABLED（人为停用语义）</li>
     *   <li>rotateAgentApiKey：旧凭证 → EXPIRED（自动轮换语义），
     *       在 remark 中记录 rotated_from_id 审计链</li>
     * </ul>
     *
     * @param agentId         Agent ID
     * @param provider        LLM Provider
     * @param apiKeyPlaintext 新 API Key 明文
     * @param remark          审计备注
     * @return 新创建的 ACTIVE 凭证
     */
    CredentialVault rotateAgentApiKey(Long agentId, String provider,
                                      String apiKeyPlaintext, String remark);
}
