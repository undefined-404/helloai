package com.helloai.api.controller;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.helloai.api.dto.PageResult;
import com.helloai.api.dto.workflow.WorkflowInstanceRequest;
import com.helloai.api.dto.workflow.WorkflowInstanceResponse;
import com.helloai.api.dto.workflow.WorkflowTemplateRequest;
import com.helloai.api.dto.workflow.WorkflowTemplateResponse;
import com.helloai.api.dto.workflow.WorkflowVersionRequest;
import com.helloai.api.dto.workflow.WorkflowVersionResponse;
import com.helloai.common.base.R;
import com.helloai.common.constant.WorkflowTemplateStatus;
import com.helloai.common.constant.WorkflowVersionStatus;
import com.helloai.core.task.workflow.entity.WorkflowInstance;
import com.helloai.core.task.workflow.entity.WorkflowTemplate;
import com.helloai.core.task.workflow.entity.WorkflowTemplateVersion;
import com.helloai.core.task.workflow.service.WorkflowInstanceService;
import com.helloai.core.task.workflow.service.WorkflowTemplateService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

/**
 * {@link WorkflowTemplateController} C1-S1 单元测试：7 端点转发与返回封装。
 *
 * <p>核心断言：参数原样透传 service；返回 R 封装 code=200 + DTO 字段映射；
 * 控制器无编排（verifyNoMoreInteractions）。</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("WorkflowTemplateController 模板/版本管理端点（C1-S1）")
class WorkflowTemplateControllerTest {

    @Mock
    private WorkflowTemplateService workflowTemplateService;

    @Mock
    private WorkflowInstanceService workflowInstanceService;

    @InjectMocks
    private WorkflowTemplateController controller;

    private static final Long TEMPLATE_ID = 1L;
    private static final Long VERSION_ID = 10L;
    private static final OffsetDateTime NOW = OffsetDateTime.parse("2026-09-05T12:00:00+08:00");

    private WorkflowTemplate template() {
        WorkflowTemplate t = new WorkflowTemplate();
        t.setId(TEMPLATE_ID);
        t.setName("dev-release");
        t.setDescription("desc");
        t.setStatus(WorkflowTemplateStatus.DRAFT);
        t.setCategory("cat");
        t.setCreateTime(NOW);
        t.setUpdateTime(NOW);
        return t;
    }

    private WorkflowTemplateVersion version() {
        WorkflowTemplateVersion v = new WorkflowTemplateVersion();
        v.setId(VERSION_ID);
        v.setTemplateId(TEMPLATE_ID);
        v.setVersionNo(1);
        v.setStatus(WorkflowVersionStatus.PUBLISHED);
        v.setCreateTime(NOW);
        return v;
    }

    @Test
    @DisplayName("create：name/desc/category 透传 service，DTO 映射正确")
    void shouldCreateTemplate() {
        WorkflowTemplateRequest req = new WorkflowTemplateRequest();
        req.setName("dev-release");
        req.setDescription("desc");
        req.setCategory("cat");
        when(workflowTemplateService.createTemplate("dev-release", "desc", "cat")).thenReturn(template());

        R<WorkflowTemplateResponse> resp = controller.create(req);

        assertThat(resp.getCode()).isEqualTo(200);
        assertThat(resp.getData().getName()).isEqualTo("dev-release");
        assertThat(resp.getData().getStatus()).isEqualTo("DRAFT");
        assertThat(resp.getData().getCurrentVersionId()).isNull();
        verifyNoMoreInteractions(workflowTemplateService);
    }

    @Test
    @DisplayName("update：id + 字段透传 service")
    void shouldUpdateTemplate() {
        WorkflowTemplateRequest req = new WorkflowTemplateRequest();
        req.setName("new-name");
        when(workflowTemplateService.updateTemplate(TEMPLATE_ID, "new-name", null, null))
                .thenReturn(template());

        R<WorkflowTemplateResponse> resp = controller.update(TEMPLATE_ID, req);

        assertThat(resp.getCode()).isEqualTo(200);
        verify(workflowTemplateService).updateTemplate(TEMPLATE_ID, "new-name", null, null);
        verifyNoMoreInteractions(workflowTemplateService);
    }

    @Test
    @DisplayName("archive：id 透传，返回 R.ok")
    void shouldArchiveTemplate() {
        when(workflowTemplateService.archiveTemplate(TEMPLATE_ID)).thenReturn(template());

        R<WorkflowTemplateResponse> resp = controller.archive(TEMPLATE_ID);

        assertThat(resp.getCode()).isEqualTo(200);
        verify(workflowTemplateService).archiveTemplate(TEMPLATE_ID);
        verifyNoMoreInteractions(workflowTemplateService);
    }

    @Test
    @DisplayName("page：分页透传，PageResult 封装")
    void shouldPageTemplates() {
        Page<WorkflowTemplate> page = new Page<>(1, 20, 1);
        page.setRecords(List.of(template()));
        when(workflowTemplateService.pageTemplates(1, 20)).thenReturn(page);

        R<PageResult<WorkflowTemplateResponse>> resp = controller.page(1, 20);

        assertThat(resp.getCode()).isEqualTo(200);
        assertThat(resp.getData().getTotal()).isEqualTo(1);
        assertThat(resp.getData().getList()).hasSize(1);
        assertThat(resp.getData().getList().get(0).getId()).isEqualTo(TEMPLATE_ID);
        verify(workflowTemplateService).pageTemplates(1, 20);
        verifyNoMoreInteractions(workflowTemplateService);
    }

    @Test
    @DisplayName("createVersion：templateId + definition 透传，DTO 映射（不含 definition 正文）")
    void shouldCreateVersion() {
        WorkflowVersionRequest req = new WorkflowVersionRequest();
        req.setDefinition(Map.of("nodes", List.of()));
        when(workflowTemplateService.createVersion(TEMPLATE_ID, req.getDefinition())).thenReturn(version());

        R<WorkflowVersionResponse> resp = controller.createVersion(TEMPLATE_ID, req);

        assertThat(resp.getCode()).isEqualTo(200);
        assertThat(resp.getData().getTemplateId()).isEqualTo(TEMPLATE_ID);
        assertThat(resp.getData().getVersionNo()).isEqualTo(1);
        assertThat(resp.getData().getStatus()).isEqualTo("PUBLISHED");
        verify(workflowTemplateService).createVersion(eq(TEMPLATE_ID), any(Map.class));
        verifyNoMoreInteractions(workflowTemplateService);
    }

    @Test
    @DisplayName("publish：versionId 透传，返回 R.ok")
    void shouldPublishVersion() {
        when(workflowTemplateService.publishVersion(VERSION_ID)).thenReturn(version());

        R<WorkflowVersionResponse> resp = controller.publish(VERSION_ID);

        assertThat(resp.getCode()).isEqualTo(200);
        assertThat(resp.getData().getId()).isEqualTo(VERSION_ID);
        verify(workflowTemplateService).publishVersion(VERSION_ID);
        verifyNoMoreInteractions(workflowTemplateService);
    }

    @Test
    @DisplayName("listVersions：templateId 透传，返回版本列表")
    void shouldListVersions() {
        when(workflowTemplateService.listVersions(TEMPLATE_ID)).thenReturn(List.of(version()));

        R<List<WorkflowVersionResponse>> resp = controller.listVersions(TEMPLATE_ID);

        assertThat(resp.getCode()).isEqualTo(200);
        assertThat(resp.getData()).hasSize(1);
        assertThat(resp.getData().get(0).getVersionNo()).isEqualTo(1);
        verify(workflowTemplateService).listVersions(TEMPLATE_ID);
        verifyNoMoreInteractions(workflowTemplateService);
    }

    @Test
    @DisplayName("createInstance：templateId + params 透传，返回实例（含 taskId）")
    void shouldCreateInstance() {
        WorkflowInstanceRequest req = new WorkflowInstanceRequest();
        req.setParams(Map.of("goal", "报表"));
        WorkflowInstance inst = new WorkflowInstance();
        inst.setId(20L);
        inst.setTemplateId(TEMPLATE_ID);
        inst.setVersionId(VERSION_ID);
        inst.setTaskId(100L);
        inst.setParams(req.getParams());
        inst.setStatusSnapshot("RUNNING");
        when(workflowInstanceService.createWorkflowInstance(TEMPLATE_ID, req.getParams())).thenReturn(inst);

        R<WorkflowInstanceResponse> resp = controller.createInstance(TEMPLATE_ID, req);

        assertThat(resp.getCode()).isEqualTo(200);
        assertThat(resp.getData().getTaskId()).isEqualTo(100L);
        assertThat(resp.getData().getTemplateId()).isEqualTo(TEMPLATE_ID);
        assertThat(resp.getData().getVersionId()).isEqualTo(VERSION_ID);
        assertThat(resp.getData().getStatusSnapshot()).isEqualTo("RUNNING");
        verify(workflowInstanceService).createWorkflowInstance(TEMPLATE_ID, req.getParams());
        verifyNoMoreInteractions(workflowInstanceService);
    }
}
