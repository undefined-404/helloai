package com.helloai.core.system.notification;

/**
 * 人侧消息严重度（MSG-1，对齐 {@code agent_inbox.priority} 三档）。
 *
 * <p>只决定呈现与提醒强度，不决定事务语义（事务由时间线写入口决定，见设计 §3.6）。
 * {@code URGENT} 写库失败须 error 级留痕并计入对账，不得只 warn 而过。</p>
 */
public enum Severity {
    NORMAL,
    HIGH,
    URGENT
}