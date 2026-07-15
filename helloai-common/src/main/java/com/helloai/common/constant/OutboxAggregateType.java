package com.helloai.common.constant;

/**
 * Phase 2H ②a 引入：
 * 执行命令 Outbox / 未来统一 outbox 的"业务聚合类型"枚举。
 *
 * <p>本枚举出现在 {@code agent_command_outbox.aggregate_type} 列上，
 * 当前物理取值固定为 {@link #EXECUTION_COMMAND}。
 *
 * <p>设计上：
 * <ul>
 *   <li>采用独立枚举（而非沿用 {@link AgentAccessType} 等已有枚举）—— 不同业务域字段不应共享枚举；</li>
 *   <li>当前仅含 {@code EXECUTION_COMMAND}，预留给未来统一 outbox（如与
 *       {@code agent_outbox_event} 合并）时按需扩展，本轮不做。</li>
 * </ul>
 *
 * <p>值以字符串形式持久化（{@code VARCHAR(64)}），不参与 MyBatis-Plus
 * {@code IEnum<Integer>} 数值映射，因此不带 {@code IEnum}。
 */
public enum OutboxAggregateType {
    EXECUTION_COMMAND;

    /** 列上持久化使用的字面量（本枚举与列值一一对应）。 */
    public String code() {
        return name();
    }
}
