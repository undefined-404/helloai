package com.helloai.core.review.support;

/**
 * 自动核验链路来源：单审 / 双审 / 抽检复审。
 *
 * <p>驱动核验对话流消息类型前缀（subtask_review_* / subtask_dual_review_* /
 * subtask_recheck_*），使执行对话流中三种链路可分辨：双审 = difficulty=HIGH
 * 双 Reviewer 并行共识（仅落一条 review_record）；抽检 = 已 APPROVED 记录换
 * Reviewer 复判（只度量不改状态）。</p>
 */
public enum ReviewChannel {

    /** 单审（默认路径，自动核验正常链路） */
    SINGLE,
    /** 双审（HIGH 难度双 Reviewer 并行，共识后落一条 review_record） */
    DUAL,
    /** 抽检复审（已 APPROVED 记录换 Reviewer 复判，只度量不改状态） */
    RECHECK;

    /** 按链路返回对话流消息类型：subtask_{review|dual_review|recheck}_{kind}。 */
    public String toolName(String kind) {
        return switch (this) {
            case SINGLE -> "subtask_review_" + kind;
            case DUAL -> "subtask_dual_review_" + kind;
            case RECHECK -> "subtask_recheck_" + kind;
        };
    }
}
