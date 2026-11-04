package com.helloai.core.review.support;

import java.util.ArrayList;
import java.util.List;

/**
 * 验收标准拆分器（`RV-3`，纯静态、零 Spring 依赖）。
 *
 * <p><b>拆分对象只有 {@code sub_task.acceptance}</b>（子任务级验收标准），不是任务级
 * {@code package.acceptanceCriteria}——后者粒度粗、与单条子任务判定不同源（实施计划 §2.3 事实 22）。</p>
 *
 * <p><b>分隔符优先级按真机数据定</b>（实施计划 §4 决策 D2）：</p>
 * <ol>
 *     <li>圈号 {@code ①~⑳}：实测仅 5 条使用，但一旦使用就是<b>作者明确的条目边界</b>，
 *         条目内部出现的 {@code ；} 属句内停顿，不该再拆 ⇒ 圈号命中时只用圈号拆。</li>
 *     <li>全角 {@code ；}：fallback，实测 9 条。</li>
 *     <li>半角 {@code ;}：<b>不参与</b>——真机 0 命中，且它高频出现在代码片段 / 枚举串里
 *         （如 {@code "SELECT a; SELECT b"}），按它拆必然过拆。</li>
 * </ol>
 *
 * <p><b>判错方向</b>：拆分只影响「有几条要核」，覆盖判据用的是子串归属（
 * {@link ReviewVerdictValidator#isGroundedIn}），对分隔符风格不敏感。少拆一条 ⇒ 少核对一条
 * （漏判）；多拆一条 ⇒ 多要求一条覆盖（可由 {@code criterionRef} 命中同一段原文而抵消）。
 * 故取<b>保守</b>：只在有明确边界信号时拆。</p>
 */
public final class AcceptanceItemSplitter {

    /** 单个拆分项长度上限（超长基本是整段未拆，保留原样由覆盖判据兜）。 */
    private static final int MAX_ITEM_CHARS = 500;

    /** 拆分项数量上限（防异常输入产出上百条把 finding 文案撑爆）。 */
    private static final int MAX_ITEMS = 20;

    /** 圈号起始/结束码位（① U+2460 ~ ⑳ U+2473）。 */
    private static final char CIRCLED_FIRST = '①';
    private static final char CIRCLED_LAST = '⑳';

    /**
     * 「后果条款」标记：这类条款不是新的可核验对象，而是对前文的<b>驳回条件/后果声明</b>。
     *
     * <p>真机样本（2026-11-04 本地真实任务）：验收原文末尾常带
     * 「三要素缺任一项、或任一条要点无来源标注，<b>即判不通过</b>。」——按 {@code ；} 拆会把它变成
     * 第 3 条独立验收项，而 reviewer 只会为前两条实质条款写 {@code coverage}，于是
     * 「明明审全了」被判 {@code acceptance_not_covered} 转人工（实测确实发生了一次）。
     * 归并到前一保存留原文，既不新增核对要求，也不丢内容（覆盖归属按子串匹配，两份都能命中）。</p>
     *
     * <p><b>只认「后果」短语，不认「含否定词」</b>：像「缺少结论则驳回」这类条款本身可能是独立的
     * 实质要求，不做泛化归并；此处每条标记都必须能指出「它只是对前文的后果声明」的真机样本。</p>
     */
    private static final List<String> CONSEQUENCE_MARKERS = List.of(
            "即判不通过", "则判不通过", "均判不通过", "一律不通过",
            "否则判不通过", "否则不通过", "视为不通过", "视同不通过", "即不通过");

    /**
     * 「交叉引用括号」标记：形如「（对应任务级验收条目 ②）」的括号只是指回其他编号，
     * 既不是边界也不是验收内容。
     *
     * <p>真机样本（2026-11-04 第二轮）：验收原文为
     * 「结构可数断言：（对应任务级验收条目 ①）① 正文中…；（对应任务级验收条目 ②）② 汉字数…」。
     * 括号里的圈号被当成条目边界 ⇒ 拆出「（对应任务级验收条目」这类残片，reviewer 照抄
     * 「① 正文中…」的 {@code criterionRef} 与残片互不包含 ⇒ 4 条全判未覆盖。</p>
     */
    private static final List<String> CROSS_REF_MARKERS = List.of("对应", "参见", "参考", "详见", "验收条目");

    private AcceptanceItemSplitter() {
    }

    /**
     * 拆分验收标准原文。
     *
     * @param acceptance {@code sub_task.acceptance} 原文（可空）
     * @return 拆分项（去空白、去尾标点；无法拆时整段作为唯一一项；空输入返回空清单）
     */
    public static List<String> split(String acceptance) {
        if (acceptance == null || acceptance.isBlank()) {
            return List.of();
        }
        List<String> items = hasCircledNumber(acceptance)
                ? splitByCircled(acceptance)
                : splitByFullWidthSemicolon(acceptance);
        List<String> normalized = new ArrayList<>(items.size());
        for (String item : items) {
            String cleaned = normalize(item);
            if (!cleaned.isEmpty() && normalized.size() < MAX_ITEMS) {
                normalized.add(cleaned);
            }
        }
        // 拆不出任何有效条目时（如整段以空白构成）返回空清单：没有可核对的验收点，
        // 覆盖批评器据此跳过（不拿「无原文」当「未覆盖」，那是假阳性）。
        List<String> withoutLeadIns = stripLeadIns(normalized);
        List<String> base = withoutLeadIns.isEmpty() ? normalized : withoutLeadIns;
        return List.copyOf(mergeConsequenceClauses(base));
    }

    /**
     * 丢弃「引导句」——以冒号结尾、本身不含可核验要求的条目。
     *
     * <p>真机样本（2026-11-04 本地真实任务，圈号形态）：
     * 「三项结构断言全部满足才通过：①…；②…；③…」——圈号拆分会把圈号前的引导句变成一条独立
     * 验收项，reviewer 只为三条实质断言写 {@code coverage}，于是被判 {@code acceptance_not_covered}。
     * 引导句的语义（「全部满足」）正是覆盖批评器本身的口径（每条都要 covered），因此丢弃不损失要求。</p>
     *
     * <p>全部条目都是引导句时返回空清单，由调用方回退为原文条目（不把整段判成「无需核对」）。</p>
     */
    private static List<String> stripLeadIns(List<String> items) {
        List<String> kept = new ArrayList<>(items.size());
        for (String item : items) {
            char last = item.isEmpty() ? ' ' : item.charAt(item.length() - 1);
            if (last == '：' || last == ':') {
                continue;
            }
            kept.add(item);
        }
        return kept;
    }

    /**
     * 把「后果条款」并回前一保存（见 {@link #CONSEQUENCE_MARKERS}）。
     *
     * <p>首项即后果条款时（没有可归属的前文）原样保留——宁可多核一条，也不丢弃原文。</p>
     */
    private static List<String> mergeConsequenceClauses(List<String> items) {
        if (items.size() < 2) {
            return items;
        }
        List<String> merged = new ArrayList<>(items.size());
        for (String item : items) {
            if (!merged.isEmpty() && isConsequenceClause(item)) {
                String previous = merged.remove(merged.size() - 1);
                String joined = previous + "；" + item;
                merged.add(joined.length() <= MAX_ITEM_CHARS ? joined : joined.substring(0, MAX_ITEM_CHARS));
                continue;
            }
            merged.add(item);
        }
        return merged;
    }

    /** 是否为后果条款（含 {@link #CONSEQUENCE_MARKERS} 之一）。 */
    private static boolean isConsequenceClause(String item) {
        for (String marker : CONSEQUENCE_MARKERS) {
            if (item.contains(marker)) {
                return true;
            }
        }
        return false;
    }

    /** 是否含圈号编号（①~⑳）。 */
    static boolean hasCircledNumber(String text) {
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c >= CIRCLED_FIRST && c <= CIRCLED_LAST) {
                return true;
            }
        }
        return false;
    }

    /** 按圈号拆：圈号本身丢弃（不是验收标准内容），条目内的 {@code ；} 原样保留。 */
    private static List<String> splitByCircled(String text) {
        List<String> items = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        int parenDepth = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '（' || c == '(') {
                parenDepth++;
            } else if (c == '）' || c == ')') {
                parenDepth = Math.max(0, parenDepth - 1);
            }
            // 括号内的圈号是交叉引用（如「（对应任务级验收条目 ①）」），不是条目边界
            if (c >= CIRCLED_FIRST && c <= CIRCLED_LAST && parenDepth == 0) {
                if (!current.isEmpty()) {
                    items.add(current.toString());
                    current.setLength(0);
                }
                continue;
            }
            current.append(c);
        }
        if (!current.isEmpty()) {
            items.add(current.toString());
        }
        return items;
    }

    /** 按全角分号拆（fallback：无圈号编号时）。 */
    private static List<String> splitByFullWidthSemicolon(String text) {
        List<String> items = new ArrayList<>();
        int start = 0;
        for (int i = 0; i < text.length(); i++) {
            if (text.charAt(i) == '；') {
                items.add(text.substring(start, i));
                start = i + 1;
            }
        }
        items.add(text.substring(start));
        return items;
    }

    /** 单条清洗：去首尾空白、去交叉引用括号、去行首列表符号、去尾标点、限长。 */
    private static String normalize(String raw) {
        if (raw == null) {
            return "";
        }
        String value = raw.trim();
        value = stripCrossReferenceParentheticals(value);
        // 行首列表符号（"- xxx" / "* xxx" / "· xxx"）不是验收内容本身
        while (!value.isEmpty() && (value.charAt(0) == '-' || value.charAt(0) == '*' || value.charAt(0) == '·')) {
            value = value.substring(1).trim();
        }
        value = trimTailPunctuation(value);
        return value.length() <= MAX_ITEM_CHARS ? value : value.substring(0, MAX_ITEM_CHARS);
    }

    /**
     * 去掉首尾的「交叉引用括号」（见 {@link #CROSS_REF_MARKERS}）。
     *
     * <p>普通括号（如「输出（含表格）」）<b>不得</b>被去掉：它可能有实质要求，
     * 只认含交叉引用标记的括号。</p>
     */
    private static String stripCrossReferenceParentheticals(String raw) {
        String value = raw.trim();
        String previous;
        do {
            previous = value;
            value = stripTrailingCrossReference(value);
            value = stripLeadingCrossReference(value);
        } while (!value.equals(previous));
        return value.trim();
    }

    private static String stripTrailingCrossReference(String value) {
        String current = value.trim();
        while (!current.isEmpty() && (current.endsWith("）") || current.endsWith(")"))) {
            int open = Math.max(current.lastIndexOf('（'), current.lastIndexOf('('));
            if (open < 0) {
                break;
            }
            String inside = current.substring(open + 1, current.length() - 1);
            if (!containsAny(inside, CROSS_REF_MARKERS)) {
                break;
            }
            current = trimTailPunctuation(current.substring(0, open).trim());
        }
        return current;
    }

    private static String stripLeadingCrossReference(String value) {
        String current = value.trim();
        while (!current.isEmpty() && (current.startsWith("（") || current.startsWith("("))) {
            int close = current.indexOf('）');
            int halfClose = current.indexOf(')');
            if (close < 0 || (halfClose >= 0 && halfClose < close)) {
                close = halfClose;
            }
            if (close < 0) {
                break;
            }
            String inside = current.substring(1, close);
            if (!containsAny(inside, CROSS_REF_MARKERS)) {
                break;
            }
            current = current.substring(close + 1).trim();
        }
        return current;
    }

    private static boolean containsAny(String text, List<String> markers) {
        for (String marker : markers) {
            if (text.contains(marker)) {
                return true;
            }
        }
        return false;
    }

    /** 去尾部句读（拆分残留的 {@code 。};};， 会让子串归属失败）。 */
    private static String trimTailPunctuation(String value) {
        String result = value;
        while (!result.isEmpty()) {
            char last = result.charAt(result.length() - 1);
            if (last == '。' || last == '；' || last == ';' || last == '，' || last == ',' || last == '、') {
                result = result.substring(0, result.length() - 1);
                continue;
            }
            break;
        }
        return result.trim();
    }
}
