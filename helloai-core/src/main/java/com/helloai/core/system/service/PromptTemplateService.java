package com.helloai.core.system.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.helloai.core.system.entity.PromptTemplate;

import java.util.List;

/**
 * 提示词模板服务接口。
 */
public interface PromptTemplateService extends IService<PromptTemplate> {

    /**
     * 按角色获取模板列表
     */
    List<PromptTemplate> getByRole(String role);

    /**
     * 按分类获取模板列表
     */
    List<PromptTemplate> getByCategory(String category);

    /**
     * 获取角色的默认模板
     */
    PromptTemplate getDefaultByRole(String role);

    /**
     * 按 category 和 role 获取模板
     */
    PromptTemplate getByRoleAndCategory(String role, String category);

    /**
     * 创建模板
     */
    PromptTemplate create(PromptTemplate template);

    /**
     * 更新模板
     */
    PromptTemplate update(PromptTemplate template);

    /**
     * 组合最终提示词。
     * 合并: 角色模板 + Agent 特定内容 + 全局规则
     */
    String compose(String role, String agentSpecificContent);

    /**
     * 获取 Agent 的 SKILL.md（从文件系统读取 + 运行时变量替换）。
     * 文件路径: resources/skills/{role}/SKILL.md
     */
    String getSkillForAgent(String role, String apiKey, String baseUrl, String agentName, Long agentId);

    /**
     * 拼装完整接入内容（Agent 信息摘要 + 执行要求 + SKILL）。
     * 生成结果可直接复制到 Trae / Qoder 供外部 Agent 接入使用。
     */
    String buildOnboardingContent(String role, String apiKey, String baseUrl,
                                  String agentName, Long agentId);
}
