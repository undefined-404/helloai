package com.helloai.core.planner.policy;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * {@code requirement_conversation.final_package} / {@code task.context.requirementPackage}
 * JSONB 的静态解析与渲染工具（G-011 需求包，同 {@code TaskAgentPolicy} 防御式模式）。
 *
 * <p>全部读取 / 判定统一收口到本类，避免各处散落 Map 取值与防御式类型转换。
 * 键结构与默认值语义（设计 D1 压缩版 + P1 修订补回任务级验收条目）：
 * <ul>
 *   <li>{@code goal}：可验收目标（字符串，缺失 / 类型异常为 null）；</li>
 *   <li>{@code scope} / {@code outOfScope} / {@code acceptanceCriteria} / {@code assumptions} /
 *       {@code openQuestions}：字符串数组（缺失 / 类型异常为空列表，元素非字符串丢弃）。</li>
 * </ul>
 * 所有解析均为防御式：source 为 null、键缺失、类型异常时一律回落空集合 / null，
 * 与旧数据「无需求包」行为完全一致（拆解渲染占位文案）。解析不抛异常、不阻断主流程
 * （LLM 输出防御模式，同 contract / requiredSkills / constraints）。</p>
 */
public final class RequirementPackageParser {

    private RequirementPackageParser() {
    }

    /** 需求包键：可验收目标（一句话）。 */
    public static final String KEY_GOAL = "goal";

    /** 需求包键：需求范围条目（字符串数组）。 */
    public static final String KEY_SCOPE = "scope";

    /** 需求包键：明确不做项（字符串数组，拆解侧边界硬约束）。 */
    public static final String KEY_OUT_OF_SCOPE = "outOfScope";

    /** 需求包键：任务级验收条目（字符串数组，拆解侧封闭覆盖校验 + 子任务 acceptance 回溯锚点）。 */
    public static final String KEY_ACCEPTANCE_CRITERIA = "acceptanceCriteria";

    /** 需求包键：关键假设 / 推断项（字符串数组）。 */
    public static final String KEY_ASSUMPTIONS = "assumptions";

    /** 需求包键：待确认 / 阻断项（字符串数组）。 */
    public static final String KEY_OPEN_QUESTIONS = "openQuestions";

    /** context 键：task.context 内需求包键名（拆解链读取点，与 runningSpec 键隔离）。 */
    public static final String CONTEXT_KEY_REQUIREMENT_PACKAGE = "requirementPackage";

    /**
     * 防御式六字段读取：source 为 null / 键缺失 / 类型异常回落空集合（goal 回落 null），
     * 永不抛异常。数组元素仅保留字符串，其余类型丢弃。
     *
     * @param source 需求包 JSONB 反序列化后的 Map（可为 null）
     * @return 解析结果；六字段全无内容时等价于 {@link RequirementPackage#EMPTY}
     */
    public static RequirementPackage parse(Map<String, Object> source) {
        if (source == null || source.isEmpty()) {
            return RequirementPackage.EMPTY;
        }
        return new RequirementPackage(
                asString(source.get(KEY_GOAL)),
                asStringList(source.get(KEY_SCOPE)),
                asStringList(source.get(KEY_OUT_OF_SCOPE)),
                asStringList(source.get(KEY_ACCEPTANCE_CRITERIA)),
                asStringList(source.get(KEY_ASSUMPTIONS)),
                asStringList(source.get(KEY_OPEN_QUESTIONS)));
    }

    /**
     * 从 task.context 读取需求包（拆解链读取点）：context 为 null、
     * 键缺失或值非 Map 时回落 EMPTY（旧任务/未走澄清链路行为零变化）。
     *
     * @param context task.context JSONB 反序列化后的 Map（可为 null）
     * @return 解析结果；无需求包时为空需求包
     */
    public static RequirementPackage fromContext(Map<String, Object> context) {
        if (context == null || context.isEmpty()) {
            return RequirementPackage.EMPTY;
        }
        Object raw = context.get(CONTEXT_KEY_REQUIREMENT_PACKAGE);
        if (!(raw instanceof Map<?, ?> rawMap)) {
            return RequirementPackage.EMPTY;
        }
        Map<String, Object> packageMap = new LinkedHashMap<>();
        rawMap.forEach((k, v) -> packageMap.put(String.valueOf(k), v));
        return parse(packageMap);
    }

    /**
     * 需求包段渲染（拆解 Prompt 占位符 {@code {{REQUIREMENT_PACKAGE}}}）：六字段逐项列表，
     * 空数组字段不渲染（防提示词膨胀）；全空需求包渲染占位文案。
     *
     * @param pkg 需求包（可为 null，按空需求包处理）
     * @return Markdown 逐项列表；无需求包时返回占位文案「（本任务未经过澄清链路，无结构化需求包——按任务描述拆解）」
     */
    public static String render(RequirementPackage pkg) {
        RequirementPackage source = pkg == null ? RequirementPackage.EMPTY : pkg;
        if (source.isEmpty()) {
            return "（本任务未经过澄清链路，无结构化需求包——按任务描述拆解）";
        }
        StringBuilder sb = new StringBuilder();
        if (source.goal() != null && !source.goal().isBlank()) {
            sb.append("- 目标：").append(source.goal()).append('\n');
        }
        appendList(sb, "范围", source.scope());
        appendList(sb, "明确不做（outOfScope）", source.outOfScope());
        appendList(sb, "任务级验收标准（acceptanceCriteria）", source.acceptanceCriteria());
        appendList(sb, "关键假设（推断项，须标注）", source.assumptions());
        appendList(sb, "待确认事项（openQuestions）", source.openQuestions());
        return sb.toString().trim();
    }

    /** 渲染辅助：数组字段非空时输出「- 字段名：」+ 逐条子列表，空数组不渲染。 */
    private static void appendList(StringBuilder sb, String label, List<String> items) {
        if (items == null || items.isEmpty()) {
            return;
        }
        sb.append("- ").append(label).append("：\n");
        for (String item : items) {
            if (item != null && !item.isBlank()) {
                sb.append("  - ").append(item).append('\n');
            }
        }
    }

    /** 字符串读取：非 String 类型（数字/布尔/嵌套对象）回落 null。 */
    private static String asString(Object value) {
        return value instanceof String s ? s : null;
    }

    /** 字符串数组读取：非 List 回落空列表；元素仅保留字符串，其余类型丢弃。 */
    private static List<String> asStringList(Object value) {
        if (!(value instanceof List<?> list)) {
            return List.of();
        }
        List<String> result = new ArrayList<>(list.size());
        for (Object item : list) {
            if (item instanceof String s && !s.isBlank()) {
                result.add(s);
            }
        }
        return result;
    }
}
