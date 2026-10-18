package com.helloai.core.shared.util;

/**
 * LLM 输出 JSON 的容错修复工具。
 *
 * <p>LLM 经常把 Windows 路径（如 {@code E:\workspace}）原样放进 JSON 字符串值，
 * 反斜杠未按 JSON 规范转义成 {@code \\}，Jackson 严格解析遇到 {@code \w} 这类
 * 非法转义直接抛 "Unrecognized character escape"。本工具在解析前做一次预修复：
 * 字符串值内的非法转义补成字面反斜杠（{@code \w → \\w}），路径内容不丢失；
 * 合法转义（{@code \" \\ \/ \b \f \n \r \t} 及 unicode 转义：反斜杠 + u + 4 位十六进制）原样保留。</p>
 *
 * <p>与各服务内 stripToJsonObject（剥围栏/截取花括号）配合使用：先 strip 再 fix。</p>
 */
public final class LlmJsonSanitizer {

    private LlmJsonSanitizer() {
    }

    /**
     * 修复 JSON 字符串值内的非法反斜杠转义。
     *
     * @param json 剥离围栏后的 JSON 文本（可为 null）
     * @return 修复后的 JSON 文本；无反斜杠时原样返回
     */
    public static String fixInvalidEscapes(String json) {
        if (json == null || json.indexOf('\\') < 0) {
            return json;
        }
        StringBuilder sb = new StringBuilder(json.length() + 16);
        boolean inString = false;
        for (int i = 0; i < json.length(); i++) {
            char c = json.charAt(i);
            if (c == '"') {
                inString = !inString;
                sb.append(c);
                continue;
            }
            if (inString && c == '\\') {
                char next = i + 1 < json.length() ? json.charAt(i + 1) : '\0';
                if (next == '"' || next == '\\' || next == '/' || next == 'b'
                        || next == 'f' || next == 'n' || next == 'r' || next == 't') {
                    sb.append(c).append(next);
                    i++;
                    continue;
                }
                if (next == 'u' && isHex4(json, i + 2)) {
                    sb.append(json, i, i + 6);
                    i += 5;
                    continue;
                }
                // 非法转义：补成字面反斜杠，后续字符按普通字符处理
                sb.append("\\\\");
                continue;
            }
            sb.append(c);
        }
        return sb.toString();
    }

    /** 判断 from 起是否有连续 4 个十六进制字符（unicode 转义合法性）。 */
    private static boolean isHex4(String s, int from) {
        if (from + 4 > s.length()) {
            return false;
        }
        for (int i = from; i < from + 4; i++) {
            char c = s.charAt(i);
            boolean hex = (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F');
            if (!hex) {
                return false;
            }
        }
        return true;
    }

    /**
     * 修复「字符串内部裸写」导致的 JSON 结构错乱（{@link #fixInvalidEscapes} 的第二级修复）。
     *
     * <p><b>要解决的实测故障</b>（2026-10-18）：LLM 在 {@code description} / {@code message}
     * 里写了未转义的英文双引号（或裸换行），字符串提前结束，Jackson 报
     * {@code Unexpected character ('下'): was expecting comma to separate Object entries}。
     * 这类输出**内容是完整的**，只是不符合 JSON 转义规范——直接抛错等于让用户白等一次
     * 完整 LLM 调用，必须尽量救回来。</p>
     *
     * <p><b>启发式判据（只在严格解析失败后才调用，因此可以激进一点）</b>：</p>
     * <ol>
     *   <li>字符串内的裸控制字符（{@code \n \r \t} 及 U+0000~U+001F）→ 转义为 {@code \n} 等；</li>
     *   <li>字符串内遇到 {@code "}：向后跳过空白看第一个字符——
     *       是 {@code ,} {@code }} {@code ]} {@code :} ⇒ 判定为**结构结束引号**，原样保留；
     *       否则判定为**内容引号**，转义成 {@code \"}。</li>
     * </ol>
     *
     * <p><b>已知边界</b>：内容里恰好出现 {@code "…",} 形态（引号后紧跟英文逗号）会被误判成
     * 结构结束引号。该形态在中文技术文本里罕见，且本方法只在严格解析已失败时兜底使用。</p>
     *
     * @param json 已过 {@link #fixInvalidEscapes} 的 JSON 文本（可为 null）
     * @return 修复后的文本；无法改进时原样返回（调用方据「是否变化」决定是否重试）
     */
    public static String repairBrokenStrings(String json) {
        if (json == null || json.isEmpty()) {
            return json;
        }
        StringBuilder out = new StringBuilder(json.length() + 32);
        boolean inString = false;
        for (int i = 0; i < json.length(); i++) {
            char c = json.charAt(i);
            if (!inString) {
                if (c == '"') {
                    inString = true;
                }
                out.append(c);
                continue;
            }
            // ---- 以下为「字符串内部」 ----
            if (c == '\\') {
                // 已是转义序列（fixInvalidEscapes 保证合法），整体搬运两字符
                out.append(c);
                if (i + 1 < json.length()) {
                    out.append(json.charAt(++i));
                }
                continue;
            }
            if (c == '"') {
                if (isStringTerminator(json, i)) {
                    inString = false;
                    out.append(c);
                } else {
                    out.append("\\\"");
                }
                continue;
            }
            switch (c) {
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> {
                    if (c < 0x20) {
                        out.append(String.format("\\u%04x", (int) c));
                    } else {
                        out.append(c);
                    }
                }
            }
        }
        return out.toString();
    }

    /**
     * 判断 {@code quoteIndex} 处的双引号是否为字符串**结束**引号：
     * 向后跳过空白与（可选的）换行，第一个非空白字符 ∈ {@code , } ] :} 即为结束。
     */
    private static boolean isStringTerminator(String json, int quoteIndex) {
        for (int i = quoteIndex + 1; i < json.length(); i++) {
            char c = json.charAt(i);
            if (c == ' ' || c == '\t' || c == '\r' || c == '\n') {
                continue;
            }
            return c == ',' || c == '}' || c == ']' || c == ':';
        }
        // 到末尾都没内容（截断输出）：当作未结束，转义更安全
        return false;
    }
}
