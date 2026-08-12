package com.helloai.core.agent;

import com.helloai.common.constant.AgentAccessType;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Agent 技能（skills）best-effort 推导工具类（A2）。
 *
 * <p>V47 后 {@code agent.skills} 供任务 {@code required_skills} 做 AND 匹配，
 * 但注册链路此前从不填充，技能匹配形同虚设。本类按接入类型与注册名称/描述
 * 关键词推导基础技能标签，规则：<b>显式传入的技能优先</b>，其次在已有技能
 * 为空时才做推导（不覆盖手工显式值）。</p>
 *
 * <p>推导为 best-effort：结果只是合理的起点，管理员/Agent 可在注册或管理端
 * 更新时显式覆盖。</p>
 */
public final class AgentSkillDeriver {

    private AgentSkillDeriver() {}

    /** 关键词 → 技能标签（小写匹配，先列先得不叠加同标签）。 */
    private static final Map<String, String> KEYWORD_SKILLS = keywordSkills();

    /** accessType 兜底基础技能（无任何关键词命中时保证至少一个技能）。 */
    private static final Map<AgentAccessType, String> BASE_SKILLS = Map.of(
            AgentAccessType.CLI_CLIENT, "shell",
            AgentAccessType.API_KEY_LLM, "code-review",
            AgentAccessType.WEB_BROWSER, "web-search");

    /**
     * 推导技能列表。
     *
     * <p>优先级：① explicitSkills 非空 → 清洗后直接返回（手工显式值优先，
     * 不被推导覆盖）；② 否则按 accessType 基础技能 + 名称/描述关键词命中合并，
     * 去重保序。</p>
     *
     * @param type          接入类型（决定基础技能；null 按无基础技能处理）
     * @param name          Agent 名称（可为 null）
     * @param description   Agent 描述（可为 null）
     * @param explicitSkills 显式技能（可为 null/空）
     * @return 清洗后的技能列表（非 null，可能为空）
     */
    public static List<String> derive(AgentAccessType type, String name, String description,
                                      List<String> explicitSkills) {
        List<String> cleaned = clean(explicitSkills);
        if (!cleaned.isEmpty()) {
            return cleaned;
        }
        LinkedHashSet<String> skills = new LinkedHashSet<>();
        if (type != null) {
            String base = BASE_SKILLS.get(type);
            if (base != null) {
                skills.add(base);
            }
        }
        String haystack = haystackOf(name, description);
        for (Map.Entry<String, String> entry : KEYWORD_SKILLS.entrySet()) {
            if (haystack.contains(entry.getKey())) {
                skills.add(entry.getValue());
            }
        }
        return new ArrayList<>(skills);
    }

    /**
     * 清洗技能列表：trim、过滤空白、去重保序；null 或全空返回空列表。
     */
    public static List<String> clean(List<String> raw) {
        if (raw == null || raw.isEmpty()) {
            return new ArrayList<>();
        }
        LinkedHashSet<String> skills = new LinkedHashSet<>();
        for (String s : raw) {
            if (s == null) {
                continue;
            }
            String trimmed = s.trim();
            if (!trimmed.isEmpty()) {
                skills.add(trimmed);
            }
        }
        return new ArrayList<>(skills);
    }

    private static String haystackOf(String name, String description) {
        String n = name == null ? "" : name.toLowerCase(Locale.ROOT);
        String d = description == null ? "" : description.toLowerCase(Locale.ROOT);
        return n + ' ' + d;
    }

    private static Map<String, String> keywordSkills() {
        Map<String, String> map = new LinkedHashMap<>();
        map.put("docker", "docker");
        map.put("容器", "docker");
        map.put("python", "python");
        map.put("java", "java");
        map.put("sql", "sql");
        map.put("数据库", "sql");
        map.put("shell", "shell");
        map.put("bash", "shell");
        map.put("powershell", "shell");
        map.put("脚本", "shell");
        map.put("cli", "shell");
        map.put("web", "web-search");
        map.put("search", "web-search");
        map.put("搜索", "web-search");
        map.put("浏览器", "web-search");
        map.put("爬虫", "web-search");
        map.put("review", "code-review");
        map.put("审查", "code-review");
        map.put("评审", "code-review");
        return map;
    }
}
