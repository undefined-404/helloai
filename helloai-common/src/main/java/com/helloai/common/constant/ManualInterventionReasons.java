package com.helloai.common.constant;

/**
 * 子任务人工介入标记（{@code sub_task.context.manualIntervention.reason}）的原因码。
 *
 * <p><b>为什么单独承载</b>：该原因码跨越「写入方」与「判定方」两个域——
 * 写入方在 agent 域（内部执行者自报阻塞工具 {@code internal_report_blocked}），
 * 判定方在 task 域的派发层（BLOCKED 自动重派巡检需据此区分「能力不足」与「真人工场景」）。
 * 若各自写字面量，任一处拼写漂移都会让判定<b>静默失效</b>（既不报错也不告警），
 * 故收敛到叶子域 {@code helloai-common} 作为唯一事实源（CODE_STYLE §5.6 公共常量）。</p>
 *
 * <p><b>与其它原因码的区别</b>：{@code no_candidate_long_wait} / {@code rework_budget} /
 * {@code dispatch_skip_execution_dense} / {@code fallback_skip_policy*} 均表示
 * 「自动链已穷尽手段，等人工拍板」；只有 {@link #EXECUTOR_REPORTED_BLOCKED} 表示
 * 「执行者自认无能力完成」——它仍有确定的平台处置动作（换执行者），故两者在
 * 自动重派判定中被区别对待。</p>
 */
public final class ManualInterventionReasons {

    private ManualInterventionReasons() {
    }

    /**
     * 内部执行者自报阻塞（能力不足）。
     *
     * <p>由 {@code InternalLlmExecutorTools#internalReportBlocked} 在落 {@code BLOCKED}
     * 之后写入；语义 = 「所需输入 / 原始附件不可得、需要本机环境而没有、验收证据拿不到」，
     * 即「当前执行者做不到」，而非「任务本身有缺陷」。</p>
     */
    public static final String EXECUTOR_REPORTED_BLOCKED = "executor_reported_blocked";
}
