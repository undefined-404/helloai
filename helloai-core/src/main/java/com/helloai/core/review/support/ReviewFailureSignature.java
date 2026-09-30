package com.helloai.core.review.support;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 重复失败签名检测（P-1 防御层 A2-2）：判定连续两轮核验驳回是否为「同一结构性失败」。
 *
 * <p><b>背景</b>：tku-e2e-01 事故中，子任务因上游材料被截断而物理上无法满足验收，
 * 评审 LLM 每轮给出几乎相同的驳回意见（同一批关键词不可见），重派链在反复重试一个
 * 结构性不可能的任务——空转 4 轮、烧掉 346K tokens 后仍进死信。本类用字符 bigram
 * Jaccard 相似度量化「本轮 issues 与上轮 issues 的重复度」。</p>
 *
 * <p><b>判据定位（R1 修订，2026-09-30 审计 §15.2）</b>：真实长文本 issues 每轮以
 * 不同措辞 / 编号重写（sub3 三轮 2753/1526/928 字符，相似度实测仅 0.25/0.19/0.18），
 * 相似度无法作为短路主判据——短路主判据改为「两轮 score 未严格提升」（服务层实现），
 * 本类相似度降为 score 任一轮缺失时的兜底信号。</p>
 *
 * <p><b>为什么用字符 bigram</b>：驳回意见为中英文混合的自由文本，无稳定分词；
 * 字符 bigram 集合对措辞微漂移（标点/助词差异）不敏感、对内容变化敏感，且零依赖。
 * 同一 LLM 对同一输入的两轮输出通常逐字近似（相似度接近 1.0），不同拒因则显著低于阈值。</p>
 *
 * <p>纯静态工具：无 Spring、无业务实体依赖，任意域可安全引用。</p>
 */
public final class ReviewFailureSignature {

    private ReviewFailureSignature() {
    }

    /**
     * 文本相似度（字符 bigram Jaccard）：|A∩B| / |A∪B|，取值 [0, 1]。
     * 比较前经 {@link #canonical} 规范化（去空白/标点/符号 + 转小写）——
     * 消除 LLM 措辞层面的格式漂移（换分隔符、大小写），只留内容字符。
     * 双方规范化后均为空 → 1.0（同为「无意见」）；单侧为空 → 0.0（无法判同）。
     */
    public static double similarity(String a, String b) {
        String na = canonical(a);
        String nb = canonical(b);
        if (na.isEmpty() && nb.isEmpty()) {
            return 1.0;
        }
        if (na.isEmpty() || nb.isEmpty()) {
            return 0.0;
        }
        Set<String> gramsA = bigrams(na);
        Set<String> gramsB = bigrams(nb);
        Set<String> intersection = new HashSet<>(gramsA);
        intersection.retainAll(gramsB);
        if (intersection.isEmpty()) {
            return 0.0;
        }
        Set<String> union = new HashSet<>(gramsA);
        union.addAll(gramsB);
        return (double) intersection.size() / union.size();
    }

    /** 规范化：去空白/Unicode 标点/符号 + 转小写——格式漂移不敏感、内容变化敏感。 */
    private static String canonical(String text) {
        if (text == null) {
            return "";
        }
        return text.replaceAll("[\\p{P}\\p{S}\\s]+", "").toLowerCase();
    }

    /** 字符 bigram 集合；单字符文本以自身作为唯一边（保证非空可比较）。 */
    private static Set<String> bigrams(String text) {
        Set<String> grams = new HashSet<>();
        for (int i = 0; i + 1 < text.length(); i++) {
            grams.add(text.substring(i, i + 2));
        }
        if (grams.isEmpty()) {
            grams.add(text);
        }
        return grams;
    }

    /** reviewHistory 原始 issues 值规范化为文本：null → ""；List → 「；」连接；其余 toString。 */
    public static String normalize(Object issues) {
        if (issues == null) {
            return "";
        }
        if (issues instanceof List<?> list) {
            return list.stream().map(String::valueOf).collect(Collectors.joining("；"));
        }
        return issues.toString();
    }

    /** reviewHistory 原始 score 值规范化为 Integer：Number 直取；数字字符串解析；其余 null。 */
    public static Integer asScore(Object score) {
        if (score instanceof Number n) {
            return n.intValue();
        }
        if (score instanceof String s) {
            try {
                return Integer.parseInt(s.trim());
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }
}
