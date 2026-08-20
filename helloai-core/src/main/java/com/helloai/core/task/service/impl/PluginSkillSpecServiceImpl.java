package com.helloai.core.task.service.impl;

import com.helloai.core.agent.SkillNormalizer;
import com.helloai.core.task.entity.Task;
import com.helloai.core.task.service.PluginSkillSpecService;
import com.helloai.core.task.service.TaskService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * {@link PluginSkillSpecService} 实现——classpath 读取平台 eng-* 规范库，
 * 按任务 {@code required_skills} 命中渲染「执行速览」段。
 *
 * <p>约定：每份规范文件（skills/plugins/{name}.md）以「执行速览」开头、
 * 首个 {@code ---} 分隔详细规范；渲染时只取速览部分（控制注入 token 成本），
 * 完整文件保留供外部 Agent 参考与产出对齐。</p>
 *
 * <p>失败语义：任务不存在 / 无 required_skills / 未命中 / 文件缺失均返回空串，
 * 绝不抛异常阻断执行链（best-effort，与依赖上下文装配哲学一致）。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PluginSkillSpecServiceImpl implements PluginSkillSpecService {

    /** 已登记插件标签 → classpath 文件名（LinkedHashMap 保序，渲染顺序按声明顺序）。 */
    private static final Map<String, String> KNOWN_SPECS = knownSpecs();

    /** 规范文件内「执行速览」与「详细规范」的分隔标记（速览在前）。 */
    private static final String DETAIL_SEPARATOR = "\n---\n";

    private final TaskService taskService;

    @Override
    public String renderSection(Long taskId) {
        if (taskId == null) {
            return "";
        }
        Task task = taskService.getById(taskId);
        if (task == null || task.getRequiredSkills() == null || task.getRequiredSkills().isEmpty()) {
            return "";
        }
        List<String> normalized = SkillNormalizer.normalizeAll(task.getRequiredSkills());
        StringBuilder specs = new StringBuilder();
        for (Map.Entry<String, String> entry : KNOWN_SPECS.entrySet()) {
            if (!normalized.contains(entry.getKey())) {
                continue;
            }
            String summary = loadSpeedSummary(entry.getKey(), entry.getValue());
            if (summary == null || summary.isBlank()) {
                continue;
            }
            specs.append("\n### ").append(entry.getKey()).append('\n');
            specs.append(summary).append('\n');
        }
        if (specs.length() == 0) {
            return "";
        }
        return "## 平台技能规范（任务所需技能命中 eng-* 规范）\n"
                + "> 以下规范由平台按任务 required_skills 命中注入；产出必须按此执行，"
                + "审查侧按同一清单核验。\n"
                + specs;
    }

    /**
     * 读取规范文件的「执行速览」部分（首个 {@code ---} 之前），并去掉文件 h1 标题行
     * （渲染段自带 {@code ### 标签} 标题，避免重复层级）。
     */
    private String loadSpeedSummary(String label, String fileName) {
        try {
            ClassPathResource resource = new ClassPathResource("skills/plugins/" + fileName);
            if (!resource.exists()) {
                log.warn("平台技能规范文件缺失，跳过: label={}, path={}", label, fileName);
                return null;
            }
            String content;
            try (InputStream in = resource.getInputStream()) {
                content = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            }
            // 兼容 CRLF 行尾（Windows 检出），否则 DETAIL_SEPARATOR 匹配失败导致详细规范被整体注入
            content = content.replace("\r\n", "\n");
            int cut = content.indexOf(DETAIL_SEPARATOR);
            if (cut >= 0) {
                content = content.substring(0, cut);
            }
            StringBuilder body = new StringBuilder();
            for (String line : content.split("\r?\n")) {
                if (line.startsWith("# ")) {
                    continue;
                }
                body.append(line).append('\n');
            }
            return body.toString().trim();
        } catch (Exception e) {
            log.warn("平台技能规范读取失败，跳过该规范（不阻断执行链）: label={}, err={}",
                    label, e.getMessage());
            return null;
        }
    }

    private static Map<String, String> knownSpecs() {
        Map<String, String> map = new LinkedHashMap<>();
        map.put("eng-code-review", "eng-code-review.md");
        map.put("eng-doc-standard", "eng-doc-standard.md");
        map.put("eng-verification", "eng-verification.md");
        return map;
    }
}
