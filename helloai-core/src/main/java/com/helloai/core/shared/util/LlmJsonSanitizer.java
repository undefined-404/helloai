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
}
