package com.helloai.api.controller;

import com.helloai.api.dto.workflow.WorkflowInstanceStatusResponse;
import com.helloai.common.base.R;
import com.helloai.core.task.workflow.domain.WorkflowInstanceStatusView;
import com.helloai.core.task.workflow.service.WorkflowInstanceService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Workflow 实例管理端点（N-001，C1-S3；/api/admin/* 由 AuthInterceptor 统一鉴权）。
 *
 * <p>实例为一次性物化产物（D1），本控制器只做**聚合状态查询**（纯查询投影，
 * 从 sub_task 现算，不落权威列）——不提供任何反向操作 task/sub_task 的端点
 * （D6-3 反锁禁令）。纯转发 + DTO 装配，无编排（§6.3 红线）。</p>
 */
@RestController
@RequestMapping("/api/admin/workflow-instances")
@RequiredArgsConstructor
public class WorkflowInstanceController {

    private final WorkflowInstanceService workflowInstanceService;

    @GetMapping("/{id}/status")
    public R<WorkflowInstanceStatusResponse> status(@PathVariable("id") Long id) {
        WorkflowInstanceStatusView view = workflowInstanceService.aggregateStatus(id);
        return R.ok(toResponse(view));
    }

    private WorkflowInstanceStatusResponse toResponse(WorkflowInstanceStatusView view) {
        WorkflowInstanceStatusResponse resp = new WorkflowInstanceStatusResponse();
        resp.setInstanceId(view.getInstanceId());
        resp.setTaskId(view.getTaskId());
        resp.setStatus(view.getStatus() != null ? view.getStatus().name() : null);
        resp.setDoneCount(view.getDoneCount());
        resp.setTotalCount(view.getTotalCount());
        resp.setReason(view.getReason());
        resp.setNodeStatuses(view.getNodeStatuses());
        return resp;
    }
}
