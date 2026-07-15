package com.helloai.common.constant;

import com.baomidou.mybatisplus.annotation.IEnum;

/**
 * Phase 2H ②a 引入 / Phase 2H ②b 扩展：
 * 执行命令 Outbox 表（{@code agent_command_outbox}）的投递状态机。
 *
 * <p>本枚举与现有 {@link OutboxStatus} 严格区分 ——
 * 现有 {@code OutboxStatus} 专门服务于 {@code agent_outbox_event}（SubTask 状态变更事件）；
 * 本枚举服务于 {@code agent_command_outbox}（执行命令 MQ 投递）。
 *
 * <p>状态迁移（②a + ②b 合并口径）：
 * <pre>
 *   新建   → PENDING
 *                ├─[发送调用成功]→ SENT
 *                │        ├─[broker ACK 且无 return]→ CONFIRMED
 *                │        ├─[NACK / return / confirm-timeout]→ PENDING（next_retry_at 退避后回流）
 *                │        └─[retry_count ≥ maxRetry]→ FAILED
 *                ├─[发送调用失败 / retry_count &lt; maxRetry]→ PENDING（next_retry_at 退避后回流）
 *                └─[发送调用失败 / retry_count ≥ maxRetry]→ FAILED（最终失败）
 * </pre>
 *
 * <p>②a 落地 PENDING / SENT / FAILED 三态；
 * ②b 新增 {@link #CONFIRMED} 并配合 {@code CorrelationData} + publisher confirms 完成 broker ACK 回写。
 * 技术噪声（broker nack / return / 重发节奏）只动本表，不污染 {@code task_timeline}。</p>
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
