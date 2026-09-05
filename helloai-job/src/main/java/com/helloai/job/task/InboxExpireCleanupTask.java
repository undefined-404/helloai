package com.helloai.job.task;

import com.helloai.common.config.AgentInboxProperties;
import com.helloai.core.agent.service.AgentInboxService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 收件箱过期归档任务（N-008 统一消息生命周期，Phase 2 A3）。
 *
 * <p>周期扫描 {@code agent_inbox} 表中 {@code expire_time} 已过且未归档的消息，
 * 批量软删（{@code is_archived = 1}）。消除 V1 建表以来 {@code expire_time}
 * 零读零写、unread 消息无限堆积、永被 pullTasks 重投的死置问题。</p>
 *
 * <p>统一口径（doc/design/HelloAI_Phase2_A3_消息生命周期执行方案.md §3）：
 * 通知类载体无 Claim/Retry/Reassign——「超时未消费 → 重新分派」由子任务层承担
 * （{@link AssignedSubTaskTimeoutTask}，ASSIGNED 10min 未认领即重派）；本任务只做
 * inbox 自身的过期卫生。归档即软死信：不删数据，保留审计查询能力（fail-close）。</p>
 *
 * <p>保护机制：
 * <ul>
 *   <li>ShedLock 实例级互斥（@SchedulerLock，Redis 存储锁记录）保证同一时刻只有一台节点执行</li>
 *   <li>batch limit 防止单轮归档过多（200，与 {@code DutyLeaseExpirationTask} 同粒度）</li>
 *   <li>业务异常不抛出：失败只记 error，不影响下轮扫描</li>
 * </ul>
 *
 * <p>查询侧（{@code getUnread} / {@code countUnread}）已同步过滤过期消息，
 * 本任务的归档窗口期（最长 5min）内不会投递过期内容。</p>
 *
 * @see AgentInboxService#archiveExpired
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class InboxExpireCleanupTask {

    private final AgentInboxService agentInboxService;
    private final AgentInboxProperties inboxProperties;

    /** 单轮归档上限（同一轮内最多归档多少条） */
    private static final int BATCH_LIMIT = 200;

    /**
     * 5 分钟一轮：inbox 过期是堆积级卫生问题（默认 TTL 7 天），
     * 分钟级归档延迟无业务影响，无需更细粒度。
     */
    @Scheduled(fixedRate = 300_000)
    @SchedulerLock(name = "inboxExpireCleanup", lockAtMostFor = "PT60S")
    public void scan() {
        if (!inboxProperties.isCleanupEnabled()) {
            return;
        }
        try {
            int archived = agentInboxService.archiveExpired(BATCH_LIMIT);
            if (archived > 0) {
                log.info("收件箱过期归档完成: 归档行数={}", archived);
            }
        } catch (Exception e) {
            log.error("InboxExpireCleanupTask 执行异常", e);
        }
    }
}
