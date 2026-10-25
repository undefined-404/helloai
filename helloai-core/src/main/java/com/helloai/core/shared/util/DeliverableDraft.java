package com.helloai.core.shared.util;

/**
 * 「草稿是否构成<b>可交付正文</b>」的判据（唯一事实源，2026-10-25）。
 *
 * <p><b>为什么需要单独一个类</b>：这条判据曾以两处独立实现存在——{@code FinalReportFenceHook}
 * 判断「耗尽时草稿值不值得救」、{@code LocalExecutionCommandConsumer} 判断「turn 末要不要用
 * 沙箱工作区的文件顶替对话正文」。同一事实双写必分叉（工程纪律判据 ④）：一处把门槛调成 200、
 * 另一处还是 1500，就会出现「一边回传、一边又判定不值得救」的自相矛盾。故收敛到本类。</p>
 *
 * <p><b>判据形状（为什么是「或」而不是「且」）</b>：</p>
 * <ul>
 *   <li><b>有 Markdown 骨架 ⇒ 可交付</b>：实测「# 手册 - 最终整合报告\n\n## 执行摘要…」剔除空白
 *       后仅 30 余字符，但它<b>确实</b>是交付物的开头（2026-10-16 事故：完整草稿被收尾轮顶替）。
 *       标题标记是「草稿已有交付物骨架」的最廉价信号。</li>
 *   <li><b>篇幅达标 ⇒ 可交付</b>：无骨架但超过 {@value #MIN_BODY_CHARS} 字符的纯文本，
 *       亦视为正文（例如验收要求就是「一段连贯说明」而无须小标题）。</li>
 *   <li>两者皆无 ⇒ <b>正文不在草稿区</b>：实测 MiniMax 把 20 轮全花在沙箱里写文件，
 *       对话区只剩逐轮进度叙述（「已压缩至 3022 字」之类，几百字符、无 {@code #} 骨架），
 *       而 3008 字真正文仍在 {@code /workspace/part1.md}——这种形态必须判「不可交付」，
 *       否则兜底救不到、回传也不会触发（`LOG-20261025-007` / `-009`）。</li>
 * </ul>
 *
 * <p><b>判错方向必须保守</b>：宁可把「真近空白但恰好含一个 #」判成可交付（结果 = 兜底不触发、
 * 围栏缺失但不丢内容），也不把真交付物误判成不可交付（结果 = 完整草稿被顶替，属事故回归）。</p>
 */
public final class DeliverableDraft {

    /**
     * 无骨架草稿的篇幅门槛（字符，去空白）。
     *
     * <p>取 1500 的理由：文档类子任务验收下限普遍 ≥1800 字（MiniMax 事故验收 1800~2300），
     * 草稿若有 1500+ 字符<b>且无骨架</b>，仍可能是真正文（纯段落式交付）；低于此值基本只能是
     * 进度叙述 / 开场白。远大于「近空白」阈值（200），故不会与近空白判据打架。</p>
     */
    public static final int MIN_BODY_CHARS = 1500;

    private DeliverableDraft() {
    }

    /**
     * 草稿是否已具备「交付物骨架」：行首 Markdown 标题 / 列表符号 / 表格 / 代码块分隔线。
     *
     * @param text 草稿正文（{@code null} ⇒ false）
     * @return 是否含骨架标记
     */
    public static boolean looksLikeDraftBody(String text) {
        if (text == null) {
            return false;
        }
        for (String line : text.split("\\R")) {
            String trimmed = line.trim();
            if (trimmed.startsWith("#")             // Markdown 标题 / H1-H6
                    || trimmed.startsWith("- ")     // 无序列表项
                    || trimmed.startsWith("* ")     // 无序列表项（备选符号）
                    || trimmed.startsWith("|")      // 表格行
                    || trimmed.startsWith("```")) { // 代码块分隔线
                return true;
            }
        }
        return false;
    }

    /**
     * 文本（对话正文 / 沙箱文件）是否构成<b>可交付正文</b>：有骨架 <b>或</b> 篇幅达标。
     *
     * <p><b>★判定必须先剥掉自检围栏</b>（2026-10-25 实测，`review_record` 三轮驳回原文）：
     * MiniMax 的假成功交付物形状是「几行过程自述 + {@code VERIFICATION:} + {@code - 核实说明：…}
     * + {@code STATUS: BLOCKED}」。围栏里的 {@code - 核实说明} 是<b>无序列表项</b>，若不剥围栏
     * 就会被 {@link #looksLikeDraftBody} 判成「有骨架 ⇒ 可交付」，兜底与回传双双失灵——
     * 判据再次「判错对象」（自审痕迹不是正文）。剔除围栏后的正文才是交付物本体，
     * 篇幅统计同理（{@code STATUS}/{@code NEXT_STEPS} 的字符不该顶字数）。</p>
     *
     * @param text 待判文本（{@code null} / 空白 ⇒ false）
     * @return 是否可交付
     */
    public static boolean isDeliverable(String text) {
        String body = body(text);
        if (body.isBlank()) {
            return false;
        }
        return looksLikeDraftBody(body) || compact(body).length() >= MIN_BODY_CHARS;
    }

    /**
     * 交付物本体：剔除<b>结尾处的自检围栏段</b>（{@code VERIFICATION:} 起至末尾）。
     *
     * <p>无标记时原样返回；围栏后还有内容（正文里正经讨论围栏协议）时也不剥——
     * 用「末个标记之后是否只剩围栏样内容」这条弱判据即可，方向保守：宁可多留不算错字数。</p>
     */
    public static String body(String text) {
        if (text == null) {
            return "";
        }
        int idx = text.lastIndexOf(VerificationFence.MARKER);
        if (idx < 0) {
            return text;
        }
        String tail = text.substring(idx);
        // 末段「像围栏」= 含 STATUS 标记（弱判据，刻意不用正则：围栏行有粗体/列表/引用十几种写法，
        // 逐个匹配只会越写越脆）。不剥时方向保守——围栏字符被算进正文，最多让「可交付」偏宽。
        boolean tailIsFence = tail.toUpperCase(java.util.Locale.ROOT).contains("STATUS");
        return tailIsFence ? text.substring(0, idx) : text;
    }

    /** 剔除全部空白字符（空白无关比对 / 篇幅统计用；{@code null} ⇒ 空串）。 */
    public static String compact(String s) {
        if (s == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (!Character.isWhitespace(c)) {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    /** 交付物本体的篇幅（去空白字符数，已剔围栏）—— 回传「比现状更丰富」判据用。 */
    public static int bodyChars(String text) {
        return compact(body(text)).length();
    }
}
