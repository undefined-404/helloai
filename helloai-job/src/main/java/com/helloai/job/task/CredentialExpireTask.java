package com.helloai.job.task;

import com.helloai.common.config.CredentialLifecycleProperties;
import com.helloai.core.system.service.CredentialVaultService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 凭证过期扫描任务（N-004 Credential Vault 收口，Phase 2 B2）。
 *
 * <p>周期扫描 {@code credential_vault} 中 ACTIVE 且 {@code expire_time} 已过的凭证，
 * 批量置为 EXPIRED + 落 EXPIRE 审计。激活 V14 建表以来 {@code expireTime} 死字段：
 * 到期凭证自动退出路由（{@code getActive*} 只查 ACTIVE），无需人工干预。</p>
 *
 * <p>保护机制（与 {@link InboxExpireCleanupTask} 同模式）：
 * <ul>
 *   <li>ShedLock 实例级互斥（@SchedulerLock，Redis 存储锁）保证同一时刻只有一台节点执行</li>
 *   <li>batch limit 防止单轮失效过多（100）</li>
 *   <li>业务异常不抛出：失败只记 error，不影响下轮扫描</li>
 *   <li>{@code expire-scan-enabled} 开关：false 时空转（逃生口）</li>
 * </ul>
 *
 * <p>清理策略口径：不物理删除，仅状态隔离 + 审计保留（fail-close）。</p>
 *
 * @see CredentialVaultService#expireOverdue
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CredentialExpireTask {

    private final CredentialVaultService credentialVaultService;
    private final CredentialLifecycleProperties lifecycleProperties;

    /** 单轮失效上限（同一轮内最多失效多少条过期凭证）。 */
    private static final int BATCH_LIMIT = 100;

    /**
     * 5 分钟一轮：凭证过期是日级卫生问题（默认无 TTL，有 TTL 的也多为月级），
     * 分钟级失效延迟无业务影响，无需更细粒度（与 InboxExpireCleanupTask 同粒度）。
     */
    @Scheduled(fixedRate = 300_000)
    @SchedulerLock(name = "credentialExpireScan", lockAtMostFor = "PT60S")
    public void scan() {
        if (!lifecycleProperties.isExpireScanEnabled()) {
            return;
        }
        try {
            int expired = credentialVaultService.expireOverdue(BATCH_LIMIT);
            if (expired > 0) {
                log.info("凭证过期扫描完成: 失效行数={}", expired);
            }
        } catch (Exception e) {
            log.error("CredentialExpireTask 执行异常", e);
        }
    }
}
