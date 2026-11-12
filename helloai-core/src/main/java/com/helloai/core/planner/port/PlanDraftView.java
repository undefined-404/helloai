package com.helloai.core.planner.port;

import com.helloai.common.constant.AcceptanceKind;
import com.helloai.common.constant.TaskPriority;
import com.helloai.core.task.port.UncertaintyDraft;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

/**
 * 草案暂存区只读视图（`AD-5a-3`，方式 A）。
 *
 * <p><b>为什么不复用 {@code SubTaskView}</b>：`AD-5a-3` 起草案 id 空间是
 * {@code task_plan_draft.id}，而执行任务是 {@code sub_task.id}；同一 DTO 承载两种 id
 * 会让前端把草案 id 传给子任务接口。本契约只描述「草案」，与执行任务彻底分开。</p>
 */
public record PlanDraftView(
        Long id,
        Long taskId,
        String code,
        Integer round,
        Integer draftSeq,
        String title,
        String content,
        String deliverable,
        String acceptance,
        AcceptanceKind acceptanceKind,
        TaskPriority priority,
        Integer contract,
        List<String> requiredSkills,
        String constraints,
        List<UncertaintyDraft> uncertainties,
        List<String> dependsOnCodes,
        Map<String, Object> context,
        OffsetDateTime updateTime
) {
}