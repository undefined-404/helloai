package com.helloai.core.system.service;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.helloai.common.base.BizException;
import com.helloai.core.system.entity.PromptTemplate;
import com.helloai.core.system.mapper.PromptTemplateMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
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
     * 获取 Agent 的 SKILL.md（从文件系统读取 + 运行时变量替换）。
     * 文件路径: resources/skills/{role}/SKILL.md
     */
    public String getSkillForAgent(String role, String apiKey, String baseUrl, String agentName, Long agentId) {
        String content;
        ClassPathResource resource = new ClassPathResource(
                "skills/" + role.toLowerCase() + "/SKILL.md");
        if (!resource.exists()) {
            throw new BizException("未找到角色 " + role + " 的 SKILL 文档（文件路径: skills/"
                    + role.toLowerCase() + "/SKILL.md）");
        }
        try (InputStream in = resource.getInputStream()) {
            content = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new BizException("读取 SKILL 文档失败: " + e.getMessage());
        }
        content = content.replace("<注册后填入>", apiKey);
        content = content.replace("{{BASE_URL}}", baseUrl);
        content = content.replace("{{AGENT_NAME}}", agentName);
        content = content.replace("<你的ID>", String.valueOf(agentId));
        content = content.replace("<你的 ID>", String.valueOf(agentId));
        return content;
    }

    /**
     * 拼装完整接入内容（Agent 信息摘要 + 执行要求 + SKILL）。
     * 生成结果可直接复制到 Trae / Qoder 供外部 Agent 接入使用。
     */
    public String buildOnboardingContent(String role, String apiKey, String baseUrl,
                                          String agentName, Long agentId) {
        String skillContent = getSkillForAgent(role, apiKey, baseUrl, agentName, agentId);

        return "你是 HelloAI 平台中的一个已注册 Agent，请按以下信息接入并开始工作。\n\n"
                + "【Agent 信息】\n"
                + "- Agent ID：" + agentId + "\n"
                + "- 名称：" + agentName + "\n"
                + "- 角色：" + role + "\n"
                + "- API Key：" + apiKey + "\n"
                + "- 服务地址：" + baseUrl + "\n\n"
                + "【执行要求】\n"
                + "1. 你已在 HelloAI 平台完成注册，无需再次注册。\n"
                + "2. 你需要按照以下 Skill 内容工作。\n"
                + "3. 文中所有 API 请求需携带 Header: Authorization: Bearer " + apiKey + "\n"
                + "4. 首次进入后先获取规则，再查收件箱和任务。\n\n"
                + "【SKILL】\n"
                + skillContent;
    }
}
