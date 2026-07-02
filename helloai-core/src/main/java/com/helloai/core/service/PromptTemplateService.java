package com.helloai.core.service;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.helloai.common.base.BizException;
import com.helloai.core.entity.PromptTemplate;
import com.helloai.core.mapper.PromptTemplateMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class PromptTemplateService extends ServiceImpl<PromptTemplateMapper, PromptTemplate> {

    /**
     * 按角色获取模板列表
     */
    public List<PromptTemplate> getByRole(String role) {
        return lambdaQuery()
                .eq(PromptTemplate::getRole, role)
                .orderByDesc(PromptTemplate::getIsDefault)
                .orderByDesc(PromptTemplate::getVersion)
                .list();
    }

    /**
     * 获取角色的默认模板
     */
    public PromptTemplate getDefaultByRole(String role) {
        return lambdaQuery()
                .eq(PromptTemplate::getRole, role)
                .eq(PromptTemplate::getIsDefault, 1)
                .orderByDesc(PromptTemplate::getVersion)
                .last("LIMIT 1")
                .one();
    }

    /**
     * 创建模板
     */
    @Transactional(rollbackFor = Exception.class)
    public PromptTemplate create(PromptTemplate template) {
        // 如果设为默认，先取消其他默认
        if (template.getIsDefault() != null && template.getIsDefault() == 1) {
            lambdaUpdate()
                    .eq(PromptTemplate::getRole, template.getRole())
                    .set(PromptTemplate::getIsDefault, 0)
                    .update();
        }
        template.setVersion(1);
        save(template);
        log.info("提示词模板创建: id={}, role={}, name={}", template.getId(), template.getRole(), template.getName());
        return template;
    }

    /**
     * 更新模板
     */
    @Transactional(rollbackFor = Exception.class)
    public PromptTemplate update(PromptTemplate template) {
        PromptTemplate existing = getById(template.getId());
        if (existing == null) {
            throw new BizException("模板不存在: " + template.getId());
        }

        // 如果设为默认，先取消其他默认
        if (template.getIsDefault() != null && template.getIsDefault() == 1) {
            lambdaUpdate()
                    .eq(PromptTemplate::getRole, existing.getRole())
                    .set(PromptTemplate::getIsDefault, 0)
                    .update();
        }

        template.setVersion(existing.getVersion() + 1);
        updateById(template);
        log.info("提示词模板更新: id={}, version={}", template.getId(), template.getVersion());
        return template;
    }

    /**
     * 组合最终提示词（模板 + Agent 特定内容）
     */
    public String compose(String role, String agentSpecificContent) {
        PromptTemplate defaultTemplate = getDefaultByRole(role);
        if (defaultTemplate == null) {
            throw new BizException("未找到角色 " + role + " 的默认提示词模板");
        }
        String base = defaultTemplate.getContent();
        if (agentSpecificContent != null && !agentSpecificContent.isBlank()) {
            base += "\n\n--- Agent 特定指令 ---\n" + agentSpecificContent;
        }
        return base;
    }
}
