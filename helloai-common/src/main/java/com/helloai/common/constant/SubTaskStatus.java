package com.helloai.common.constant;

public enum SubTaskStatus {
    PENDING,
    ASSIGNED,
    IN_PROGRESS,
    PAUSED,
    REVIEW,
    DONE,
    REWORK,
    BLOCKED,
    CANCELLED,
    /** 死信：重分配熔断后转入人工兜底池（可人工再指派或放弃，非终态） */
    DEAD_LETTER,

    /**
     * 等待派生（`ADR-008` / `V148`）：本子任务被拆出去成为一个独立派生 Task。
     *
     * <p><b>非终态、不参与 claim/分发</b>：派生 Task 完成时由完成态传播把本状态置
     * {@code DONE}；派生失败/废除时置 {@code RESTRUCTURING} 转人工接管。草案期派生
     * （父任务尚未确认）不产生本状态——确认物化 SubTask 时按派生 Task 的当前状态
     * 直接落 {@code AWAITING_DERIVED} 或 {@code DONE}。</p>
     */
    AWAITING_DERIVED,

    /**
     * 重构中（`ADR-008` / `V148`）：执行期子任务（{@code BLOCKED} / {@code DEAD_LETTER} /
     * {@code REWORK} / {@code PAUSED}）进入人工接管，等待重新派生或退回重派。
     *
     * <p>只有本状态允许发起执行期派生（构造派生 Task Y）；不允许从 {@code PENDING} /
     * {@code IN_PROGRESS} 直接进入（那会绕过「先判失败/卡住」的触发前提）。</p>
     */
    RESTRUCTURING
}
