package com.helloai.core.planner.clarify;

/**
 * 附件意图三态（`ATT-5`）：本轮提问与「已上传附件」的关系。
 *
 * <p>回答两个问题，且两者都由模型在联合决策里判定：</p>
 * <ul>
 *   <li><b>要不要读附件正文</b>——{@link #NONE} 时本轮不注入附件正文（只保留清单），
 *       避免「问天气也带上 200k 手册」把上下文顶爆；</li>
 *   <li><b>要不要联网</b>——{@link #ANSWERABLE} 时附件本身能答，否决 LLM 的
 *       {@code need_search=true}（附件优先于外部检索）；{@link #NEEDS_EXTERNAL}
 *       时才进「分析后检索」链路（{@code ATT-6}）。</li>
 * </ul>
 *
 * <p><b>为什么是 null 而不是默认值</b>：{@code null} 表示「模型未判定」——旧模板、
 * 解析失败、字段缺失都落在这里，消费方按改造前的行为处理（有附件就注入、按
 * {@code need_search} 决定是否联网）。这是契约变更的向后兼容位：新模板生效前
 * 行为零变化，不需要灰度开关。</p>
 *
 * <p><b>为什么不是枚举序列化到 JSON</b>：LLM 输出的是小写下划线字符串（模板约束），
 * 解析层用 {@link #fromWire(String)} 做唯一映射点；未知值不抛异常（决策解析对未知
 * 枚举值一贯宽容），只记 WARN 并回落到 {@code null}。</p>
 */
public enum AttachmentIntent {

    /** 本轮不涉及附件：不注入附件正文，联网仍按 {@code need_search} 走。 */
    NONE("none"),

    /** 附件本身能答：不联网，基于附件作答。 */
    ANSWERABLE("answerable"),

    /** 附件需外部核验：进「分析后检索」（检索词由附件里的外部锚点构成）。 */
    NEEDS_EXTERNAL("needs_external");

    private final String wireValue;

    AttachmentIntent(String wireValue) {
        this.wireValue = wireValue;
    }

    /** 模板里约定的取值（小写下划线）。 */
    public String wireValue() {
        return wireValue;
    }

    /**
     * 从模板输出解析；缺失、空白、非文本、未知值一律返回 {@code null}（= 未判定）。
     *
     * <p>不抛异常是刻意的：调用方 {@link ChatRoundDecisionParser} 对未知枚举值一贯
     * 宽容处理，避免一个拼写错误就把整轮决策降级为 {@code defaults()}。</p>
     *
     * @param value 模板原始值（可为 null）
     * @return 命中值；未命中返回 null
     */
    public static AttachmentIntent fromWire(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String normalized = value.trim();
        for (AttachmentIntent intent : values()) {
            if (intent.wireValue.equals(normalized)) {
                return intent;
            }
        }
        return null;
    }
}
