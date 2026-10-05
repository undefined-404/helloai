package com.helloai.core.system.port;

/**
 * 凭证解密素材只读快照（system 域对外契约）。
 *
 * <p><b>归属判据（CODE_STYLE §7.2 情形②）</b>：消费方 agent 域高于提供方 system 域
 * ⇒ 契约落【提供方】{@code system.port}；消费方不再 import {@code system.entity.CredentialVault}。</p>
 *
 * <p>仅暴露解密所需两个字段（{@code encryptedValue} / {@code secretRef}），
 * 不携带 owner / 状态 / 审计等管理面字段（最小投影）。</p>
 */
public record CredentialSecret(
        String encryptedValue,
        String secretRef
) {
}
