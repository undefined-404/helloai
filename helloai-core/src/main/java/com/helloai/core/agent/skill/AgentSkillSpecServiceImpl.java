package com.helloai.core.agent.skill;

import com.helloai.core.agent.SkillNormalizer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * {@link AgentSkillSpecService} 实现——classpath 读取平台 eng-* 规范库，
 * 把任务声明的 required_skills（装箱传入）解析为命中标签 + 渲染速览段。
 *
 * <p>Phase 1 Step 1 fix（LOG-20260904-009）：由 task 域 {@code PluginSkillSpecServiceImpl}
 * 迁域而来，<b>纯函数式</b>——入参即 requiredSkills，不再反向查询 task（§6 依赖方向红线），
 * 因此不持有任何 task 域依赖；资源仍从 classpath {@code skills/plugins/*.md} 读取
 * （helloai-core 模块内，迁域不改资源位置）。</p>
 */
@Slf4j
@Service
public class AgentSkillSpecServiceImpl implements AgentSkillSpecService {

    /** 已登记插件标签 → classpath 文件名（LinkedHashMap 保序，渲染顺序按声明顺序）。 */
    private static final Map<String, String> KNOWN_SPECS = knownSpecs();

    /** 规范文件内「执行速览」与「详细规范」的分隔标记（速览在前）。 */
    private static final String DETAIL_SEPARATOR = "\n---\n";

    /**
     * 一次性解析任务平台技能规范（D1=B）：声明 / 命中 / 渲染三件套，命中语义与
     * Prompt 注入事实严格一致（两层过滤：标签命中 + 速览非空）。
     *
     * <p>best-effort：requiredSkills 为 null / 空 / 未命中均返回空三字段，不抛异常。</p>
     */
    @Override
    public ResolvedSpec resolve(List<String> requiredSkills) {
        List<String> required = requiredSkills == null ? List.of() : requiredSkills;
        if (required.isEmpty()) {
            return new ResolvedSpec(required, List.of(), "");
        }
        List<String> normalized = SkillNormalizer.normalizeAll(required);
        List<String> matched = new ArrayList<>();
        StringBuilder specs = new StringBuilder();
        for (Map.Entry<String, String> entry : KNOWN_SPECS.entrySet()) {
            if (!normalized.contains(entry.getKey())) {
                continue;
            }
            String summary = loadSpeedSummary(entry.getKey(), entry.getValue());
            if (summary == null || summary.isBlank()) {
                continue;
            }
            matched.add(entry.getKey());
            specs.append("\n### ").append(entry.getKey()).append('\n');
            specs.append(summary).append('\n');
        }
        if (matched.isEmpty()) {
            return new ResolvedSpec(required, List.of(), "");
        }
        String section = "## 平台技能规范（任务所需技能命中 eng-* 规范）\n"
                + "> 以下规范由平台按任务 required_skills 命中注入；产出必须按此执行，"
                + "审查侧按同一清单核验。\n"
                + specs;
        return new ResolvedSpec(required, matched, section);
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