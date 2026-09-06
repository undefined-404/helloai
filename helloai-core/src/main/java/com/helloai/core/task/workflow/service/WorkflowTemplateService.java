package com.helloai.core.task.workflow.service;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.service.IService;
import com.helloai.core.task.workflow.entity.WorkflowTemplate;
import com.helloai.core.task.workflow.entity.WorkflowTemplateVersion;

import java.util.List;
import java.util.Map;

/**
 * Workflow 模板服务（N-001，C1-S1：模板 + 版本 CRUD）。
 *
 * <p>模板为可复用蓝图元信息，版本为不可变节点图快照（D4）。
 * 实例化（C1-S2）不在本接口——S1 只管理模板/版本定义态。</p>
 */
public interface WorkflowTemplateService extends IService<WorkflowTemplate> {

    /**
     * 创建模板（status=DRAFT，无版本）。
     */
    WorkflowTemplate createTemplate(String name, String description, String category);

    /**
     * 更新模板元信息（DRAFT / ACTIVE 可改；ARCHIVED 禁止）。
     */
    WorkflowTemplate updateTemplate(Long id, String name, String description, String category);

    /**
     * 归档模板（→ ARCHIVED，不再用于新实例化；current_version_id 保留供查询）。
     */
    WorkflowTemplate archiveTemplate(Long id);

    /**
     * 分页查询模板。
     */
    IPage<WorkflowTemplate> pageTemplates(long page, long size);

    /**
     * 创建版本（definition 校验通过后 version_no = 模板内 max+1，status=DRAFT）。
     */
    WorkflowTemplateVersion createVersion(Long templateId, Map<String, Object> definition);

    /**
     * 发布版本（DRAFT → PUBLISHED 不可变；同事务回填模板 current_version_id 并置 ACTIVE）。
     */
    WorkflowTemplateVersion publishVersion(Long versionId);

    /**
     * 模板全部版本（按 version_no 升序）。
     */
    List<WorkflowTemplateVersion> listVersions(Long templateId);

    /**
     * 取版本（不存在抛 BizException）。
     */
    WorkflowTemplateVersion getVersion(Long versionId);
}
