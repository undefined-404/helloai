package com.helloai.core.system.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.helloai.core.system.entity.CredentialVault;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * 凭证保险库服务。
 *
 * <p>只提供最小可复用能力，供后续 AgentExecutor / ChatClient 链路继续接入。</p>
 */
public interface CredentialVaultService extends IService<CredentialVault> {

    /**
     * 查询 Agent 的当前启用 API Key 凭证。
     */
    CredentialVault getActiveAgentApiKey(Long agentId, String provider);

    /**
     * 查询平台级（PLATFORM/ownerId=0）的当前启用 API Key 凭证。
     *
     * <p>平台级凭证按 provider 唯一（owner_type=PLATFORM、owner_id=0），
     * 由 {@code PlatformProviderConfigService} 读取，替代 yml 启动期一次性绑定。</p>
     */
    CredentialVault getActivePlatformApiKey(String provider);

    /**
     * 查询平台级全部凭证记录（不含加密值明文），供管理端脱敏展示。
     */
    List<CredentialVault> listPlatformCredentials();

    /**
     * 判断平台级是否已配置启用态凭证。
     */
    boolean hasActivePlatformCredential(String provider);

    /**
     * 查询 Agent 的全部凭证记录（不含加密值明文）。
     *
     * <p>按 §6.3 分层红线从 CredentialController 收口。</p>
     */
    List<CredentialVault> listAgentCredentials(Long agentId);

    /**
     * 判断 Agent 当前是否已绑定启用态托管凭证。
     */
    boolean hasActiveAgentCredential(Long agentId);

    /**
     * 以最小 upsert 方式保存 Agent 的 API Key 凭证。
     *
     * <p>先只支持单条启用态记录；后续多 Provider / 多版本轮换再继续扩展。</p>
     */
    CredentialVault saveAgentApiKeyCredential(Long agentId, String provider,
                                              String encryptedValue, String secretRef,
                                              OffsetDateTime expiresAt,
                                              String remark);

    /**
     * 以最小 upsert 方式保存平台级 API Key 凭证（ownerId 固定占位 0）。
     */
    CredentialVault savePlatformApiKeyCredential(String provider,
                                                 String encryptedValue, String secretRef,
                                                 String remark);

    CredentialVault saveAgentApiKeyCredential(Long agentId, String provider,
                                              String encryptedValue, String secretRef,
                                              String remark);

    /**
     * 轮换 Agent 的 API Key 凭证：旧凭证 → EXPIRED，新凭证 → ACTIVE。
     *
     * <p>AgentHub 轮换语义：</p>
     * <ul>
     *   <li>旧 ACTIVE 凭证标为 {@code EXPIRED}（非 DISABLED），
     *       区分"人为停用"和"自动轮换"</li>
     *   <li>新建 ACTIVE 凭证，在 remark 中记录 rotated_from_id 审计链</li>
     *   <li>事务内保证一致性：旧凭证过期 + 新凭证创建原子完成</li>
     * </ul>
     *
     * @param agentId        Agent ID
     * @param provider       LLM Provider
     * @param encryptedValue 新凭证加密值
     * @param secretRef      新凭证 Secret 引用
     * @param remark         审计备注
     * @return 新创建的 ACTIVE 凭证
     */
    CredentialVault rotateAgentApiKey(Long agentId, String provider,
                                      String encryptedValue, String secretRef,
                                      String remark);

    /**
     * 轮换平台级 API Key 凭证（旧凭证 → EXPIRED，新凭证 → ACTIVE），ownerId 固定占位 0。
     */
    CredentialVault rotatePlatformApiKey(String provider,
                                         String encryptedValue, String secretRef,
                                         String remark);
}
