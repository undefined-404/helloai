package com.helloai.core.shared.util;

/**
 * 文本注入的安全截断工具（P-1 防御层）：按行边界回退截断。
 *
 * <p>背景：tku-e2e-01 事故中，硬切（{@code substring(0, limit)}）把上游产出的
 * URL/清单拦腰截断，且消费方无法判断「哪里被切断、丢了什么」。本工具将截点回退
 * 到最近换行处（回退窗口 {@link #LINE_BOUNDARY_LOOKBACK} 字符内），保证输出以
 * 完整行收尾；找不到换行时按字符硬切（保底不抛、不丢字符数语义）。</p>
 *
 * <p>纯静态工具：无 Spring、无业务实体依赖，执行 / 核验 / 报告域可安全引用。</p>
 */
public final class TextTruncator {

    /** 行边界回退最大窗口：截点向前最多回退这么多字符找换行；窗口内找不到则按字符硬切。 */
    public static final int LINE_BOUNDARY_LOOKBACK = 512;

    private TextTruncator() {
    }

    /**
     * 按行边界截断：截点从 {@code maxChars} 处向前查找最近换行（回退不超过
     * {@link #LINE_BOUNDARY_LOOKBACK} 字符）——找到则截到换行前（该行完整保留前文），
     * 找不到则硬切到 {@code maxChars}。文本未超限时原样返回。
     *
     * @param text     原文（可为 null，null 原样返回）
     * @param maxChars 字符数上限（≤0 时原样返回）
     */
    public static String truncateAtLineBoundary(String text, int maxChars) {
        if (text == null || maxChars <= 0 || text.length() <= maxChars) {
            return text;
        }
        int floor = Math.max(0, maxChars - LINE_BOUNDARY_LOOKBACK);
        int newline = text.lastIndexOf('\n', maxChars - 1);
        return newline >= floor ? text.substring(0, newline) : text.substring(0, maxChars);
    }
}
