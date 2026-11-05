package com.helloai.core.review.support;

import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CharsetEncoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * 交付正文编码损坏检测器（`RV-7`，纯函数、零 token、无 Spring 依赖）。
 *
 * <p><b>为什么需要</b>：`LOG-20261105-008` 真机样本中，执行侧把 UTF-8 字节按 GBK 解码后
 * 再以 UTF-8 存库（`瀹炴祴` 类双重解码串），reviewer 看见了但按「格式瑕疵」判 nit，
 * 最终 `APPROVED`。验收标准没写编码维度时，判定面就永远拦不住——所以补一条<b>确定性</b>
 * 观测：不经模型判断，直接在交付正文里找三类可机读证据。</p>
 *
 * <p><b>判定口径（`Warn` 档）</b>：只产 finding，不改判定、不驳回、不落状态。理由与本计划
 * `RV-1` 一致——真实语料尚未证明零误伤（私用区字符可能来自图标字体，短拉丁串可能来自
 * 人名），先观测再议升档。</p>
 *
 * <p><b>三类信号</b>：</p>
 * <ol>
 *     <li><b>U+FFFD</b>：解码失败的替换字符，出现即说明上游字节流已被破坏；</li>
 *     <li><b>私用区字符</b>（U+E000~U+F8FF）：GBK 解码 UTF-8 字节时常见的落点区间，
 *         真机样本含 U+E045；</li>
 *     <li><b>双重解码片段</b>：把连续汉字/拉丁扩展串按「GBK / Windows-1252 编码 → UTF-8 严格解码」
 *         回推，若得到可读且含汉字的原文，说明该串本身就是「UTF-8 字节被单字节/双字节解码」
 *         的产物。回推使用严格解码器（任何非法字节即放弃），误报只在概率极低的巧合字节序上发生。</li>
 * </ol>
 *
 * <p><b>不做</b>：不判断「乱码是否影响验收标准」——那是 reviewer 的活；不修改正文、不清洗输出。</p>
 */
public final class ReviewEncodingDetector {

    /** finding 稳定代码（前端 / 统计按此聚合，不改文案）。 */
    public static final String OUTPUT_ENCODING_CORRUPTED = "output_encoding_corrupted";

    /** Unicode 替换字符。 */
    private static final char REPLACEMENT = '\uFFFD';

    /** GBK：双重解码最常见的上游单字节集（中文 Windows 默认 CP936）。 */
    private static final Charset GBK = Charset.forName("GBK");

    /** Windows-1252：UTF-8 字节按西文代码页解码后的典型产物（`ä¸æ–‡`）。 */
    private static final Charset WINDOWS_1252 = Charset.forName("windows-1252");

    /** 单条 detail 字符上限（与 {@link ReviewVerdictValidator} 同口径）。 */
    private static final int MAX_DETAIL_CHARS = 200;

    /** detail 里最多列出的回推示例数。 */
    private static final int MAX_SAMPLES = 3;

    /** 示例串最大展示字符数。 */
    private static final int SAMPLE_CHARS = 24;

    /**
     * 汉字连续段最短长度。取 3 而非 2：2 个汉字的 GBK 字节恰好构成合法 UTF-8 的概率约 4e-5/段，
     * 万字级文档会有可观误报；3 个汉字（6 字节）降到 1e-7 量级，同时仍能命中真机
     * `瀹炴祴`（4 字）与更长乱码段。单字乱码段漏检，由替换字符 / 私用区信号兜底。
     */
    private static final int MIN_CJK_RUN = 3;

    /** 拉丁扩展连续段最短长度：西文乱码通常成串出现，3 字符起判避免把「Málaga」类误伤。 */
    private static final int MIN_LATIN_RUN = 3;

    private ReviewEncodingDetector() {
    }

    /**
     * 检测结论。
     *
     * @param replacementChars U+FFFD 出现次数
     * @param privateUseChars  私用区字符出现次数
     * @param doubleDecodeRuns 可回推的双重解码片段数
     * @param samples          回推示例（`乱码→原文`，最多 {@value #MAX_SAMPLES} 条）
     */
    public record Result(int replacementChars, int privateUseChars, int doubleDecodeRuns, List<String> samples) {

        public Result {
            samples = samples == null ? List.of() : List.copyOf(samples);
        }

        /** 是否命中任一编码损坏信号。 */
        public boolean corrupted() {
            return replacementChars > 0 || privateUseChars > 0 || doubleDecodeRuns > 0;
        }

        /** 人读说明（已截断，可直接进 timeline payload）。 */
        public String detail() {
            StringBuilder sb = new StringBuilder("交付正文疑似编码损坏");
            if (replacementChars > 0) {
                sb.append("；替换字符(U+FFFD) ").append(replacementChars).append(" 处");
            }
            if (privateUseChars > 0) {
                sb.append("；私用区字符 ").append(privateUseChars).append(" 处");
            }
            if (doubleDecodeRuns > 0) {
                sb.append("；双重解码片段 ").append(doubleDecodeRuns).append(" 处");
            }
            if (!samples.isEmpty()) {
                sb.append("（示例：").append(String.join("，", samples)).append("）");
            }
            return sb.length() > MAX_DETAIL_CHARS ? sb.substring(0, MAX_DETAIL_CHARS - 1) + "…" : sb.toString();
        }
    }

    /**
     * 检测文本中的编码损坏信号。
     *
     * @param text 交付正文（可空）；建议传「执行产出 + 附件正文」拼接
     * @return 检测结论；空文本返回全零结果
     */
    public static Result detect(String text) {
        if (text == null || text.isEmpty()) {
            return new Result(0, 0, 0, List.of());
        }
        int replacementChars = 0;
        int privateUseChars = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == REPLACEMENT) {
                replacementChars++;
            } else if (isPrivateUse(c)) {
                privateUseChars++;
            }
        }

        int runs = 0;
        List<String> samples = new ArrayList<>();
        int i = 0;
        while (i < text.length()) {
            char c = text.charAt(i);
            int end;
            if (isCjk(c)) {
                end = i;
                while (end < text.length() && isCjk(text.charAt(end))) {
                    end++;
                }
                if (end - i >= MIN_CJK_RUN) {
                    String token = text.substring(i, end);
                    String recovered = roundTrip(token, GBK);
                    if (isRecovered(token, recovered)) {
                        runs++;
                        addSample(samples, token, recovered);
                    }
                }
            } else if (isLatinMojibakeCandidate(c)) {
                end = i;
                while (end < text.length() && isLatinMojibakeCandidate(text.charAt(end))) {
                    end++;
                }
                if (end - i >= MIN_LATIN_RUN) {
                    String token = text.substring(i, end);
                    String recovered = roundTrip(token, WINDOWS_1252);
                    if (isRecovered(token, recovered)) {
                        runs++;
                        addSample(samples, token, recovered);
                    }
                }
            } else {
                i++;
                continue;
            }
            i = end;
        }
        return new Result(replacementChars, privateUseChars, runs, samples);
    }

    /** 回推结果是否构成「原文」：非空、含汉字、与输入不同且自身不再带损坏信号。 */
    private static boolean isRecovered(String token, String recovered) {
        if (recovered == null || recovered.isBlank() || recovered.equals(token)) {
            return false;
        }
        boolean hasCjk = false;
        for (int i = 0; i < recovered.length(); i++) {
            char c = recovered.charAt(i);
            if (c == REPLACEMENT || isPrivateUse(c)) {
                return false;
            }
            if (isCjk(c)) {
                hasCjk = true;
            }
        }
        return hasCjk;
    }

    /** 按指定单字节/双字节集编码后，用严格 UTF-8 解码回推原文；任何非法字节即放弃。 */
    private static String roundTrip(String token, Charset charset) {
        try {
            CharsetEncoder encoder = charset.newEncoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT);
            ByteBuffer bytes = encoder.encode(CharBuffer.wrap(token));
            CharsetDecoder decoder = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT);
            return decoder.decode(bytes).toString();
        } catch (CharacterCodingException | UnsupportedOperationException e) {
            return null;
        }
    }

    private static void addSample(List<String> samples, String token, String recovered) {
        if (samples.size() >= MAX_SAMPLES) {
            return;
        }
        samples.add(shrink(token) + "→" + shrink(recovered));
    }

    private static String shrink(String text) {
        return text.length() <= SAMPLE_CHARS ? text : text.substring(0, SAMPLE_CHARS) + "…";
    }

    /** CJK 统一表意文字（基本区 + 扩展 A）：双重解码串的主要落点。 */
    private static boolean isCjk(char c) {
        return (c >= '\u4E00' && c <= '\u9FFF') || (c >= '\u3400' && c <= '\u4DBF');
    }

    /** 私用区（BMP）：图标字体与 GBK 误码都会落在这里，按 `RV-7` 判据计入。 */
    private static boolean isPrivateUse(char c) {
        return c >= '\uE000' && c <= '\uF8FF';
    }

    /**
     * 西文双重解码候选字符：Latin-1 可打印区 + Windows-1252 的 C1 映射字符
     * （`æ–‡` 里的 `–` 即 U+2013）。普通中英文正文不会连续出现 3 个此类字符。
     */
    private static boolean isLatinMojibakeCandidate(char c) {
        if (c >= '\u00A0' && c <= '\u00FF') {
            return true;
        }
        return switch (c) {
            case '\u0152', '\u0153', '\u0160', '\u0161', '\u0178', '\u017D', '\u017E', '\u0192',
                 '\u02C6', '\u02DC', '\u2013', '\u2014', '\u201A', '\u201C', '\u201D', '\u201E',
                 '\u2020', '\u2021', '\u2022', '\u2026', '\u2030', '\u2039', '\u203A', '\u20AC', '\u2122' -> true;
            default -> false;
        };
    }
}