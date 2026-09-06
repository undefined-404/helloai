package com.helloai.common.constant;

/**
 * Browser 会话状态（N-003，C3-S1）。
 *
 * <p>登记语义（非状态机约束力）：BEGIN=会话开始 / ACTIVE=执行中 / CLOSED=正常关闭 /
 * FAILED=异常终止。状态由执行结果回写，不反向约束 task/sub_task（反锁禁令）。</p>
 */
public enum BrowserSessionStatus {
    /** 会话开始。 */
    BEGIN,
    /** 执行中（已收到活跃上报/截图）。 */
    ACTIVE,
    /** 正常关闭。 */
    CLOSED,
    /** 异常终止。 */
    FAILED
}
