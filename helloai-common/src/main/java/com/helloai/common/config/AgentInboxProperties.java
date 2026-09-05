package com.helloai.common.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Agent 收件箱消息生命周期配置（N-008 统一消息生命周期，Phase 2 A3）。
 *
 * <p>本类是收件箱消息 TTL 的单一来源：{@code AgentInboxServiceImpl#send} 落库时写入
 * {@code expire_time = now + expireHours}，{@code InboxExpireCleanupTask} 定期将过期
 * 未读消息软删（{@code is_archived = 1}），消除 V1 建表以来 {@code expire_time}
 * 零读零写、unread 消息无限堆积的死置问题。</p>
 *
 * <p>统一口径（doc/design/HelloAI_Phase2_A3_消息生命周期执行方案.md §3）：
 * 通知类载体（inbox）无 Claim/Retry/Reassign——「超时未消费 → 重新分派」由子任务层
 * 承担（{@code AssignedSubTaskTimeoutTask}）；inbox 自身的过期只做归档软删，
 * 保留审计查询能力（fail-close：不丢数据）。</p>
 */
@Data
@Component
@ConfigurationProperties(prefix = "helloai.agent.inbox")
public class AgentInboxProperties {

    /**
     * 收件箱消息 TTL（小时）。
     *
     * <p>默认 168（7 天）：远大于 ASSIGNED 超时（10min）与 review 通知的合理消费窗口，
     * 只兜「Agent 长期不拉单」的堆积场景；存量消息（expire_time 为 NULL）不受影响。</p>
     *
     * <p>取值说明：</p>
     * <ul>
     *     <li>推荐值：168（7 天）</li>
     *     <li>设置为 0 或负数：关闭过期机制（send 不写 expire_time，清理任务空转——逃生口）</li>
     * </ul>
     */
    private int expireHours = 168;

    /**
     * 过期归档清理任务开关（逃生口，默认开）。
     */
    private boolean cleanupEnabled = true;
}
