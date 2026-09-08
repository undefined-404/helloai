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

    /** 已登记插件标签 → 技能包元数据（LinkedHashMap 保序，渲染顺序按声明顺序）。 */
    private static final Map<String, SkillPackage> KNOWN_SPECS = knownSpecs();

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
        for (Map.Entry<String, SkillPackage> entry : KNOWN_SPECS.entrySet()) {
            if (!normalized.contains(entry.getKey())) {
                continue;
            }
            SkillPackage pkg = entry.getValue();
            String summary = loadSpeedSummary(pkg.name(), pkg.fileName());
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

    @Override
    public List<SkillPackage> listPackages() {
        return List.copyOf(KNOWN_SPECS.values());
    }

    @Override
    public List<SkillPackage> resolvePackages(List<String> requiredSkills) {
        List<String> required = requiredSkills == null ? List.of() : requiredSkills;
        if (required.isEmpty()) {
            return List.of();
        }
        List<String> normalized = SkillNormalizer.normalizeAll(required);
        List<SkillPackage> matched = new ArrayList<>();
        for (Map.Entry<String, SkillPackage> entry : KNOWN_SPECS.entrySet()) {
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

    private static Map<String, SkillPackage> knownSpecs() {
        Map<String, SkillPackage> map = new LinkedHashMap<>();
        // eng-code-review：输出为自查问题四元组（[defect][location][impact][evidence]，文件明示）；
        // 验证规则提炼自 C1~C4
        map.put("eng-code-review", new SkillPackage(
                "eng-code-review", "1.0.0",
                "代码评审规范：接口契约 / 生命周期与并发 / 验证强度 / 范围与必要性（C1~C4）",
                List.of(), List.of(), Map.of(),
                Map.of("type", "array", "items", Map.of(
                        "type", "object",
                        "properties", Map.of(
                                "defect", Map.of("type", "string"),
                                "location", Map.of("type", "string"),
                                "impact", Map.of("type", "string"),
                                "evidence", Map.of("type", "string")))),
                List.of(
                        "接口契约必须文档化：函数签名 / 返回值区分 / 异常约定 / 边界条件（C1）",
                        "资源创建与释放成对出现，共享状态说明锁粒度与竞态处理（C2）",
                        "每个关键行为至少一条真断言，禁止永真冒烟充当证据（C3）",
                        "不写投机泛化与过度抽象，新增依赖须说明必要性（C4）"),
                "eng-code-review.md"));
        // eng-doc-standard：文档类产出无结构化输出声明（outputSchema 置空）；验证规则提炼自 D1~D3 + 信息密度
        map.put("eng-doc-standard", new SkillPackage(
                "eng-doc-standard", "1.0.0",
                "文档规范：接口文档化、自查产出四元组格式",
                List.of(), List.of(), Map.of(), Map.of(),
                List.of(
                        "命题完整保留：每条必须/不得陈述含主体 + 条件 + 模态 + 失败模式（D1）",
                        "tutorial / reference 分离，不混写（D3）",
                        "无思维链泄漏：8 类实现/评审叙事不得出现在面向使用者文档（D2）",
                        "信息密度：删掉后信息不减的语句必须删"),
                "eng-doc-standard.md"));
        // eng-verification：输出为验证证据结构（命令 + 实测输出 + 结论 + 环境，文件明示）；
        // 验证规则提炼自最小证据集 / 证据真实可复现 / 断言有效 / 环境可复现
        map.put("eng-verification", new SkillPackage(
                "eng-verification", "1.0.0",
                "验证规范：最小证据集 / 证据真实可复现 / 断言有效性 / 环境可复现",
                List.of(), List.of(), Map.of(),
                Map.of("type", "object", "properties", Map.of(
                        "command", Map.of("type", "string"),
                        "output", Map.of("type", "string"),
                        "conclusion", Map.of("type", "string"),
                        "environment", Map.of("type", "string"))),
                List.of(
                        "最小证据集：所选验证组合必须覆盖目标行为本身",
                        "证据真实可复现：写明验证命令与实测输出关键片段",
                        "断言必须因目标回归而失败，写死预期等于无验证",
                        "注明验证环境（JDK 版本 / 服务版本 / profile / 关键配置）"),
                "eng-verification.md"));
        return map;
    }
}