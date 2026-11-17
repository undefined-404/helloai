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

    /** 头尾截断时尾部预算的分母（尾部至少保留 {@code maxChars / 4}，保 EXECUTION_RECORD 尾部块）。 */
    public static final int HEAD_TAIL_TAIL_DIVISOR = 4;

    /** 头尾截断的省略标记预留字符数（计入预算，保证结果总长不超 {@code maxChars}）。 */
    private static final int OMIT_MARKER_RESERVE = 48;

    private TextTruncator() {
    }

    /**
     * 头 + 尾双向保留的截断：中间超限部分替换为「省略 N 字符」标记。
     *
     * <p><b>为什么不能只用 {@link #truncateAtLineBoundary}</b>：那是最小改动做法
     * {@code substring(0, maxChars)} 的行边界版本，只保头。执行产出协议把
     * {@code EXECUTION_RECORD} 回填块刻意放在<b>尾部</b>（方案3 {@code displayText} 约定，
     * 见 {@code ExecutionOutputParser}），只保头会把尾部块整段切掉，等于自废既有协议。
     * 故写侧截断必须双向：头按行边界、尾按行边界、中间打标。</p>
     *
     * <p>尾部预算 = {@code maxChars / }{@link #HEAD_TAIL_TAIL_DIVISOR}，头部预算 = 余量扣掉
     * 标记预留。文本未超限时原样返回；{@code maxChars} 过小（不足以容纳标记 + 头 + 尾）时
     * 退回 {@link #truncateAtLineBoundary}，保证不抛、不产生比原文更长的结果。</p>
     *
     * @param text     原文（null 原样返回）
     * @param maxChars 字符数上限（≤0 时原样返回）
     */
    public static String truncateHeadTail(String text, int maxChars) {
        if (text == null || maxChars <= 0 || text.length() <= maxChars) {
            return text;
        }
        if (maxChars <= OMIT_MARKER_RESERVE * 2) {
            return truncateAtLineBoundary(text, maxChars);
        }
        int tailBudget = Math.max(1, maxChars / HEAD_TAIL_TAIL_DIVISOR);
        int headBudget = Math.max(1, maxChars - tailBudget - OMIT_MARKER_RESERVE);
        String head = truncateAtLineBoundary(text, headBudget);
        // 尾段起点按行边界<b>向后</b>对齐：宁可少留一行，也不让尾部从半行开始
        int tailStart = Math.max(head.length(), text.length() - tailBudget);
        int newline = text.indexOf('\n', tailStart);
        if (newline >= 0 && newline + 1 < text.length()) {
            tailStart = newline + 1;
        }
        tailStart = Math.max(tailStart, head.length());
        String tail = text.substring(tailStart);
        int omitted = text.length() - head.length() - tail.length();
        if (omitted <= 0) {
            return text;
        }
        return head + "\n…（中间省略 " + omitted + " 字符）…\n" + tail;
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
