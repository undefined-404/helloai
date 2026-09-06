package com.helloai.core.task.workflow.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.helloai.common.base.BizException;
import com.helloai.common.constant.WorkflowTemplateStatus;
import com.helloai.common.constant.WorkflowVersionStatus;
import com.helloai.core.task.workflow.entity.WorkflowTemplate;
import com.helloai.core.task.workflow.entity.WorkflowTemplateVersion;
import com.helloai.core.task.workflow.mapper.WorkflowTemplateMapper;
import com.helloai.core.task.workflow.mapper.WorkflowTemplateVersionMapper;
import com.helloai.core.task.workflow.service.WorkflowTemplateService;
import com.helloai.core.task.workflow.validator.WorkflowDefinitionValidator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;

/**
 * Workflow 模板服务实现（N-001，C1-S1）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WorkflowTemplateServiceImpl
        extends ServiceImpl<WorkflowTemplateMapper, WorkflowTemplate>
        implements WorkflowTemplateService {

    private final WorkflowTemplateVersionMapper versionMapper;

    @Transactional(rollbackFor = Exception.class)
    @Override
    public WorkflowTemplate createTemplate(String name, String description, String category) {
        validateName(name);
        WorkflowTemplate template = new WorkflowTemplate();
        template.setName(name.trim());
        template.setDescription(description);
        template.setCategory(category);
        template.setStatus(WorkflowTemplateStatus.DRAFT);
        save(template);
        log.info("Workflow 模板已创建: id={}, name={}", template.getId(), name);
        return template;
    }

    @Transactional(rollbackFor = Exception.class)
    @Override
    public WorkflowTemplate updateTemplate(Long id, String name, String description, String category) {
        WorkflowTemplate template = requireTemplate(id);
        if (template.getStatus() == WorkflowTemplateStatus.ARCHIVED) {
            throw new BizException("已归档模板不可编辑: id=" + id);
        }
        if (name != null && !name.isBlank()) {
            template.setName(name.trim());
        }
        template.setDescription(description);
        template.setCategory(category);
        updateById(template);
        return template;
    }

    @Transactional(rollbackFor = Exception.class)
    @Override
    public WorkflowTemplate archiveTemplate(Long id) {
        WorkflowTemplate template = requireTemplate(id);
        if (template.getStatus() == WorkflowTemplateStatus.ARCHIVED) {
            return template;
        }
        lambdaUpdate()
                .eq(WorkflowTemplate::getId, id)
                .set(WorkflowTemplate::getStatus, WorkflowTemplateStatus.ARCHIVED)
                .update();
        template.setStatus(WorkflowTemplateStatus.ARCHIVED);
        log.info("Workflow 模板已归档: id={}", id);
        return template;
    }

    @Override
    public IPage<WorkflowTemplate> pageTemplates(long page, long size) {
        // 显式 baseMapper + LambdaQueryWrapper：规避 lambdaQuery() chain 在隔离测试环境限制
        return baseMapper.selectPage(new Page<>(page, size),
                new LambdaQueryWrapper<WorkflowTemplate>()
                        .orderByDesc(WorkflowTemplate::getCreateTime));
    }

    @Transactional(rollbackFor = Exception.class)
    @Override
    public WorkflowTemplateVersion createVersion(Long templateId, Map<String, Object> definition) {
        WorkflowTemplate template = requireTemplate(templateId);
        if (template.getStatus() == WorkflowTemplateStatus.ARCHIVED) {
            throw new BizException("已归档模板不可新建版本: id=" + templateId);
        }
        List<String> errors = WorkflowDefinitionValidator.validate(definition);
        if (!errors.isEmpty()) {
            throw new BizException("Workflow 定义校验失败: " + String.join("; ", errors));
        }
        WorkflowTemplateVersion version = new WorkflowTemplateVersion();
        version.setTemplateId(templateId);
        version.setDefinition(definition);
        version.setStatus(WorkflowVersionStatus.DRAFT);
        version.setVersionNo(nextVersionNo(templateId));
        versionMapper.insert(version);
        log.info("Workflow 模板版本已创建: templateId={}, versionNo={}", templateId, version.getVersionNo());
        return version;
    }

    @Transactional(rollbackFor = Exception.class)
    @Override
    public WorkflowTemplateVersion publishVersion(Long versionId) {
        WorkflowTemplateVersion version = getVersion(versionId);
        if (version.getStatus() != WorkflowVersionStatus.DRAFT) {
            throw new BizException("版本已发布，不可重复发布（不可变）: id=" + versionId);
        }
        int updated = versionMapper.update(null,
                new LambdaUpdateWrapper<WorkflowTemplateVersion>()
                        .eq(WorkflowTemplateVersion::getId, versionId)
                        .eq(WorkflowTemplateVersion::getStatus, WorkflowVersionStatus.DRAFT)
                        .set(WorkflowTemplateVersion::getStatus, WorkflowVersionStatus.PUBLISHED));
        if (updated == 0) {
            throw new BizException("版本状态冲突，发布失败（CAS）: id=" + versionId);
        }
        // 回填模板 current_version_id 并置 ACTIVE（同事务）
        lambdaUpdate()
                .eq(WorkflowTemplate::getId, version.getTemplateId())
                .set(WorkflowTemplate::getCurrentVersionId, versionId)
                .set(WorkflowTemplate::getStatus, WorkflowTemplateStatus.ACTIVE)
                .update();
        version.setStatus(WorkflowVersionStatus.PUBLISHED);
        log.info("Workflow 模板版本已发布: id={}, templateId={}, versionNo={}",
                versionId, version.getTemplateId(), version.getVersionNo());
        return version;
    }

    @Override
    public List<WorkflowTemplateVersion> listVersions(Long templateId) {
        return versionMapper.selectList(new LambdaQueryWrapper<WorkflowTemplateVersion>()
                .eq(WorkflowTemplateVersion::getTemplateId, templateId)
                .orderByAsc(WorkflowTemplateVersion::getVersionNo));
    }

    @Override
    public WorkflowTemplateVersion getVersion(Long versionId) {
        WorkflowTemplateVersion version = versionMapper.selectById(versionId);
        if (version == null) {
            throw new BizException("Workflow 版本不存在: id=" + versionId);
        }
        return version;
    }

    private WorkflowTemplate requireTemplate(Long id) {
        WorkflowTemplate template = getById(id);
        if (template == null) {
            throw new BizException("Workflow 模板不存在: id=" + id);
        }
        return template;
    }

    private void validateName(String name) {
        if (name == null || name.isBlank()) {
            throw new BizException("模板名称不能为空");
        }
    }

    /** 模板内最大 version_no + 1（无版本时为 1）。 */
    private int nextVersionNo(Long templateId) {
        WorkflowTemplateVersion latest = versionMapper.selectOne(
                new LambdaQueryWrapper<WorkflowTemplateVersion>()
                        .eq(WorkflowTemplateVersion::getTemplateId, templateId)
                        .orderByDesc(WorkflowTemplateVersion::getVersionNo)
                        .last("LIMIT 1"));
        return latest == null ? 1 : latest.getVersionNo() + 1;
    }
}
