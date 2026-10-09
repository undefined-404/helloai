package com.helloai.core.agent.skill;

import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;
import org.yaml.snakeyaml.error.YAMLException;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * frontmatter YAML → {@link SkillPackage}（REF-1.1b / REF-1.2b）。
 *
 * <p><b>已知字段白名单 = 8 个</b>：{@code name / version / description / requiredTools /
 * dependencies / inputSchema / outputSchema / validationRules}。{@code fileName} <b>禁止出现</b>
 * ——它由目录扫描推导，写进 frontmatter 就是第二个事实源。</p>
 *
 * <p><b>失败一律抛 {@link SkillCorruptException}</b>，消息面向人：未知字段 / 缺必填 / 类型不符 /
 * 值为空 / 重复键 / YAML 语法错，逐类给出可操作的提示（如「version 需要加引号」）。</p>
 *
 * <p><b>安全</b>：使用 {@link SafeConstructor} 并禁用重复键——绝不用无参 {@code new Yaml()}
 * （默认 Constructor 允许 {@code !!} 实例化任意类）。</p>
 */
public final class SkillPackageParser {

    private SkillPackageParser() {
    }

    /** 已知顶层字段（白名单；新增字段须同批更新本集合，否则旧文件会被判 corrupt）。 */
    private static final Set<String> KNOWN_KEYS = Set.of(
            "name", "version", "description", "requiredTools",
            "dependencies", "inputSchema", "outputSchema", "validationRules");

    /** 必填字段。 */
    private static final List<String> REQUIRED_KEYS = List.of("name", "version", "description");

    private static final List<String> LIST_KEYS = List.of("requiredTools", "dependencies", "validationRules");

    private static final List<String> MAP_KEYS = List.of("inputSchema", "outputSchema");

    /**
     * @param fileName 目录扫描得到的实际文件名（如 {@code eng-code-review.md}），直接作为 {@code fileName} 字段
     * @param yamlText frontmatter 的 YAML 文本（不含围栏）
     * @throws SkillCorruptException 任一类定义错误
     */
    public static SkillPackage parse(String fileName, String yamlText) throws SkillCorruptException {
        Map<String, Object> root = loadRoot(yamlText);

        for (String key : root.keySet()) {
            if ("fileName".equals(key)) {
                throw new SkillCorruptException("fileName 由目录扫描推导，不应写入 frontmatter");
            }
            if (!KNOWN_KEYS.contains(key)) {
                throw new SkillCorruptException("未知字段：" + key + "（已知字段：" + String.join(" / ", KNOWN_KEYS) + "）");
            }
        }
        for (String key : REQUIRED_KEYS) {
            if (!root.containsKey(key)) {
                throw new SkillCorruptException("缺少必填字段：" + key);
            }
        }

        String name = requireText(root, "name");
        String version = requireText(root, "version");
        String description = requireText(root, "description");

        List<String> requiredTools = optionalTextList(root, "requiredTools");
        List<String> dependencies = optionalTextList(root, "dependencies");
        List<String> validationRules = optionalTextList(root, "validationRules");
        Map<String, Object> inputSchema = optionalSchema(root, "inputSchema");
        Map<String, Object> outputSchema = optionalSchema(root, "outputSchema");

        return new SkillPackage(name, version, description, requiredTools, dependencies,
                inputSchema, outputSchema, validationRules, fileName);
    }

    private static Map<String, Object> loadRoot(String yamlText) throws SkillCorruptException {
        LoaderOptions options = new LoaderOptions();
        options.setAllowDuplicateKeys(false);
        options.setMaxAliasesForCollections(50);
        Object loaded;
        try {
            loaded = new Yaml(new SafeConstructor(options)).load(yamlText);
        } catch (YAMLException e) {
            throw new SkillCorruptException("frontmatter YAML 解析失败：" + firstLine(e.getMessage()), e);
        }
        if (loaded == null) {
            throw new SkillCorruptException("frontmatter 为空（无任何字段）");
        }
        if (!(loaded instanceof Map<?, ?> map)) {
            throw new SkillCorruptException("frontmatter 顶层必须是键值映射，实际为 " + loaded.getClass().getSimpleName());
        }
        Map<String, Object> root = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            if (!(entry.getKey() instanceof String key)) {
                throw new SkillCorruptException("frontmatter 字段名必须是字符串，实际为 " + entry.getKey());
            }
            root.put(key, entry.getValue());
        }
        return root;
    }

    private static String requireText(Map<String, Object> root, String key) throws SkillCorruptException {
        Object value = root.get(key);
        if (value == null) {
            throw new SkillCorruptException(key + " 不能为空");
        }
        if (!(value instanceof String text)) {
            throw new SkillCorruptException(key + " 必须是字符串（若为版本号请加引号，如 version: \"1.0.0\"），实际为 "
                    + value.getClass().getSimpleName());
        }
        if (text.isBlank()) {
            throw new SkillCorruptException(key + " 不能为空白");
        }
        return text;
    }

    private static List<String> optionalTextList(Map<String, Object> root, String key) throws SkillCorruptException {
        Object value = root.get(key);
        if (value == null) {
            return List.of();
        }
        if (!(value instanceof List<?> list)) {
            throw new SkillCorruptException(key + " 必须是列表（如 " + key + ": [] 或 " + key + ": 换行后以 - 开头逐项列出）");
        }
        List<String> out = new ArrayList<>(list.size());
        for (Object item : list) {
            if (item == null) {
                throw new SkillCorruptException(key + " 不能包含空项（null）");
            }
            if (!(item instanceof String text)) {
                throw new SkillCorruptException(key + " 的每一项必须是字符串，实际含 "
                        + item.getClass().getSimpleName() + "（数字或布尔请加引号）");
            }
            out.add(text);
        }
        return out;
    }

    private static Map<String, Object> optionalSchema(Map<String, Object> root, String key) throws SkillCorruptException {
        Object value = root.get(key);
        if (value == null) {
            return Map.of();
        }
        if (!(value instanceof Map<?, ?> map)) {
            throw new SkillCorruptException(key + " 必须是映射（无结构化声明时写 " + key + ": {}）");
        }
        Map<String, Object> out = new LinkedHashMap<>();
        putSchema(out, map, key);
        return out;
    }

    /**
     * 逐层复制为 {@code Map<String,Object>}，同时拦下 {@code null}——{@code SkillPackage} 的
     * compact 构造器用 {@code Map.copyOf} / {@code List.copyOf}，遇 null 会抛 NPE 而非可读的 corrupt 原因。
     */
    private static void putSchema(Map<String, Object> out, Map<?, ?> map, String path) throws SkillCorruptException {
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            if (!(entry.getKey() instanceof String childKey)) {
                throw new SkillCorruptException(path + " 的键必须是字符串，实际为 " + entry.getKey());
            }
            if (entry.getValue() == null) {
                throw new SkillCorruptException(path + "." + childKey + " 不能为空（null）");
            }
            Object child = entry.getValue();
            if (child instanceof Map<?, ?> childMap) {
                Map<String, Object> nested = new LinkedHashMap<>();
                out.put(childKey, nested);
                putSchema(nested, childMap, path + "." + childKey);
            } else if (child instanceof List<?> childList) {
                List<Object> normalized = new ArrayList<>(childList.size());
                for (Object item : childList) {
                    if (item == null) {
                        throw new SkillCorruptException(path + "." + childKey + " 不能包含空项（null）");
                    }
                    normalized.add(item);
                }
                out.put(childKey, normalized);
            } else {
                out.put(childKey, child);
            }
        }
    }

    private static String firstLine(String message) {
        if (message == null) {
            return "未知原因";
        }
        int nl = message.indexOf('\n');
        return nl > 0 ? message.substring(0, nl) : message;
    }

    /** 供测试与校验脚本对照的已知字段集合。 */
    public static Set<String> knownKeys() {
        return KNOWN_KEYS;
    }
}
