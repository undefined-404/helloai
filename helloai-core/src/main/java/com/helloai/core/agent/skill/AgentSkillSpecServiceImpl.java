package com.helloai.core.agent.skill;

import com.helloai.core.agent.SkillNormalizer;
import lombok.RequiredArgsConstructor;
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
 * <p>LOG-20260904-009：由 task 域 {@code PluginSkillSpecServiceImpl}
 * 迁域而来，<b>纯函数式</b>——入参即 requiredSkills，不再反向查询 task（§6 依赖方向红线），
 * 因此不持有任何 task 域依赖；资源仍从 classpath {@code skills/plugins/*.md} 读取
 * （helloai-core 模块内，迁域不改资源位置）。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AgentSkillSpecServiceImpl implements AgentSkillSpecService {

    /** 技能包目录（REF-1.2a）：元数据事实源 = classpath {@code skills/plugins/*.md} 的 frontmatter。 */
    private final SkillPackageCatalog catalog;

    /**
     * 一次性解析任务平台技能规范（D1=B）：声明 / 命中 / 渲染三件套 + 版本映射 + 工具并集，
     * 命中语义与 Prompt 注入事实严格一致（两层过滤：标签命中 + 速览非空）。
     *
     * <p>G-004 增量 A（联动接线）：resolvedVersions 供 SKILL_RESOLVED 事件携带版本；
     * requiredTools 为命中技能的声明工具并集（去重、按技能 name 升序追加），
     * 供执行侧并入启用工具清单（TOOL_RESOLVED 前合并）。</p>
     *
     * <p>best-effort：requiredSkills 为 null / 空 / 未命中均返回空五字段，不抛异常。
     * <b>坏技能包（corrupt）等同「文件缺失」</b>——在目录层已被过滤为 healthy，不会进入本方法。</p>
     */
    @Override
    public ResolvedSpec resolve(List<String> requiredSkills) {
        List<String> required = requiredSkills == null ? List.of() : requiredSkills;
        if (required.isEmpty()) {
            return new ResolvedSpec(required, List.of(), "");
        }
        List<String> normalized = SkillNormalizer.normalizeAll(required);
        List<String> matched = new ArrayList<>();
        Map<String, String> versions = new LinkedHashMap<>();
        List<String> tools = new ArrayList<>();
        StringBuilder specs = new StringBuilder();
        for (Map.Entry<String, SkillPackage> entry : catalog.byName().entrySet()) {
            if (!normalized.contains(entry.getKey())) {
                continue;
            }
            SkillPackage pkg = entry.getValue();
            String summary = loadSpeedSummary(pkg.name(), pkg.fileName());
            if (summary == null || summary.isBlank()) {
                continue;
            }
            matched.add(entry.getKey());
            versions.put(entry.getKey(), pkg.version());
            for (String tool : pkg.requiredTools()) {
                if (tool != null && !tool.isBlank() && !tools.contains(tool)) {
                    tools.add(tool);
                }
            }
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
        return new ResolvedSpec(required, matched, section, versions, tools);
    }

    @Override
    public List<SkillPackage> listPackages() {
        return catalog.healthyPackages();
    }

    @Override
    public List<SkillPackage> resolvePackages(List<String> requiredSkills) {
        List<String> required = requiredSkills == null ? List.of() : requiredSkills;
        if (required.isEmpty()) {
            return List.of();
        }
        List<String> normalized = SkillNormalizer.normalizeAll(required);
        List<SkillPackage> matched = new ArrayList<>();
        for (Map.Entry<String, SkillPackage> entry : catalog.byName().entrySet()) {
            if (!normalized.contains(entry.getKey())) {
                continue;
            }
            SkillPackage pkg = entry.getValue();
            String summary = loadSpeedSummary(pkg.name(), pkg.fileName());
            if (summary == null || summary.isBlank()) {
                continue;
            }
            matched.add(pkg);
        }
        return matched;
    }

    /**
     * 读取规范文件的「执行速览」部分，并去掉文件 h1 标题行
     * （渲染段自带 {@code ### 标签} 标题，避免重复层级）。
     *
     * <p><b>顺序即正确性</b>：必须<b>先剥 frontmatter、再切分隔符</b>——md 引入 frontmatter 后，
     * 闭合围栏本身就是 {@code \n---\n}，会被 {@code indexOf} 先命中（REF-1.1a）。
     * 无 frontmatter 时 {@link SkillFrontMatter#stripBody} 为恒等变换，故纯正文 md 行为不变。</p>
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
            return SkillSpeedSummaryRenderer.render(SkillFrontMatter.stripBody(content));
        } catch (Exception e) {
            log.warn("平台技能规范读取失败，跳过该规范（不阻断执行链）: label={}, err={}",
                    label, e.getMessage());
            return null;
        }
    }
}
