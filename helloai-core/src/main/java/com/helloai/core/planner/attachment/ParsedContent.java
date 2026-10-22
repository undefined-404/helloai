package com.helloai.core.planner.attachment;

/**
 * 解析结果。
 *
 * @param text      正文（文本族为原文；结构化格式为 Markdown）；无正文时为 null
 * @param chars     正文字符数（截断后）
 * @param pageCount 页数（PDF/PPT 可读到时），未知为 null
 * @param parser    解析器标识：{@code text} / {@code tika}
 * @param charset   文本族判码结果（UTF-8 / GB18030 / ...），结构化格式为 null
 * @param truncated 是否因超限额被截断（清单里要显式标注，避免模型以为全文就这么多）
 */
public record ParsedContent(String text, int chars, Integer pageCount, String parser,
                            String charset, boolean truncated) {

    /** 文本族直读结果。 */
    public static ParsedContent ofText(String text, String charset, boolean truncated) {
        return new ParsedContent(text, text == null ? 0 : text.length(), null, "text", charset, truncated);
    }

    /** 结构化格式解析结果。 */
    public static ParsedContent ofTika(String text, Integer pageCount, boolean truncated) {
        return new ParsedContent(text, text == null ? 0 : text.length(), pageCount, "tika", null, truncated);
    }
}
