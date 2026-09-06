package com.helloai.common.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 凭证生命周期治理配置（N-004 Credential Vault 收口，Phase 2 B2）。
 *
 * <p>{@code CredentialExpireTask} 的单一来源：定期将 ACTIVE 且 {@code expire_time} 已过
 * 的凭证批量置为 EXPIRED（激活 V14 建表以来 {@code expireTime} 死字段），
 * 让到期凭证自动退出路由（{@code getActive*} 只查 ACTIVE）。</p>
 *
 * <p>清理策略口径（doc/design/HelloAI_Phase2_B2_CredentialVault收口执行方案.md §3.2）：
 * 过期/停用凭证不物理删除，仅状态隔离 + 审计保留（fail-close），物理清理留未来容量治理。</p>
 */
@Data
@Component
@ConfigurationProperties(prefix = "helloai.security.credential.lifecycle")
public class CredentialLifecycleProperties {

    /**
     * 凭证过期扫描任务开关（逃生口，默认开）。
     *
     * <p>关闭后 {@code CredentialExpireTask} 空转，过期凭证不再自动置 EXPIRED
     * （仅人工 revoke 可停用）——用于异常场景紧急止血。</p>
     */
    private boolean expireScanEnabled = true;
}
