package com.helloai.core.task.projection;

import lombok.Data;

/**
 * 个人工作台任务概况行（PA-4 改版，2026-11-02）。
 *
 * <p>按当前用户任务归属（{@code task.owner_id = ? OR owner_id IS NULL}）一次性聚合：
 * 执行中任务数 + 子任务「待分配 / 待审查 / 待人工」三态计数。
 * 「待人工」= 阻塞（BLOCKED）+ 人工死信（DEAD_LETTER），二者对用户是同一个动作面。</p>
 */
@Data
public class OwnedTaskOverviewRow {

    /** 执行中任务数（task.status = IN_PROGRESS）。 */
    private Long inProgressTasks;

    /** 待分配子任务数（status = PENDING）。 */
    private Long pendingDispatch;

    /** 待审查子任务数（status = REVIEW）。 */
    private Long pendingReview;

    /** 待人工子任务数（status IN (BLOCKED, DEAD_LETTER)）。 */
    private Long pendingManual;
}
