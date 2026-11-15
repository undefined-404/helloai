package com.helloai.core.review.support;

/**
 * 核验证据视图（`RV-6`）：核验 Prompt 真正消费的证据文本。
 *
 * <p><b>为什么单独抽出来</b>：单审/双审/抽检共用同一证据装配口径；驳回侧复判要在「返工已覆盖
 * 产出、附件已失效」之后仍能读到原判对象，所以需要把<b>判定当时看到的证据原文</b>冻结下来。
 * 冻结对象不是文件/附件引用（会失效），而是这些渲染结果本身——复判时直接复用，渲染出的
 * Prompt 与原判一致。</p>
 *
 * <p>字段与 {@code prompts/subtask-review.md} 的占位符一一对应：</p>
 * <ul>
 *     <li>{@link #executionOutput} → {@code {{EXECUTION_OUTPUT}}}</li>
 *     <li>{@link #attachmentList} → {@code {{ATTACHMENT_LIST}}}</li>
 *     <li>{@link #attachmentContent} → {@code {{ATTACHMENT_CONTENT}}}</li>
 *     <li>{@link #verificationSignal} → {@code {{VERIFICATION_SIGNAL}}}</li>
 *     <li>{@link #selfReviewSignal} → {@code {{SELF_REVIEW_SIGNAL}}}</li>
 *     <li>{@link #mechanicalCheck} → {@code {{MECHANICAL_CHECK}}}（`RV-10`）</li>
 *     <li>{@link #evidenceLedger} → {@code {{EVIDENCE_LEDGER}}}（`RV-8`）</li>
 * </ul>
 *
 * @param executionOutput   执行产出摘要（已按核验口径截断）
 * @param attachmentList    物化附件清单
 * @param attachmentContent 物化附件正文（按族配额截断并带截断标注）
 * @param verificationSignal 验证证据信号
 * @param selfReviewSignal  平台自审闸门信号
 * @param mechanicalCheck   表格类机械预检结论（`RV-10`）
 * @param evidenceLedger    附件注入台账（`RV-8`）
 */
public record ReviewEvidenceView(
        String executionOutput,
        String attachmentList,
        String attachmentContent,
        String verificationSignal,
        String selfReviewSignal,
        String mechanicalCheck,
        String evidenceLedger
) {

    public ReviewEvidenceView {
        executionOutput = executionOutput == null ? "" : executionOutput;
        attachmentList = attachmentList == null ? "" : attachmentList;
        attachmentContent = attachmentContent == null ? "" : attachmentContent;
        verificationSignal = verificationSignal == null ? "" : verificationSignal;
        selfReviewSignal = selfReviewSignal == null ? "" : selfReviewSignal;
        mechanicalCheck = mechanicalCheck == null ? "" : mechanicalCheck;
        evidenceLedger = evidenceLedger == null ? "" : evidenceLedger;
    }

    /** 兼容构造：仅五段证据（`RV-6` 历史调用点）。 */
    public ReviewEvidenceView(String executionOutput, String attachmentList, String attachmentContent,
                              String verificationSignal, String selfReviewSignal) {
        this(executionOutput, attachmentList, attachmentContent, verificationSignal, selfReviewSignal,
                "", "");
    }

    /** 证据总字符数（冻结体积上限判定用）。 */
    public int totalChars() {
        return executionOutput.length() + attachmentList.length() + attachmentContent.length()
                + verificationSignal.length() + selfReviewSignal.length()
                + mechanicalCheck.length() + evidenceLedger.length();
    }
}
