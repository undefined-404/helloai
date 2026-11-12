package com.helloai.api.controller;

import com.helloai.common.base.R;
import com.helloai.core.planner.port.PlanDraftView;
import com.helloai.core.planner.service.PlanDraftService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 草案暂存区读接口（`AD-5a-3`）。
 *
 * <p>草案 id 空间是 {@code task_plan_draft.id}，与执行任务 {@code sub_task.id} 分离；
 * 写接口在 5a-3 后续切片补齐。暂存区无当前轮行时返回空列表，调用方回落旧读法
 * （存量 83 条在 `5a-4` 迁入前仍走 `sub_task`）。</p>
 */
@Slf4j
@RestController
@RequestMapping("/api/plan-drafts")
@RequiredArgsConstructor
public class PlanDraftController {

    private final PlanDraftService planDraftService;

    @GetMapping("/listByTaskId/{taskId}")
    public R<List<PlanDraftView>> listByTask(@PathVariable("taskId") Long taskId) {
        return R.ok(planDraftService.listByTask(taskId));
    }
}