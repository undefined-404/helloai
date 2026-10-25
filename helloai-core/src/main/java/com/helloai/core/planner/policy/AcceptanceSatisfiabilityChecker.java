package com.helloai.core.planner.policy;

import com.helloai.core.task.port.UncertaintyDraft;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * `TD-11`：验收契约**可满足性**标注（纯函数，无 IO、无 Spring、可单测）。
 *
 * <p><b>要解决的是什么</b>：`TD-5` 的 {@link AcceptanceKindValidator} 自述「只判文本写法，
 * 不做内容质量判定」，于是它会把一份**物理上不可能满足**的验收判成合法并放行——2026-10-25
 * 的事故就是这样：{@code content} 要求「不要自行替换成其他产品」，{@code acceptance} 要求
 * 「≥15 条带真实 URL 的事实」，两条同时对一份「竞品 A/B/C 未确认」的契约成立 ⇒ 执行者无解，
 * 编数据与交空表都被判 2 分，最终预算耗尽进死信（`LOG-20261025-014`）。</p>
 *
 * <p><b>本类只做「标注」，不做「判定」，更不阻断</b>：命中即返回引用清单，由调用方写进
 * {@code sub_task.context.acceptancePreconditions} + 记一条 WARN 级时间线事件；拆解照常落库。
 * 理由是「拦不住的代价（死信）比放过的代价（质量下滑）更高」，而且放过是**可见的**
 * （时间线 + `B1a` 修好后的驳回理由），拦下则是直接卡死主链路（`TD-5` 风险 2 的教训）。</p>
 *
 * <p><b>为什么规则只咬「实证型来源」而不咬「所有量词」</b>：`STRUCTURAL` 天然要写量词
 * （{@code ≥3 个章节} / {@code ≤300 字} / {@code 5 个字段}），这些**不依赖任何外部对象**，
 * 一律标注会大面积误标，并让核验侧的豁免（`TD-12`）失控。真正会与「待确认缺口」冲突的，
 * 只有「必须指向某个尚未确定的**外部实体**去取回证据」这一类（见计划 §3 `TD-11`）。</p>
 *
 * <p><b>诚实边界</b>：这是**启发式**而非证明。漏标不阻断任何链路（核验侧仍照常判定），
 * 误标会让该子任务在 `TD-12` 落地后被错误放宽——所以四条判据刻意收窄，宁可少标。</p>
 */
public final class AcceptanceSatisfiabilityChecker {

    /**
     * 实证型来源信号：验收要求「取回并核对外部证据」的文本特征。
     *
     * <p>取自 2026-10-25 事故契约原文（`URL` / `页面` / `原文摘录`）与同类交付物常见措辞。
     * **刻意不收「条数」**——「≥15 条」本身不指向外部实体。</p>
     */
    private static final Pattern EVIDENCE_SOURCE_PATTERN = Pattern.compile(
            "URL|url|网址|原文摘录|截图|文档名|一手来源|页面");

    /**
     * 豁免措辞：验收文本自己已经写了「前置未满足则不算失败」，则不需要再标注。
     *
     * <p>这是**允许模型自救**的那条路：模型若把逃生口写进验收（而不是只写在 {@code content}
     * 里），本类就不再标注——事故里恰好是「逃生口只写在 content、没传导进 acceptance」。</p>
     */
    private static final Pattern EXEMPTION_PATTERN = Pattern.compile(
            "未确认时不计|不计\\s*fail|不计入\\s*fail|待映射|若[^。；;\\n]{0,24}未提供|以[^。；;\\n]{0,24}为前提");

    /**
     * 上游已是「澄清 / 映射」类前置的信号。
     *
     * <p>命中即不标注：缺输入这件事已经由 DAG 表达（依赖上游先问清楚），再标一遍是重复记账，
     * 而且会在 `TD-12` 落地后造成「上游还没跑完就先豁免」的错误放宽。
     * 关键词刻意取窄——宁可漏判（= 照常标注 + 照常严格核验）也不要误判。</p>
     */
    private static final List<String> CLARIFY_UPSTREAM_HINTS = List.of(
            "澄清", "映射", "拍板", "需求确认", "口径确认");

    private AcceptanceSatisfiabilityChecker() {
    }

    /**
     * 补齐缺口引用标签（`TD-11`，**全项目唯一实现**）。
     *
     * <p>模型给了非空且未重复的 {@code ref} ⇒ 原样保留（模型可能写成 {@code C1} 这种有语义的
     * 标签，不该被覆盖）；否则补 {@code U<序号>}，并跳过已被占用的序号。</p>
     *
     * <p><b>为什么收在本类而不是落库侧</b>：JSONB 要带 {@code ref}（写入方 = 拆解落库），
     * 判据要读 {@code ref}（读取方 = 本类）。两处各写一份编号逻辑必然分叉
     * （「同一事实双写必分叉」）。故这里出唯一实现，落库侧调它、{@link #check} 入口也调它
     * （后者只是防御，正常链路走到这里时 ref 已齐）。</p>
     *
     * <p><b>作用域 = 本子任务内</b>：{@code acceptancePreconditions} 只写在该子任务的
     * {@code context} 里，不存在跨子任务引用，故不保证全局唯一。</p>
     */
    public static List<UncertaintyDraft> ensureRefs(List<UncertaintyDraft> raw) {
        if (raw == null || raw.isEmpty()) {
            return List.of();
        }
        List<UncertaintyDraft> out = new ArrayList<>(raw.size());
        Set<String> used = new LinkedHashSet<>();
        for (int i = 0; i < raw.size(); i++) {
            UncertaintyDraft u = raw.get(i);
            if (u == null) {
                continue;
            }
            out.add(new UncertaintyDraft(u.kind(), u.note(), resolveRef(u.ref(), i + 1, used)));
        }
        return out;
    }

    /** 单个 ref 解析：保留可用值，否则补 {@code U<n>} 并跳过已占用序号。 */
    private static String resolveRef(String provided, int seq, Set<String> used) {
        if (provided != null && !provided.isBlank() && used.add(provided)) {
            return provided;
        }
        int n = seq;
        while (!used.add("U" + n)) {
            n++;
        }
        return "U" + n;
    }

    /**
     * 标注结果。
     *
     * @param preconditionRefs 命中的不确定性引用（形如 {@code U1}）；**空 = 无标注**
     * @param uncertaintyNotes 命中的不确定性原文（供时间线直接可读，避免排障时再去反查 JSONB）
     */
    public record Result(List<String> preconditionRefs, List<String> uncertaintyNotes) {

        /** 无标注（等同「键缺失」，消费侧按无豁免处理）。 */
        public static final Result NONE = new Result(List.of(), List.of());

        public boolean unmet() {
            return !preconditionRefs.isEmpty();
        }
    }

    /**
     * 四条判据**全部满足**才标注（刻意收窄，宁可少标）。
     *
     * @param acceptance     该条草案的验收判据原文（`acceptance`）
     * @param uncertainties  该条草案**已归一化**的不确定性列表（`normalizeUncertainties` 之后，
     *                       元素上的 {@code ref} 已由落库侧补齐非空）
     * @param upstreamTexts  该条草案 {@code dependsOn} 指向的草案的标题/正文摘要；空列表 = 无依赖
     * @return 标注结果；{@link Result#NONE} 表示不标注
     */
    public static Result check(String acceptance, List<UncertaintyDraft> uncertainties,
                               List<String> upstreamTexts) {
        return check(acceptance, uncertainties, upstreamTexts, null);
    }

    /**
     * 四条判据**全部满足**才标注（任务级口径，2026-10-25 准确率修正）。
     *
     * <p><b>与单条口径的差异</b>：条件 1 从「本条有 UNCONFIRMED」改为「任务级存在 UNCONFIRMED」。
     * 根因是 LOG-20261025-016 实测：模型把「A/B/C 是谁」只申报在下游草案 7/8，而真正需要它的
     * 2/3/4 报成 TO_LOOK_UP ⇒ 单条口径标了不该标的、漏了该标的。任务级口径下，只要任务里
     * 有 UNCONFIRMED，所有命中实证型来源的草案都会被标注（多标只影响观测，少标会让豁免失灵）。</p>
     *
     * @param acceptance           该条草案的验收判据原文（`acceptance`）
     * @param uncertainties        该条草案**已归一化**的不确定性列表（用于条件 1 的兜底）
     * @param upstreamTexts        该条草案 {@code dependsOn} 指向的草案的标题/正文摘要；空列表 = 无依赖
     * @param taskLevelUncertainties 任务级 uncertainties（所有草案的汇总）；null = 退化为单条口径
     * @return 标注结果；{@link Result#NONE} 表示不标注
     */
    public static Result check(String acceptance, List<UncertaintyDraft> uncertainties,
                               List<String> upstreamTexts, List<UncertaintyDraft> taskLevelUncertainties) {
        // 条件 4：依赖已表达缺输入 ⇒ 不再重复标注（先判，省掉后面的解析）
        if (hasClarifyUpstream(upstreamTexts)) {
            return Result.NONE;
        }
        // 条件 3：验收自己写了豁免措辞 ⇒ 该契约已可满足，不需要标注
        String acceptanceText = acceptance == null ? "" : acceptance;
        if (EXEMPTION_PATTERN.matcher(acceptanceText).find()) {
            return Result.NONE;
        }
        // 条件 2：验收要求取回外部实证
        if (!EVIDENCE_SOURCE_PATTERN.matcher(acceptanceText).find()) {
            return Result.NONE;
        }
        // 条件 1：存在「只能问人」的缺口（TO_LOOK_UP / TO_DERIVE 排除在外——执行者可自助解决）
        // 2026-10-25 准确率修正：优先看任务级，任务级没有才看本条（兜底）
        List<UncertaintyDraft> candidates = taskLevelUncertainties != null && !taskLevelUncertainties.isEmpty()
                ? taskLevelUncertainties : uncertainties;
        List<String> refs = new ArrayList<>();
        List<String> notes = new ArrayList<>();
        // ref 由落库侧 ensureRefs 保证非空；此处再走一遍同一实现，只是防「别的调用方
        // 直接传未归一化列表」时产出空引用（空引用会让核验侧定位不到缺口原文）。
        for (UncertaintyDraft u : ensureRefs(candidates)) {
            if (!UncertaintyDraft.KIND_UNCONFIRMED.equals(u.kind())) {
                continue;
            }
            if (u.note() == null || u.note().isBlank()) {
                continue;
            }
            refs.add(u.ref());
            notes.add(u.note());
        }
        return refs.isEmpty() ? Result.NONE : new Result(List.copyOf(refs), List.copyOf(notes));
    }

    private static boolean hasClarifyUpstream(List<String> upstreamTexts) {
        if (upstreamTexts == null || upstreamTexts.isEmpty()) {
            return false;
        }
        for (String text : upstreamTexts) {
            if (text == null || text.isBlank()) {
                continue;
            }
            for (String hint : CLARIFY_UPSTREAM_HINTS) {
                if (text.contains(hint)) {
                    return true;
                }
            }
        }
        return false;
    }
}
