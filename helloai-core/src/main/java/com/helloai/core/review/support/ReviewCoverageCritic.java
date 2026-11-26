package com.helloai.core.review.support;

import com.helloai.common.constant.UncertaintyKind;
import com.helloai.core.review.service.SubTaskReviewService.ReviewVerdict;
import com.helloai.core.review.support.ReviewCoverageParser.CoverageItem;
import com.helloai.core.review.support.ReviewVerdictValidator.Finding;
import com.helloai.core.review.support.ReviewVerdictValidator.Severity;
import com.helloai.core.task.port.UncertaintyView;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 静态覆盖批评器（`RV-3`，纯函数、零 token、无 Spring 依赖）。
 *
 * <p>核对「宣称通过」与「验收点逐条有覆盖声明」是否自洽、执行侧 {@code UNCONFIRMED} 缺口有无在判定里消失；
 * 只核对声明的结构完整性，<b>不重新核验产出</b>（抓「漏判可发现」，不引入第二个 LLM）。</p>
 *
 * <p>只在 {@code pass=true} 侧生效：驳回侧已有明确结论，升级 {@code INCONCLUSIVE} 会丢掉一个明确驳回。
 * 覆盖判据用 {@code criterionRef} 子串归属而非条数相等（{@code acceptance} 分隔符风格不统一，按条数比必失败），
 * 复用 {@link ReviewVerdictValidator#isGroundedIn} 与 {@code missingEvidence.acceptanceRef} 同一事实源。</p>
 *
 * <p>{@code BLOCK} 只把「通过」改「存疑转人工」，不放行不合格；{@code WARN} 只观测。
 * {@code coverage} 整段缺失判 {@code BLOCK}，处置关 {@code helloai.review.coverage-critic-enabled} 而非放宽判据。</p>
 */
public final class ReviewCoverageCritic {

    /** {@code evidenceRef} 里的附件 id（容忍千分位逗号）；`RV-10` 逐条命中与 `RV-11` 同一解析口径。 */
    private static final Pattern EVIDENCE_ID = Pattern.compile("id\\s*=\\s*([,0-9]+)");

    /** 通过判定但 {@code coverage} 整段缺失（既无原文引用也无状态）：验收点无任何覆盖声明。 */
    public static final String COVERAGE_ABSENT = "coverage_absent";

    /** 至少一条验收标准拆分项未被任何合格 {@code criterionRef} 覆盖。 */
    public static final String ACCEPTANCE_NOT_COVERED = "acceptance_not_covered";

    /** {@code criterionRef} 不是验收标准原文子串（疑似编造引用）。 */
    public static final String COVERAGE_REF_NOT_GROUNDED = "coverage_ref_not_grounded";

    /** {@code status=covered} 但证据方式不在 {@code attachment|sandbox|mechanical} 白名单内（自述当证据）。 */
    public static final String COVERAGE_SELF_REPORTED = "coverage_self_reported";

    /** {@code method=mechanical} 但平台没有该附件的机械结论（拿机械结论冒充语义核验）。 */
    public static final String COVERAGE_MECHANICAL_UNAVAILABLE = "coverage_mechanical_unavailable";

    /** 存在不指向任何拆分项的 {@code coverage} 条目（计数只作告警，不作拦截，见实施计划 §3）。 */
    public static final String COVERAGE_EXTRANEOUS = "coverage_extraneous";

    /** 执行侧 {@code UNCONFIRMED} 申报在判定里无声消失（既未处理也未上报）。 */
    public static final String UNCONFIRMED_SILENTLY_DROPPED = "unconfirmed_silently_dropped";

    /** finding detail 里最多列出的条目数。 */
    private static final int MAX_ITEMS_IN_DETAIL = 5;

    /** 单条 detail 字符上限（与 {@link ReviewVerdictValidator} 同口径）。 */
    private static final int MAX_DETAIL_CHARS = 200;

    /** {@code UNCONFIRMED} 消音判据的「提及」认定：取申报文本前若干字符作匹配锚点。 */
    private static final int MENTION_ANCHOR_CHARS = 12;

    private ReviewCoverageCritic() {
    }

    /**
     * 覆盖批评。
     *
     * @param verdict       已解析判定；null / 非通过 ⇒ 空清单（只核「放水」，不核「驳回」）
     * @param acceptance    {@code sub_task.acceptance} 原文；空 ⇒ 无可核对对象，跳过
     * @param uncertainties 执行侧不确定性申报（可空）
     * @return findings（{@code BLOCK} 由调用方按 `RV-2` 落 {@code INCONCLUSIVE}）
     */
    public static List<Finding> critique(ReviewVerdict verdict, String acceptance,
                                        List<UncertaintyView> uncertainties) {
        return critique(verdict, acceptance, uncertainties, "");
    }

    /**
     * 覆盖批评（带机械预检原文，`RV-10`）：{@code method=mechanical} 只有**该条目引用的附件**
     * 在机械段确有可测量结论时才计入覆盖，否则按未覆盖处理——防两件事：拿机械结论冒充语义核验、
     * 以及拿 A 文件的结论给 B 文件作保（见 {@link #mechanicalCovers}）。
     *
     * @param mechanicalCheck {@code [MECHANICAL_CHECK]} 段（空 ⇒ 所有 mechanical 条目都不计入）
     */
    public static List<Finding> critique(ReviewVerdict verdict, String acceptance,
                                        List<UncertaintyView> uncertainties,
                                        String mechanicalCheck) {
        if (verdict == null || !Boolean.TRUE.equals(verdict.getPass())) {
            return List.of();
        }
        List<String> items = AcceptanceItemSplitter.split(acceptance);
        if (items.isEmpty()) {
            // 验收标准为空：没有可核对的验收点。判「未覆盖」会把「平台没给标准」算成「模型放水」。
            return List.of();
        }
        // 机械段传原文而非布尔：`RV-10` 的命中判据是「这条引用的附件**自己有**机械结论」，
        // 全局「机械段非空」会让 A 文件的结论给 B 文件作保。
        String mechanical = mechanicalCheck == null ? "" : mechanicalCheck;
        List<CoverageItem> coverage = ReviewCoverageParser.parse(verdict.getCoverage());
        List<Finding> findings = new ArrayList<>();
        if (coverage.isEmpty()) {
            findings.add(new Finding(Severity.BLOCK, COVERAGE_ABSENT,
                    "判定通过但未输出 coverage 覆盖清单，" + items.size() + " 条验收标准无任何覆盖声明"));
        } else {
            findings.addAll(contractFindings(coverage, acceptance, mechanical));
            findings.addAll(coverageGapFindings(items, coverage, acceptance, mechanical));
        }
        Finding silenced = unconfirmedSilentlyDropped(verdict, coverage, uncertainties);
        if (silenced != null) {
            findings.add(silenced);
        }
        // BLOCK 在前（稳定排序，同类保持产出顺序）：与 ReviewVerdictValidator 同一约定，
        // 调用方与单测可按 findings.get(0) 直接取拦截依据
        findings.sort(Comparator.comparing((Finding finding) -> finding.severity() != Severity.BLOCK));
        return findings;
    }

    /**
     * 契约形态告警（不影响覆盖判定本身）：引用不落地、自述当证据、多余条目。
     *
     * <p>{@code covered} + 非白名单 {@code method} 在 {@link CoverageItem#covers()} 里已不计入覆盖，
     * 这里再单列一条 WARN，是为了让「模型想用产出文本自述蒙过覆盖核对」在时间线上可读。</p>
     *
     * <p>`G-052`：归属判据分「字面」与「归一后」两档——后者计为已归属，但单列
     * {@link ReviewVerdictValidator#REF_FORMAT_LOOSE} 观测，避免「每轮都丢反引号」这种系统性
     * 抄写差异被永久静默（一旦提示词或模型换代，这条 WARN 的消失与否就是判据是否还有必要的证据）。</p>
     */
    private static List<Finding> contractFindings(List<CoverageItem> coverage, String acceptance,
                                                  String mechanicalCheck) {
        List<Finding> findings = new ArrayList<>();
        List<String> ungrounded = new ArrayList<>();
        List<String> looseOnly = new ArrayList<>();
        List<String> selfReported = new ArrayList<>();
        List<String> mechanicalUnavailable = new ArrayList<>();
        for (CoverageItem item : coverage) {
            if (item.hasRef()) {
                if (!ReviewVerdictValidator.isGroundedIn(acceptance, item.criterionRef())) {
                    ungrounded.add(item.criterionRef());
                } else if (!ReviewVerdictValidator.isGroundedExactly(acceptance, item.criterionRef())) {
                    looseOnly.add(item.criterionRef());
                }
            }
            if (item.claimsCovered() && !item.hasAdmissibleMethod()) {
                selfReported.add(item.criterionRef().isEmpty() ? "(无 criterionRef)" : item.criterionRef());
            }
            if (item.claimsCovered() && ReviewCoverageParser.METHOD_MECHANICAL.equals(item.method())
                    && !mechanicalCovers(item, mechanicalCheck)) {
                mechanicalUnavailable.add(item.criterionRef().isEmpty()
                        ? "(无 criterionRef)" : item.criterionRef());
            }
        }
        if (!ungrounded.isEmpty()) {
            findings.add(new Finding(Severity.WARN, COVERAGE_REF_NOT_GROUNDED,
                    listDetail(ungrounded.size(), ungrounded, " 条 criterionRef 不是验收标准原文子串: ")));
        }
        if (!looseOnly.isEmpty()) {
            findings.add(new Finding(Severity.WARN, ReviewVerdictValidator.REF_FORMAT_LOOSE,
                    listDetail(looseOnly.size(), looseOnly,
                            " 条 criterionRef 仅在剥除排版标记后才命中原文（已按已归属计入覆盖）: ")));
        }
        if (!selfReported.isEmpty()) {
            findings.add(new Finding(Severity.WARN, COVERAGE_SELF_REPORTED,
                    listDetail(selfReported.size(), selfReported,
                            " 条 status=covered 但证据方式不是 attachment/sandbox/mechanical（自述不计入覆盖）: ")));
        }
        if (!mechanicalUnavailable.isEmpty()) {
            findings.add(new Finding(Severity.WARN, COVERAGE_MECHANICAL_UNAVAILABLE,
                    listDetail(mechanicalUnavailable.size(), mechanicalUnavailable,
                            " 条 status=covered 用 mechanical 但该附件无平台机械结论（不计入覆盖）: ")));
        }
        return findings;
    }

    /**
     * 未覆盖判定 + 多余条目告警。
     *
     * <p>两条判据分开：{@code uncovered} 问「这条验收点有没有<b>合格覆盖</b>」，
     * {@code extraneous} 问「这条 coverage <b>指没指向</b>某个验收点」——后者只看文本归属，
     * 不看状态。把两者混用会把「如实申报 {@code failed} 的条目」报成「多余条目」，
     * 而那条恰恰是 reviewer 唯一写了真话的条目。</p>
     *
     * <p>引用不落地的条目只由 {@link #COVERAGE_REF_NOT_GROUNDED} 报一次，此处跳过（同一条坏引用
     * 发两条告警是噪声）。</p>
     */
    private static List<Finding> coverageGapFindings(List<String> items, List<CoverageItem> coverage,
                                                     String acceptance, String mechanicalCheck) {
        List<String> uncovered = new ArrayList<>();
        for (String item : items) {
            boolean covered = false;
            for (CoverageItem entry : coverage) {
                if (covers(entry, item, mechanicalCheck)) {
                    covered = true;
                    break;
                }
            }
            if (!covered) {
                uncovered.add(item);
            }
        }
        List<String> extraneous = new ArrayList<>();
        for (CoverageItem entry : coverage) {
            if (!entry.hasRef() || !ReviewVerdictValidator.isGroundedIn(acceptance, entry.criterionRef())) {
                continue;
            }
            if (!pointsAtAny(entry, items)) {
                extraneous.add(entry.criterionRef());
            }
        }
        List<Finding> findings = new ArrayList<>(2);
        if (!uncovered.isEmpty()) {
            findings.add(new Finding(Severity.BLOCK, ACCEPTANCE_NOT_COVERED,
                    listDetail(uncovered.size(), uncovered,
                            " 条验收标准无合格覆盖（需 covered + 附件/沙箱/机械预检证据）: ")));
        }
        if (!extraneous.isEmpty()) {
            findings.add(new Finding(Severity.WARN, COVERAGE_EXTRANEOUS,
                    listDetail(extraneous.size(), extraneous, " 条 coverage 不指向任何验收标准拆分项: ")));
        }
        return findings;
    }

    /** 单个覆盖条目是否覆盖某个拆分项（须构成合格覆盖 + 文本归属 + 该附件有机械结论）。 */
    private static boolean covers(CoverageItem entry, String item, String mechanicalCheck) {
        if (ReviewCoverageParser.METHOD_MECHANICAL.equals(entry.method())
                && !mechanicalCovers(entry, mechanicalCheck)) {
            return false;
        }
        return entry.covers() && pointsAt(entry, item);
    }

    /**
     * 机械结论是否落在**该条目引用的附件**上（`RV-10`，逐条命中）。
     *
     * <p>{@code evidenceRef} 必须写出 {@code id=<附件id>}，且该 id 在 {@code [MECHANICAL_CHECK]}
     * 里有一行可测量的 {@code file id=… lines/rows/data} 结论。缺 id、id 查不到、或该附件只有
     * {@code UNAVAILABLE}（算不出来）都<b>不计入覆盖</b>——否则「拿 A 文件的机械结论给 B 文件作保」
     * 查不出来，而这正是「证据指向必须落地」的同一原则（与 {@code criterionRef} 必须是原文子串、
     * {@code claim=} 必须能对上实测值一致）。</p>
     */
    private static boolean mechanicalCovers(CoverageItem entry, String mechanicalCheck) {
        Long id = evidenceId(entry.evidenceRef());
        return id != null && ReviewMechanicalCheck.hasMeasure(mechanicalCheck, id);
    }

    /** 从 {@code evidenceRef} 取附件 id（兼容 {@code id=123} / {@code id=123,456} / {@code id=123 claim=…}）。 */
    private static Long evidenceId(String evidenceRef) {
        if (evidenceRef == null || evidenceRef.isEmpty()) {
            return null;
        }
        Matcher matcher = EVIDENCE_ID.matcher(evidenceRef);
        if (!matcher.find()) {
            return null;
        }
        try {
            return Long.valueOf(matcher.group(1).replace(",", ""));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** 文本归属（双向子串：允许整条抄写，也允许只引用片段）。 */
    private static boolean pointsAt(CoverageItem entry, String item) {
        String ref = entry.criterionRef();
        return ReviewVerdictValidator.isGroundedIn(item, ref) || ReviewVerdictValidator.isGroundedIn(ref, item);
    }

    private static boolean pointsAtAny(CoverageItem entry, List<String> items) {
        for (String item : items) {
            if (pointsAt(entry, item)) {
                return true;
            }
        }
        return false;
    }

    /**
     * {@code UNCONFIRMED} 消音判定（只对 {@code UNCONFIRMED} 生效，{@code ASSUMPTION} 不判——提示词第 10 条明写它不构成驳回理由，判它即假阳性）。
     *
     * <p>「提及」取宽口径：缺口引用标签（{@code ref}，如 {@code U1}）、申报原文、或申报前 {@value #MENTION_ANCHOR_CHARS} 字符锚点，
     * 任一出现在 {@code issues/analysis/comment/missingEvidence.acceptanceRef/coverage} 即算已处理。reviewer 会改写措辞，从严会把「处理了但换说法」误判消音，
     * 故判错方向固定为<b>少拦</b>而非<b>误杀</b>。</p>
     */
    private static Finding unconfirmedSilentlyDropped(ReviewVerdict verdict,
                                                      List<CoverageItem> coverage,
                                                      List<UncertaintyView> uncertainties) {
        if (uncertainties == null || uncertainties.isEmpty()) {
            return null;
        }
        String haystack = haystack(verdict, coverage);
        List<String> silenced = new ArrayList<>();
        for (UncertaintyView uncertainty : uncertainties) {
            if (uncertainty == null || UncertaintyKind.parse(uncertainty.kind()) != UncertaintyKind.UNCONFIRMED) {
                continue;
            }
            if (!mentioned(haystack, uncertainty)) {
                silenced.add(anchorOf(uncertainty));
            }
        }
        if (silenced.isEmpty()) {
            return null;
        }
        return new Finding(Severity.BLOCK, UNCONFIRMED_SILENTLY_DROPPED,
                listDetail(silenced.size(), silenced, " 条 UNCONFIRMED 申报在判定中无声消失（未处理也未上报）: "));
    }

    /** 判定的全部可读文本面（用于「是否提及」的宽口径匹配）。 */
    private static String haystack(ReviewVerdict verdict, List<CoverageItem> coverage) {
        StringBuilder sb = new StringBuilder();
        append(sb, verdict.getIssues());
        append(sb, verdict.getAnalysis());
        append(sb, verdict.getComment());
        for (CoverageItem item : coverage) {
            append(sb, item.criterionRef());
            append(sb, item.note());
        }
        VerdictParser.normalizeMissingEvidence(verdict.getMissingEvidence())
                .forEach(entry -> append(sb, entry.get("acceptanceRef")));
        return sb.toString();
    }

    private static void append(StringBuilder sb, String text) {
        if (text != null && !text.isBlank()) {
            sb.append(text).append('\n');
        }
    }

    private static boolean mentioned(String haystack, UncertaintyView uncertainty) {
        if (haystack.isEmpty()) {
            return false;
        }
        if (uncertainty.ref() != null && !uncertainty.ref().isBlank()
                && haystack.contains(uncertainty.ref().trim())) {
            return true;
        }
        String note = uncertainty.note();
        if (note == null || note.isBlank()) {
            // 无 note 的申报没有可核对的文本面，按「已处理」放过（不拿空申报判消音）
            return true;
        }
        String trimmed = note.trim();
        return haystack.contains(trimmed) || haystack.contains(anchorOf(uncertainty));
    }

    /** 匹配锚点：申报前若干字符（改写措辞时仍能命中开头）。 */
    private static String anchorOf(UncertaintyView uncertainty) {
        if (uncertainty.ref() != null && !uncertainty.ref().isBlank()) {
            return uncertainty.ref().trim();
        }
        String note = uncertainty.note() == null ? "" : uncertainty.note().trim();
        return note.length() <= MENTION_ANCHOR_CHARS ? note : note.substring(0, MENTION_ANCHOR_CHARS);
    }

    private static String listDetail(int total, List<String> values, String label) {
        StringBuilder detail = new StringBuilder().append(total).append(label);
        for (int i = 0; i < values.size() && i < MAX_ITEMS_IN_DETAIL; i++) {
            if (i > 0) {
                detail.append("；");
            }
            detail.append(values.get(i));
        }
        if (values.size() > MAX_ITEMS_IN_DETAIL) {
            detail.append("…");
        }
        return truncate(detail.toString());
    }

    private static String truncate(String text) {
        if (text == null) {
            return "";
        }
        return text.length() <= MAX_DETAIL_CHARS ? text : text.substring(0, MAX_DETAIL_CHARS) + "...";
    }
}
