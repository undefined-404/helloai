package com.helloai.common.util;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 执行期整改的确定性契约门 {@code G5_RUNTIME_CONTRACT_BREAK}（`ADR-008` §12.2）。
 *
 * <p><b>为什么在 {@code common}</b>：与 {@link DraftGateChecker} 同置——纯文本判定、不依赖任何
 * 领域实体或 Mapper，唯一的输入是「下游契约文本」，属 §4.1「基础工具」；放 task 域会让将来
 * review 侧复用同一判据时反向依赖。</p>
 *
 * <p><b>只判一条机械可判的规则</b>：下游明确引用了本子任务整改前的某个产出标识，而整改后的
 * {@code deliverable} / {@code acceptance} 不再包含它 ⇒ 「后面的任务执行逻辑相悖」。
 * 标识只取反引号、引号包裹的片段与<b>带大写/数字/点/下划线/连字符的 ASCII 标识</b>
 * （{@code matchResult}、{@code README.md}、{@code v1}）；普通中文词与纯小写英文词不参与——
 * 自然语言里的同义改写不是契约，按词匹配必然误报。</p>
 *
 * <p><b>为什么只做下游向</b>：「新要求的事实被上游产出排除」需要语义判断，正则做不到；
 * `ADR-008` §17.4 已把它登记为待实测项，语义门（`G4` 同型）另行灰度。本类只承担零 LLM、
 * 零等待的那一半。</p>
 *
 * <p><b>只判定、不落库、不抛异常</b>：处置由调用方按 {@link Finding#severity()} 决定
 * （当前唯一规则给 {@code block}）。</p>
 */
public final class RuntimeContractGate {

    /** 规则版本：判据口径变更时递增，历史 findings 可据此归因。 */
    public static final String RULE_VERSION = "g5-0";

    /** 下游引用了整改前的产出标识，而整改后不再产出。 */
    public static final String G5_DOWNSTREAM_REF_BREAK = "G5_DOWNSTREAM_REF_BREAK";

    private static final Pattern BACKTICK = Pattern.compile("`([^`\\n]{2,64})`");
    private static final Pattern QUOTED = Pattern.compile("[「“\"]([^」”\"\\n]{2,64})[」”\"]");
    private static final Pattern ASCII_IDENT = Pattern.compile("\\b([A-Za-z][A-Za-z0-9_.-]{2,63})\\b");
    /** 参与判定的标识上限：再多也只会把结论重复一遍，无信息增量。 */
    private static final int MAX_TOKENS = 50;

    /** 下游子任务的契约文本（{@code content} + {@code acceptance}）。 */
    public record Downstream(long id, String title, String text) {
    }

    /** 上游子任务的产出/约束文本（{@code content} + {@code acceptance} + {@code constraints} + 最近产出）。 */
    public record Upstream(long id, String title, String text) {
    }

    public record Finding(String ruleId, String severity, String evidence) {
    }

    public record Result(List<Finding> findings) {

        public boolean hasBlocking() {
            return findings.stream().anyMatch(f -> "block".equals(f.severity()));
        }
    }

    /**
     * 判定下游契约是否被整改破坏。
     *
     * @param oldDeliverable 整改前交付物（无编辑历史时传当前值，等价于「未改动」）
     * @param oldAcceptance  整改前验收
     * @param newDeliverable 整改后交付物
     * @param newAcceptance  整改后验收
     * @param downstreams    依赖本子任务、且未终态（非 DONE / CANCELLED）的下游
     */
    public static Result check(String oldDeliverable, String oldAcceptance,
                               String newDeliverable, String newAcceptance,
                               List<Downstream> downstreams) {
        if (downstreams == null || downstreams.isEmpty()) {
            return new Result(List.of());
        }
        Set<String> oldTokens = extractTokens(join(oldDeliverable, oldAcceptance));
        if (oldTokens.isEmpty()) {
            return new Result(List.of());
        }
        String newText = lower(join(newDeliverable, newAcceptance));
        List<Finding> findings = new ArrayList<>();
        Set<String> reported = new LinkedHashSet<>();
        for (String token : oldTokens) {
            if (newText.contains(lower(token))) {
                continue;
            }
            for (Downstream d : downstreams) {
                if (d.text() == null || !lower(d.text()).contains(lower(token))) {
                    continue;
                }
                String key = token.toLowerCase(Locale.ROOT) + "#" + d.id();
                if (!reported.add(key)) {
                    continue;
                }
                findings.add(new Finding(G5_DOWNSTREAM_REF_BREAK, "block",
                        "下游「" + d.title() + "」(id=" + d.id() + ") 引用了 " + token
                                + "，但整改后的交付物与验收不再包含该标识"));
            }
        }
        return new Result(List.copyOf(findings));
    }

    /** 落库结构（与 {@code gateFindings} 同形）：无问题时 {@code findings: []} 也写出，消费方只看空不空。 */
    public static Map<String, Object> payload(Result result, String checkedAt) {
        Map<String, Object> payload = new LinkedHashMap<>();
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Finding f : result.findings()) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("ruleId", f.ruleId());
            row.put("severity", f.severity());
            row.put("evidence", f.evidence());
            rows.add(row);
        }
        payload.put("findings", rows);
        payload.put("ruleVersion", RULE_VERSION);
        payload.put("checkedAt", checkedAt);
        return payload;
    }

    private static Set<String> extractTokens(String text) {
        Set<String> tokens = new LinkedHashSet<>();
        if (text == null || text.isBlank()) {
            return tokens;
        }
        collect(BACKTICK, text, tokens, true);
        collect(QUOTED, text, tokens, true);
        collect(ASCII_IDENT, text, tokens, false);
        return tokens;
    }

    private static void collect(Pattern pattern, String text, Set<String> tokens, boolean quoted) {
        Matcher m = pattern.matcher(text);
        while (m.find() && tokens.size() < MAX_TOKENS) {
            String token = m.group(1).trim();
            if (token.isEmpty()) {
                continue;
            }
            if (!quoted && !hasIdentifierFeature(token)) {
                continue;
            }
            tokens.add(token);
        }
    }

    /**
     * 纯小写英文词（{@code the} / {@code readme} 一类）不当作契约标识：
     * 它们在任何文本里都高频出现，参与匹配只会制造「改了措辞就误报」的噪声。
     */
    private static boolean hasIdentifierFeature(String token) {
        for (int i = 0; i < token.length(); i++) {
            char c = token.charAt(i);
            if (Character.isUpperCase(c) || Character.isDigit(c) || c == '.' || c == '_' || c == '-') {
                return true;
            }
        }
        return false;
    }

    private static String join(String a, String b) {
        return (a == null ? "" : a) + "\n" + (b == null ? "" : b);
    }

    private static String lower(String s) {
        return s == null ? "" : s.toLowerCase(Locale.ROOT);
    }

    private RuntimeContractGate() {
    }
}
