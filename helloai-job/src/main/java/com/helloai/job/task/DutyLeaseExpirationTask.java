package com.helloai.job.task;

import com.helloai.core.agent.service.AgentDutyLeaseService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 值班租约到期巡检任务（AgentHub P0-C）。
 *
 * <p>周期扫描 {@code agent_duty_lease} 表中 status=ACTIVE 且 expires_at 已过期的行，
 * 将它们批量翻为 EXPIRED（close_reason=lease_expired）。防止 Agent 崩溃 / 意外掉线时
 * "值班态"永远停留在 ACTIVE 状态，进而影响 {@link com.helloai.core.agent.executor.AgentSelector}
 * 的软优先级判断。</p>
 *
 * <p>保护机制：
 * <ul>
 *   <li>ShedLock 实例级互斥（@SchedulerLock，Redis 存储锁记录）保证同一时刻只有一台节点执行扫描</li>
 *   <li>batch limit 防止单轮扫描过多阻塞（默认 200）</li>
 *   <li>业务异常不抛出：单条失败只记 warn，不影响同轮其它记录</li>
 * </ul>
 *
 * <p>与 {@link AssignedSubTaskTimeoutTask} 相互独立：本任务只维护"值班态事实源"的一致性，
 * 不重分配子任务；离岗补偿由既有超时巡检自然完成。</p>
 *
 * @see AgentDutyLeaseService#expireLeases
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DutyLeaseExpirationTask {

    private final AgentDutyLeaseService agentDutyLeaseService;

    /** 单轮扫描上限（同一轮内最多翻多少条 EXPIRED） */
    private static final int BATCH_LIMIT = 200;

    /**
     * 30 秒一轮扫描：粒度与 heartbeat 周期匹配，
     * 保证 Agent 主动 renew 之外的到期检测延迟不超过 ~30s。
     */
    @Scheduled(fixedRate = 30_000)
    @SchedulerLock(name = "dutyLeaseExpiration", lockAtMostFor = "PT60S")
    public void scan() {
        try {
            int expired = agentDutyLeaseService.expireLeases(BATCH_LIMIT);
            if (expired > 0) {
                log.info("值班租约到期巡检完成: 翻为 EXPIRED 的行数={}", expired);
            }
        } catch (Exception e) {
            log.error("DutyLeaseExpirationTask 执行异常", e);
        }
    }
}
