package com.helloai.common.constant;

import com.baomidou.mybatisplus.annotation.IEnum;

/**
 * Phase 2H ②a 引入：
 * 执行命令 Outbox 表（{@code agent_command_outbox}）的投递状态机。
 *
 * <p>本枚举与现有 {@link OutboxStatus} 严格区分 ——
 * 现有 {@code OutboxStatus} 专门服务于 {@code agent_outbox_event}（SubTask 状态变更事件）；
 * 本枚举服务于 {@code agent_command_outbox}（执行命令 MQ 投递）。
 *
 * <p>状态迁移：
 * <pre>
 *   新建   → PENDING ─[成功]→ SENT
 *                └─[失败 + 仍有重试额度]→ PENDING（next_retry_at 退避后回到 PENDING 扫描）
 *                └─[retry_count ≥ maxRetry]→ FAILED（最终失败）
 * </pre>
 *
 * <p>三态刻意保持最小：CONFIRMED 留待 ②b 与 publisher-confirms 一起补；
 * FAILED 仅作为"达到 maxRetry 后的最终失败"标记，不进入 Relay 重扫；
 * 技术噪声（broker nack / 重发节奏）只动本表，不污染 {@code task_timeline}。
 */
public enum AgentCommandOutboxStatus implements IEnum<Integer> {
    PENDING(0),
    SENT(1),
    FAILED(2),
    CONFIRMED(3);

    private final int value;

    AgentCommandOutboxStatus(int value) {
        this.value = value;
    }

    @Override
    public Integer getValue() {
        return value;
    }
}
