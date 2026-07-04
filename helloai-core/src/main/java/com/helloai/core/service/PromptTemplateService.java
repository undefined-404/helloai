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

    private final RuleService ruleService;

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
     * 按分类获取模板列表
     */
    public List<PromptTemplate> getByCategory(String category) {
        return lambdaQuery()
                .eq(PromptTemplate::getCategory, category)
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
     * 按 category 和 role 获取模板
     */
    public PromptTemplate getByRoleAndCategory(String role, String category) {
        return lambdaQuery()
                .eq(PromptTemplate::getRole, role)
                .eq(PromptTemplate::getCategory, category)
                .eq(PromptTemplate::getIsDefault, 1)
                .orderByDesc(PromptTemplate::getVersion)
                .last("LIMIT 1")
                .one();
    }

    /**
     * 按 slug 获取 Agent 专业化配置
     */
    public PromptTemplate getBySlug(String slug) {
        return lambdaQuery()
                .eq(PromptTemplate::getSlug, slug)
                .eq(PromptTemplate::getCategory, "AGENT_SPECIALIZATION")
                .last("LIMIT 1")
                .one();
    }

    /**
     * 创建模板
     */
    @Transactional(rollbackFor = Exception.class)
    public PromptTemplate create(PromptTemplate template) {
        if (template.getIsDefault() != null && template.getIsDefault() == 1) {
            lambdaUpdate()
                    .eq(PromptTemplate::getRole, template.getRole())
                    .eq(PromptTemplate::getCategory, template.getCategory())
                    .set(PromptTemplate::getIsDefault, 0)
                    .update();
        }
        template.setVersion(1);
        save(template);
        log.info("提示词模板创建: id={}, role={}, category={}, name={}",
                template.getId(), template.getRole(), template.getCategory(), template.getName());
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
        if (template.getIsDefault() != null && template.getIsDefault() == 1) {
            lambdaUpdate()
                    .eq(PromptTemplate::getRole, existing.getRole())
                    .eq(PromptTemplate::getCategory, existing.getCategory())
                    .set(PromptTemplate::getIsDefault, 0)
                    .update();
        }
        template.setVersion(existing.getVersion() + 1);
        updateById(template);
        log.info("提示词模板更新: id={}, version={}", template.getId(), template.getVersion());
        return template;
    }

    /**
     * 组合最终提示词。
     * 合并: 角色模板 + Agent 专业化(slug) + 全局规则
     */
    public String compose(String role, String agentSpecificContent) {
        PromptTemplate defaultTemplate = getDefaultByRole(role);
        if (defaultTemplate == null) {
            throw new BizException("未找到角色 " + role + " 的默认提示词模板");
        }
        StringBuilder sb = new StringBuilder();
        sb.append(defaultTemplate.getContent());

        if (agentSpecificContent != null && !agentSpecificContent.isBlank()) {
            sb.append("\n\n---\n\n").append(agentSpecificContent);
        }

        // 追加全局规则
        String globalRules = ruleService.getGlobalRuleContent();
        if (globalRules != null && !globalRules.isBlank()) {
            sb.append("\n\n---\n\n## 全局规则\n\n").append(globalRules);
        }

        return sb.toString();
    }

    /**
     * 按专业化 slug 组合完整 Prompt（角色模板 + 专业化配置 + 全局规则）
     */
    public String composeBySlug(String slug) {
        PromptTemplate specialization = getBySlug(slug);
        if (specialization == null) {
            throw new BizException("未找到 Agent 配置: " + slug);
        }
        return compose(specialization.getRole(), specialization.getContent());
    }

    /**
     * 获取 Agent 的 SKILL.md（运行时变量替换）
     */
    public String getSkillForAgent(String role, String apiKey, String baseUrl, String agentName) {
        PromptTemplate skill = getByRoleAndCategory(role, "SKILL");
        if (skill == null) {
            throw new BizException("未找到角色 " + role + " 的 SKILL 文档");
        }
        String content = skill.getContent();
        content = content.replace("<注册后填入>", apiKey);
        content = content.replace("{{BASE_URL}}", baseUrl);
        content = content.replace("{{AGENT_NAME}}", agentName);
        return content;
    }
}
