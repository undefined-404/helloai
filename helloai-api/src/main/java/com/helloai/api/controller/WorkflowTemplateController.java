package com.helloai.api.controller;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.helloai.api.dto.PageResult;
import com.helloai.api.dto.workflow.WorkflowInstanceRequest;
import com.helloai.api.dto.workflow.WorkflowInstanceResponse;
import com.helloai.api.dto.workflow.WorkflowTemplateRequest;
import com.helloai.api.dto.workflow.WorkflowTemplateResponse;
import com.helloai.api.dto.workflow.WorkflowVersionRequest;
import com.helloai.api.dto.workflow.WorkflowVersionResponse;
import com.helloai.common.base.R;
import com.helloai.core.task.workflow.entity.WorkflowInstance;
import com.helloai.core.task.workflow.entity.WorkflowTemplate;
import com.helloai.core.task.workflow.entity.WorkflowTemplateVersion;
import com.helloai.core.task.workflow.service.WorkflowInstanceService;
import com.helloai.core.task.workflow.service.WorkflowTemplateService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * Workflow 模板管理端点（N-001，C1-S1/S2；/api/admin/* 由 AuthInterceptor 统一鉴权）。
 *
 * <p>模板/版本定义态管理（C1-S1）+ 实例化（C1-S2：模板 + 参数 → 一次性物化 task/sub_task）。
 * 纯参数接收 + DTO 装配 + R 封装，无编排（§6.3 红线）。</p>
 */
@RestController
@RequestMapping("/api/admin/workflow-templates")
@RequiredArgsConstructor
public class WorkflowTemplateController {

    private final WorkflowTemplateService workflowTemplateService;
    private final WorkflowInstanceService workflowInstanceService;

    @PostMapping
    public R<WorkflowTemplateResponse> create(@RequestBody WorkflowTemplateRequest req) {
        WorkflowTemplate t = workflowTemplateService.createTemplate(
                req.getName(), req.getDescription(), req.getCategory());
        return R.ok(toTemplate(t));
    }

    @PutMapping("/{id}")
    public R<WorkflowTemplateResponse> update(@PathVariable("id") Long id,
                                              @RequestBody WorkflowTemplateRequest req) {
        WorkflowTemplate t = workflowTemplateService.updateTemplate(
                id, req.getName(), req.getDescription(), req.getCategory());
        return R.ok(toTemplate(t));
    }

    @PostMapping("/{id}/archive")
    public R<WorkflowTemplateResponse> archive(@PathVariable("id") Long id) {
        return R.ok(toTemplate(workflowTemplateService.archiveTemplate(id)));
    }

    @GetMapping
    public R<PageResult<WorkflowTemplateResponse>> page(
            @RequestParam(value = "page", defaultValue = "1") long page,
            @RequestParam(value = "size", defaultValue = "20") long size) {
        IPage<WorkflowTemplate> result = workflowTemplateService.pageTemplates(page, size);
        return R.ok(PageResult.of(result, this::toTemplate));
    }

    @PostMapping("/{templateId}/versions")
    public R<WorkflowVersionResponse> createVersion(@PathVariable("templateId") Long templateId,
                                                    @RequestBody WorkflowVersionRequest req) {
        WorkflowTemplateVersion v = workflowTemplateService.createVersion(templateId, req.getDefinition());
        return R.ok(toVersion(v));
    }

    @PostMapping("/versions/{versionId}/publish")
    public R<WorkflowVersionResponse> publish(@PathVariable("versionId") Long versionId) {
        return R.ok(toVersion(workflowTemplateService.publishVersion(versionId)));
    }

    @GetMapping("/{templateId}/versions")
    public R<List<WorkflowVersionResponse>> listVersions(@PathVariable("templateId") Long templateId) {
        List<WorkflowVersionResponse> list = workflowTemplateService.listVersions(templateId)
                .stream()
                .map(this::toVersion)
                .toList();
        return R.ok(list);
    }

    @PostMapping("/{templateId}/instances")
    public R<WorkflowInstanceResponse> createInstance(@PathVariable("templateId") Long templateId,
                                                      @RequestBody WorkflowInstanceRequest req) {
        WorkflowInstance inst = workflowInstanceService.createWorkflowInstance(templateId, req.getParams());
        return R.ok(toInstance(inst));
    }

    private WorkflowTemplateResponse toTemplate(WorkflowTemplate t) {
        WorkflowTemplateResponse resp = new WorkflowTemplateResponse();
        resp.setId(t.getId());
        resp.setName(t.getName());
        resp.setDescription(t.getDescription());
        resp.setStatus(t.getStatus() != null ? t.getStatus().name() : null);
        resp.setCurrentVersionId(t.getCurrentVersionId());
        resp.setCategory(t.getCategory());
        resp.setCreateTime(t.getCreateTime());
        resp.setUpdateTime(t.getUpdateTime());
        return resp;
    }

    private WorkflowVersionResponse toVersion(WorkflowTemplateVersion v) {
        WorkflowVersionResponse resp = new WorkflowVersionResponse();
        resp.setId(v.getId());
        resp.setTemplateId(v.getTemplateId());
        resp.setVersionNo(v.getVersionNo());
        resp.setStatus(v.getStatus() != null ? v.getStatus().name() : null);
        resp.setCreateTime(v.getCreateTime());
        return resp;
    }

    private WorkflowInstanceResponse toInstance(WorkflowInstance inst) {
        WorkflowInstanceResponse resp = new WorkflowInstanceResponse();
        resp.setId(inst.getId());
        resp.setTemplateId(inst.getTemplateId());
        resp.setVersionId(inst.getVersionId());
        resp.setTaskId(inst.getTaskId());
        resp.setParams(inst.getParams());
        resp.setStatusSnapshot(inst.getStatusSnapshot());
        resp.setStartTime(inst.getStartTime());
        return resp;
    }
}
