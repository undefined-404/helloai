package com.helloai.core.system.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.helloai.common.base.BizException;
import com.helloai.core.system.entity.PromptTemplate;
import com.helloai.core.system.mapper.PromptTemplateMapper;
import com.helloai.core.system.service.PromptTemplateService;
import com.helloai.core.system.service.RuleService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

@Slf4j
@Service
@RequiredArgsConstructor
public class PromptTemplateServiceImpl extends ServiceImpl<PromptTemplateMapper, PromptTemplate> implements PromptTemplateService {

    private final RuleService ruleService;

    /**
     * 按角色获取模板列表
     */
    @Override
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
    @Override
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
    @Override
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
    @Override
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
     * 创建模板
     */
    @Transactional(rollbackFor = Exception.class)
    @Override
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
    @Override
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
     * 合并: 角色模板 + Agent 特定内容 + 全局规则
     */
    @Override
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
     * 获取 Agent 的 SKILL.md（从文件系统读取 + 运行时变量替换）。
     * 文件路径: resources/skills/{role}/SKILL.md
     */
    @Override
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
     * 构建技能包 ZIP：SKILL.md（占位符已渲染）+ scripts/ 全量脚本 + config.example.json（baseUrl 预填，apiKey 保留占位）。
     * zip 内以 <role>-skill/ 为顶层目录，整体复制到 IDE 的 skills 目录即可，防止只拿单个 md 导致脚本缺失。
     */
    @Override
    public byte[] buildSkillPackageZip(String role, String apiKey, String baseUrl, String agentName, Long agentId) {
        String roleDir = role.toLowerCase();
        String skillContent = getSkillForAgent(role, apiKey, baseUrl, agentName, agentId);
        try {
            // classpath 下枚举 skills/<role>/ 内全部文件（SKILL.md + scripts/*），jar 内同样可用
            PathMatchingResourcePatternResolver resolver = new PathMatchingResourcePatternResolver();
            Resource[] resources = resolver.getResources("classpath:skills/" + roleDir + "/**/*");
            List<Resource> files = Arrays.stream(resources)
                    .filter(Resource::isReadable)
                    .sorted(Comparator.comparing(r -> {
                        try {
                            return r.getURL().toString();
                        } catch (IOException e) {
                            return "";
                        }
                    }))
                    .toList();

            String topDir = roleDir + "-skill";
            String prefix = "/skills/" + roleDir + "/";
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            try (ZipOutputStream zos = new ZipOutputStream(bos)) {
                // SKILL.md（渲染后）置于顶层目录根部
                byte[] skillBytes = skillContent.getBytes(StandardCharsets.UTF_8);
                zos.putNextEntry(new ZipEntry(topDir + "/SKILL.md"));
                zos.write(skillBytes);
                zos.closeEntry();

                for (Resource r : files) {
                    String url = r.getURL().toString();
                    int idx = url.indexOf(prefix);
                    if (idx < 0) {
                        continue;
                    }
                    String rel = url.substring(idx + prefix.length());
                    // SKILL.md 已在上方以渲染后的完整版写入，跳过原始文件避免 zip 重复条目
                    if (rel.equals("SKILL.md")) {
                        continue;
                    }
                    byte[] content;
                    try (InputStream in = r.getInputStream()) {
                        content = in.readAllBytes();
                    }
                    // config.example.json：预填 baseUrl（外网地址），apiKey 保留模板占位由下载者填写
                    if (rel.equals("scripts/config.example.json")) {
                        String text = new String(content, StandardCharsets.UTF_8)
                                .replaceAll("\"baseUrl\"\\s*:\\s*\"[^\"]*\"", "\"baseUrl\": \"" + baseUrl + "\"");
                        content = text.getBytes(StandardCharsets.UTF_8);
                    }
                    zos.putNextEntry(new ZipEntry(topDir + "/" + rel));
                    zos.write(content);
                    zos.closeEntry();
                }
            }
            return bos.toByteArray();
        } catch (IOException e) {
            throw new BizException("构建技能包失败: " + e.getMessage());
        }
    }

    /**
     * 拼装完整接入内容（Agent 信息摘要 + 执行要求 + SKILL）。
     * 生成结果可直接复制到 Trae / Qoder 供外部 Agent 接入使用。
     */
    @Override
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
                + "【执行模式】\n"
                + "请在当前对话中被动响应执行任务，不要启动后台常驻轮询进程。\n"
                + "用户说\"有新任务了\"时拉取收件箱并处理，处理完回到等待状态。\n"
                + "推荐使用下方 SKILL 文档中 scripts/ 目录下的脚本包（clock.ps1 / pull_tasks.ps1 / process_one.ps1）。\n\n"
                + "【执行要求】\n"
                + "1. 你已在 HelloAI 平台完成注册，无需再次注册。\n"
                + "2. 你需要按照以下 Skill 内容工作。\n"
                + "3. 文中所有 API 请求需携带 Header: Authorization: Bearer " + apiKey + "\n"
                + "4. 首次进入后先获取规则，再查收件箱和任务。\n\n"
                + "【SKILL】\n"
                + skillContent;
    }
}
