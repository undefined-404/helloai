package com.helloai.core.agent.runtime.loop.impl;

import com.helloai.core.agent.runtime.sandbox.SandboxCallLedger;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 自审闸门（`SR-3`，纯静态、零 token、无 Spring 依赖）。
 *
 * <p><b>只判「证据是否存在且与台账一致」，不判交付物内容好坏</b>——后者仍归平台 reviewer
 * （与 {@code ExecuteResponse} 「沙箱是证据提供者、不是判定者」同一边界，ADR-004 §4）。
 * 与 {@code FinalReportFidelityGate} 的分工：本闸门判<b>单子任务收尾轮</b>的证据，
 * 那个判<b>终报告</b>的脚本可执行性，两者判不同对象、只共享 {@code EVIDENCE} 结构（实施计划 §4 D4）。</p>
 *
 * <p><b>两档判据</b>（实施计划 §3 `SR-3`）：</p>
 * <ul>
 *     <li>{@link Tier#SANDBOX_REQUIRED}：交付物声明为脚本/可执行文件，<b>或</b>围栏里已经自填了
 *         {@code EVIDENCE} 条目（自报的声明必须兑现）⇒ 必须有成功的一手沙箱证据；
 *         「声明的命令未执行 / {@code exitCode != 0} / {@code truncated}」一律不通过。</li>
 *     <li>{@link Tier#EVIDENCE_FORM_ONLY}：文档类交付物且无任何命令声明 ⇒ 只校验证据块形态
 *         （非法 JSON 判失败；缺失或空数组不判失败，否则文档类任务会被系统性拦截）。</li>
 * </ul>
 *
 * <p><b>判错的代价方向</b>：档 1 误判（把文档类当脚本类）代价 = 有界一次重跑；档 2 漏判代价 =
 * 少一次拦截，仍由 reviewer 大环兜底。故关键词取严（宁少不多），并在
 * {@link #requiresSandboxEvidence} 标注扩展点。</p>
 */
public final class SelfReviewGate {

    /** 闸门档位。 */
    public enum Tier {
        /** 必须有一手沙箱证据。 */
        SANDBOX_REQUIRED,
        /** 只校验证据块形态。 */
        EVIDENCE_FORM_ONLY,
        /** 沙箱未启用 ⇒ 闸门不判（`SR-4` 记 {@code degraded_no_sandbox}）。 */
        SKIPPED_NO_SANDBOX,
        /** 无子任务上下文 ⇒ 读不到台账，闸门不判（不猜）。 */
        SKIPPED_NO_SUB_TASK,
        /** 闸门开关关闭（{@code helloai.review.self-review.enabled=false}）⇒ 整条自审链不判。 */
        SKIPPED_DISABLED
    }

    /**
     * 闸门结论。
     *
     * @param tier     档位
     * @param failures 不通过原因（空即通过；可直接进事件 payload 与重跑指令）
     */
    public record Result(Tier tier, List<String> failures) {

        public Result {
            failures = failures == null ? List.of() : List.copyOf(failures);
        }

        public boolean passed() {
            return failures.isEmpty();
        }

        /** 供重跑指令与事件使用的一行摘要（已截断）。 */
        public String summary() {
            return truncate(String.join("；", failures));
        }
    }

    /** 摘要总长上限。 */
    private static final int MAX_SUMMARY_CHARS = 500;

    /**
     * 「可执行交付物」关键词（小写匹配）。
     *
     * <p>刻意只列「交付物本身是脚本/程序」的信号，不列「运行 / 执行」这类动词——实测任务要求里
     * 「执行摘要」「已执行」高频出现，会系统性把文档类判成档 1。需要放宽时只加名词，
     * 每加一个词都要能指出「交付物就是该形态」的真机样本。</p>
     */
    private static final List<String> EXECUTABLE_DELIVERABLE_KEYWORDS = List.of(
            "脚本", "可执行文件", "命令行工具", ".sh", ".py", ".js", ".ts", ".java", ".jar");

    private SelfReviewGate() {
    }

    /** 开关关闭时的结论（{@link Tier#SKIPPED_DISABLED}，恒通过 ⇒ 不重跑、不记失败事件）。 */
    public static Result disabled() {
        return new Result(Tier.SKIPPED_DISABLED, List.of());
    }

    /** 执行提示词里「交付物」声明的行首标记（`AgentRuntimeContextAssembler` 装配）。 */
    private static final String DELIVERABLE_MARKER = "交付物要求:";

    /**
     * 取「交付物声明」文本用于形态判定——<b>只取那一行，不扫整段提示词</b>。
     *
     * <p>真机口径（2026-11-04 第三轮）：执行提示词里平台指令本身就含「沙箱」「校验脚本」等字样，
     * 整段扫描会让<b>每一个</b>子任务都命中 {@code 脚本} ⇒ 全部落 {@link Tier#SANDBOX_REQUIRED}，
     * {@link Tier#EVIDENCE_FORM_ONLY}（文档类只校验形态）在真实链路中永远到不了，于是每个子任务
     * 都被追加一次收尾轮、并在模型伪造证据时留下 {@code self_review_failed}——观测与成本双失真。</p>
     *
     * <p>找不到声明行时返回空串：此时按档 2（形态校验）处理，属于「宁可少拦」的一侧，
     * 与 {@link #EXECUTABLE_DELIVERABLE_KEYWORDS} 的取舍一致。</p>
     */
    public static String deliverableDeclaration(String userText) {
        if (userText == null || userText.isEmpty()) {
            return "";
        }
        int start = userText.indexOf(DELIVERABLE_MARKER);
        if (start < 0) {
            return "";
        }
        int end = userText.indexOf('\n', start);
        return (end < 0 ? userText.substring(start) : userText.substring(start, end)).trim();
    }

    /**
     * 判定闸门。
     *
     * @param sandboxEnabled   沙箱是否启用（未启用 ⇒ {@link Tier#SKIPPED_NO_SANDBOX}）
     * @param hasSubTask       是否有子任务上下文（无 ⇒ {@link Tier#SKIPPED_NO_SUB_TASK}）
     * @param parsed           围栏 {@code EVIDENCE} 解析结果
     * @param verified         与台账的对账结果
     * @param ledger           该子任务的台账记录（成功性判据的事实源）
     * @param taskRequirement  本轮任务要求文本（判交付物形态）
     */
    public static Result evaluate(boolean sandboxEnabled, boolean hasSubTask,
                                  SelfReviewEvidenceParser.Parsed parsed,
                                  SelfReviewEvidenceVerifier.Result verified,
                                  List<SandboxCallLedger.Entry> ledger,
                                  String taskRequirement) {
        if (!sandboxEnabled) {
            return new Result(Tier.SKIPPED_NO_SANDBOX, List.of());
        }
        if (!hasSubTask) {
            return new Result(Tier.SKIPPED_NO_SUB_TASK, List.of());
        }
        boolean sandboxRequired = requiresSandboxEvidence(taskRequirement, parsed);
        List<String> failures = new ArrayList<>();
        if (parsed == null || parsed.status() == SelfReviewEvidenceParser.Status.MALFORMED) {
            failures.add("围栏 EVIDENCE 证据块不合契约（非法 JSON / 非数组 / 缺键 / 类型不符），无法核对自审证据");
            return new Result(sandboxRequired ? Tier.SANDBOX_REQUIRED : Tier.EVIDENCE_FORM_ONLY, failures);
        }
        if (!sandboxRequired) {
            // 档 2：形态合法即通过（缺失 / 空数组只作观测，不拦截文档类交付物）
            return new Result(Tier.EVIDENCE_FORM_ONLY, List.of());
        }
        if (!parsed.usable()) {
            failures.add("交付物声明为可执行形态或围栏声明了验证命令，但未提供任何 sandbox_exec 证据");
        }
        if (verified != null && verified.outcome() == SelfReviewEvidenceVerifier.Outcome.MISMATCH) {
            failures.add("自报证据与平台台账不一致（self_reported_mismatch）："
                    + String.join(" / ", verified.mismatches()));
        }
        if (!hasSuccessfulExecution(ledger)) {
            failures.add("台账中没有 exitCode=0 且未截断（truncated=false）的沙箱执行记录");
        }
        return new Result(Tier.SANDBOX_REQUIRED, failures);
    }

    /**
     * 是否要求一手沙箱证据：交付物形态命中关键词，<b>或</b>模型已在围栏里自填了证据条目。
     *
     * <p>后半个条件是关键：自填了 {@code EVIDENCE} 就等于声明「我跑过命令」，声明必须兑现，
     * 否则「写了证据块但台账里没有对应执行」这条病理（`SR-2` 的 mismatch 形态）只会被记成观测事件、
     * 不会被拦下。</p>
     */
    static boolean requiresSandboxEvidence(String taskRequirement, SelfReviewEvidenceParser.Parsed parsed) {
        if (parsed != null && parsed.status() == SelfReviewEvidenceParser.Status.PARSED
                && !parsed.entries().isEmpty()) {
            return true;
        }
        if (taskRequirement == null || taskRequirement.isBlank()) {
            return false;
        }
        String text = taskRequirement.toLowerCase(Locale.ROOT);
        for (String keyword : EXECUTABLE_DELIVERABLE_KEYWORDS) {
            if (text.contains(keyword)) {
                return true;
            }
        }
        return false;
    }

    /** 台账里是否有「执行成功且输出完整」的记录（{@code exitCode==0 && !truncated}）。 */
    static boolean hasSuccessfulExecution(List<SandboxCallLedger.Entry> ledger) {
        if (ledger == null) {
            return false;
        }
        for (SandboxCallLedger.Entry entry : ledger) {
            if (entry != null && entry.exitCode() == 0 && !entry.truncated()) {
                return true;
            }
        }
        return false;
    }

    private static String truncate(String text) {
        if (text == null) {
            return "";
        }
        String value = text.length() <= MAX_SUMMARY_CHARS ? text : text.substring(0, MAX_SUMMARY_CHARS) + "...";
        return value;
    }
}
