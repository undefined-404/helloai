package com.helloai.core.agent;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 技能标签归一化工具类（A3）。
 *
 * <p>V47 后任务 {@code required_skills} 对 {@code agent.skills} 做 AND 精确匹配
 * （{@code AgentSelectionConstraints.allows()} 的 {@code containsAll}），"shell 脚本"
 * 与 "powershell" 被视为不同技能导致误过滤。本类在<b>匹配前</b>把双方技能标签
 * 归一到规范标签（trim + 小写 + 同义词归并），同义词技能互相命中。</p>
 *
 * <p>与 {@link AgentSkillDeriver} 的分工：Deriver 负责<b>注册时</b>从名称/描述推导
 * 技能（关键词 → 标签）；本类负责<b>匹配时</b>把已声明的标签归一（同义词 → 规范标签），
 * 两者词表语义对齐（同义词键与 Deriver 关键词表一致），避免同一技能两套写法。</p>
 */
public final class SkillNormalizer {

    private SkillNormalizer() {}

    /** 技能同义词 → 规范标签（小写匹配；规范标签自身不在键集，未命中原样返回）。 */
    private static final Map<String, String> SYNONYMS = synonyms();

    /**
     * 归一单个技能标签：null/空白返回 null；trim + 小写后命中同义词表返回规范标签，
     * 未命中返回小写原样（自定义技能如 kubernetes/golang 保持可精确匹配）。
     */
    public static String normalize(String skill) {
        if (skill == null) {
            return null;
        }
        String trimmed = skill.trim();
        if (trimmed.isEmpty()) {
            return null;
        }
        String lower = trimmed.toLowerCase(Locale.ROOT);
        return SYNONYMS.getOrDefault(lower, lower);
    }

    /**
     * 归一技能列表：逐项 {@link #normalize(String)}，跳过 null，去重保序；
     * null 或全空白返回空列表。
     */
    public static List<String> normalizeAll(List<String> skills) {
        LinkedHashSet<String> normalized = new LinkedHashSet<>();
        if (skills != null) {
            for (String s : skills) {
                String n = normalize(s);
                if (n != null) {
                    normalized.add(n);
                }
            }
        }
        return new ArrayList<>(normalized);
    }

    /**
     * 技能 AND 匹配（归一化后 containsAll）：任务要求技能全部被 Agent 技能包含；
     * requiredSkills 为 null/空时视为不约束（返回 true），与调用方"空=不限定"语义一致。
     */
    public static boolean matches(List<String> agentSkills, List<String> requiredSkills) {
        if (requiredSkills == null || requiredSkills.isEmpty()) {
            return true;
        }
        List<String> normalizedAgent = normalizeAll(agentSkills);
        List<String> normalizedRequired = normalizeAll(requiredSkills);
        return normalizedAgent.containsAll(normalizedRequired);
    }

    private static Map<String, String> synonyms() {
        Map<String, String> map = new LinkedHashMap<>();
        map.put("容器", "docker");
        map.put("数据库", "sql");
        map.put("bash", "shell");
        map.put("powershell", "shell");
        map.put("脚本", "shell");
        map.put("cli", "shell");
        map.put("web", "web-search");
        map.put("search", "web-search");
        map.put("搜索", "web-search");
        map.put("检索", "web-search");
        map.put("联网", "web-search");
        map.put("浏览器", "web-search");
        map.put("爬虫", "web-search");
        map.put("review", "code-review");
        map.put("审查", "code-review");
        map.put("评审", "code-review");
        map.put("思考", "thinking");
        map.put("推理", "thinking");
        map.put("深度思考", "thinking");
        return map;
    }
}
