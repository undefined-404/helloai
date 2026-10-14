package com.helloai.core.shared.util;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 批量录入的 API Key 文本拆分器（`M-7`）。
 *
 * <p>移植 magpie {@code internal/provider/keys.go:165-178} 的 {@code SplitKeys}：一次粘贴多把 Key
 * ⇒ 按分隔符拆列 ⇒ 两端剥引号 ⇒ 丢弃空段 ⇒ **去重且保序**。</p>
 *
 * <p><b>分隔符</b>（与 magpie 逐字对齐）：半角逗号 {@code ,}、全角逗号 {@code ，}、半角分号 {@code ;}、
 * 全角分号 {@code ；}、半角空格、制表符、换行 {@code \n} / 回车 {@code \r}、全角空格 {@code \u3000}。
 * 连续分隔符产生空段并被丢弃（等价于 magpie {@code strings.FieldsFunc} 的"合并分隔符"语义）。</p>
 *
 * <p><b>剥引号</b>：每段**两端**剥掉 {@code "} / {@code '} / {@code `}（对应 magpie
 * {@code strings.Trim(f, "\"'`")}）；**段内部**的引号原样保留。</p>
 *
 * <p><b>★本类刻意不做的事</b>：不做 trim、不做大小写归一、不做形态校验。Key 明文将原样交给
 * 加密与指纹计算，任何"顺手归一"都会让指纹与密文描述的不是同一份字节 ——
 * 见 {@code com.helloai.core.system.crypto.CredentialFingerprint#of} 的「不做 trim」判据。</p>
 */
public final class KeyTextSplitter {

    /** 两端待剥的引号字符集合。 */
    private static final String QUOTES = "\"'`";

    private KeyTextSplitter() {
    }

    /**
     * 拆分并去重。
     *
     * @param text 粘贴的原始文本（可为 {@code null}）
     * @return 去重后**保序**的 Key 列表；{@code null} / 全空白输入返回空列表
     */
    public static List<String> split(String text) {
        if (text == null || text.isBlank()) {
            return List.of();
        }
        Map<String, Boolean> seen = new LinkedHashMap<>();
        StringBuilder field = new StringBuilder();
        int length = text.length();
        // i == length 时补一个分隔符哨兵，使最后一段无需额外分支
        for (int i = 0; i <= length; i++) {
            char c = i == length ? ' ' : text.charAt(i);
            if (isSeparator(c)) {
                collect(field, seen);
                field.setLength(0);
            } else {
                field.append(c);
            }
        }
        return new ArrayList<>(seen.keySet());
    }

    /** 收下一段：剥引号 → 非空才去重登记（{@link LinkedHashMap} 保证首次出现顺序）。 */
    private static void collect(StringBuilder field, Map<String, Boolean> seen) {
        if (field.length() == 0) {
            return;
        }
        String candidate = trimQuotes(field.toString());
        if (!candidate.isEmpty()) {
            seen.putIfAbsent(candidate, Boolean.TRUE);
        }
    }

    /** 仅剥**两端**引号，不动段内部。 */
    private static String trimQuotes(String raw) {
        int start = 0;
        int end = raw.length();
        while (start < end && QUOTES.indexOf(raw.charAt(start)) >= 0) {
            start++;
        }
        while (end > start && QUOTES.indexOf(raw.charAt(end - 1)) >= 0) {
            end--;
        }
        return raw.substring(start, end);
    }

    /** 分隔符谓词，与 magpie {@code strings.FieldsFunc} 的回调逐字对齐。 */
    private static boolean isSeparator(char c) {
        return c == ',' || c == ';' || c == '，' || c == '；'
                || c == ' ' || c == '\t' || c == '\n' || c == '\r' || c == '\u3000';
    }
}
