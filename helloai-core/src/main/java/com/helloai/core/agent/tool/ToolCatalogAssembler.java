package com.helloai.core.agent.tool;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * 工具目录装配器（MA-5 第一步「目录 + 按需取」）。
 *
 * <p><b>职责</b>：把 {@link ToolRegistry#resolve} 的<b>生效结果</b>（模型可见工具的唯一判据）
 * 转成「目录形态」——每个工具只带 {@code name} + 摘要（描述前 {@code maxLength} 字符 + 省略标记），
 * 白名单内的高频核心工具保留完整描述。模型每次请求不再背全部工具定义，详情经
 * {@code internal_load_tool} 按需取（对齐技能侧 {@code internal_list_skills} /
 * {@code internal_load_skill} 已在跑的范式，DSH {@code catalogDescriptionMaxLength=160}）。</p>
 *
 * <p><b>为什么变换后仍是 {@link ToolDefinition} 列表</b>：{@code VisibleToolCallbacks.select}
 * 的过滤判据与描述包装都吃 {@code ToolDefinition}（{@code name}+{@code description} 两个字段），
 * 目录化 = 只换 {@code description} 内容，类型与顺序不变 ⇒ 目录条目与 {@code effectiveTools}
 * 一一对应（不漏不重）、回调合成与降级路径零改动。参数 schema 属 spring-ai 注解面，
 * 本类<b>不</b>新增字段（MA-5 诚实边界 5）。</p>
 *
 * <p><b>入参必须是 resolve 之后的结果</b>：resolve 已含「条件可用摘除 + 动态描述重写」——
 * 被摘除的工具天然不在目录里（「模型知道有但调不到」比「不知道」更糟，蓝图 §4 MA-5 ⑦）。
 * 目录若基于全量注册表即违反该语义，故本类<b>不</b>接收注册表。</p>
 *
 * <p><b>纯静态工具</b>：无 Spring 依赖；{@code maxLength} / 白名单由调用方（配置层）传入，
 * 可独立单测。null / 空入参 ⇒ 返回空列表，不抛异常（best-effort，与 {@code VisibleToolCallbacks}
 * 降级口径一致）。</p>
 */
public final class ToolCatalogAssembler {

    /** 摘要省略标记（追加在被截断描述的末尾，提示详情可按需取）。 */
    private static final String ELLIPSIS = "…";

    private ToolCatalogAssembler() {
    }

    /**
     * 把 resolve 生效结果装配为目录形态。
     *
     * @param resolved     {@code ToolRegistry.resolve} 的结果（生效形态；null / 空 ⇒ 空列表）
     * @param maxLength    摘要封顶字符数（&lt;=0 视为不截断，与配置缺省语义对齐）
     * @param fullDefTools 白名单工具名（保留完整描述；null ⇒ 视为空）
     * @return 目录条目列表（元素数 = 入参非空元素数，顺序不变；每个 {@code description}
     *         或为原样全文（白名单 / 短描述），或为摘要（截断 + 省略标记））
     */
    public static List<ToolDefinition> assemble(List<ToolDefinition> resolved,
                                                int maxLength, Set<String> fullDefTools) {
        if (resolved == null || resolved.isEmpty()) {
            return List.of();
        }
        List<ToolDefinition> catalog = new ArrayList<>(resolved.size());
        for (ToolDefinition definition : resolved) {
            if (definition == null) {
                continue;
            }
            String description = definition.description();
            if (shouldTruncate(description, maxLength) && !isWhitelisted(fullDefTools, definition.name())) {
                description = truncate(description, maxLength);
            }
            catalog.add(new ToolDefinition(definition.name(), description));
        }
        return catalog;
    }

    /** 白名单校验（null 安全）：名单内工具保留完整描述。 */
    private static boolean isWhitelisted(Set<String> fullDefTools, String name) {
        return fullDefTools != null && name != null && fullDefTools.contains(name);
    }

    /** 是否应截断：描述非空且**码点数**超过封顶（maxLength &gt; 0 才生效；按码点与截断侧同口径）。 */
    private static boolean shouldTruncate(String description, int maxLength) {
        return description != null && !description.isBlank()
                && maxLength > 0 && description.codePointCount(0, description.length()) > maxLength;
    }

    /**
     * 截断到 {@code maxLength} 字符 + 省略标记。
     *
     * <p><b>按 UTF-16 码点安全截断</b>（不截断代理对）：先按 {@code codePointCount} 把
     * {@code maxLength} 换算成 {@code char} 偏移，再在偏移处回退到「词边界」——
     * 仅当截断点前后都是 <b>ASCII 词字符</b>（拉丁字母 / 数字 / 下划线）才向前回退到最近分隔符
     * （服务「不截拉丁词中间」，对齐 DSH 英文目录语义）；CJK 等无空格分词的语言不做回退，
     * 在封顶处硬截断——即便回退也只会在省略标记前丢掉更多原文，可读性收益为零。</p>
     *
     * <p>「截断不截词」的对齐对象：DSH {@code catalogDescriptionMaxLength} 的语义是「目录本身
     * 不能变成新负担」——封顶是硬约束，词边界回退只是让截断点可读性更好，不是软封顶。</p>
     */
    private static String truncate(String description, int maxLength) {
        // 码点封顶（防代理对截断）；码点数不足 maxLength 时以实际为准（不会越界）
        int codePointCount = description.codePointCount(0, description.length());
        int charOffset = description.offsetByCodePoints(0, Math.min(maxLength, codePointCount));
        int cut = charOffset;
        // 词边界回退：截断点两侧都是词字符时，向前回退到最近非词字符之后
        if (isWordChar(description, cut - 1) && isWordChar(description, cut)) {
            int back = cut - 1;
            while (back > 0 && isWordChar(description, back)) {
                back--;
            }
            cut = Math.max(back + 1, 0);
        }
        return description.substring(0, cut) + ELLIPSIS;
    }

    /** 某 char 偏移处是否词字符（越界视为 false；仅 ASCII 词字符参与边界回退）。 */
    private static boolean isWordChar(String text, int index) {
        if (index < 0 || index >= text.length()) {
            return false;
        }
        char c = text.charAt(index);
        return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')
                || (c >= '0' && c <= '9') || c == '_';
    }
}