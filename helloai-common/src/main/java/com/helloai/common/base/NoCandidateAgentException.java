package com.helloai.common.base;

/**
 * 「暂无可用候选 Agent」异常（2026-10-05 修 P1-1）。
 *
 * <p>与普通 {@link BizException} 的区别：本异常表示「本次未选出执行者、<b>未消耗</b>重派预算」，
 * 属<b>可自愈的等待态</b>（候选 Agent 忙/离线缓解后由周期巡检自动重试），而非业务失败。
 * 供调用方（{@code SubTaskPendingOrphanTask}）与「并发状态冲突」分流，
 * 避免被静默归入 skipStatusChanged。</p>
 */
public class NoCandidateAgentException extends BizException {

    public NoCandidateAgentException(String message) {
        super(409, message);
    }
}