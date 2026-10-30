package com.helloai.common.constant;

/**
 * 对话内技能执行的状态（{@code chat_skill_run.status}，V128）。
 *
 * <p>与 {@link ChatAttachmentParseStatus} 分表而非同列：附件解析是「文件能不能读出正文」，
 * 技能执行是「一次执行有没有跑完」，两者语义不重叠，同列会出现两种含义的判据漂移。</p>
 *
 * <p>状态机：{@code PENDING → RUNNING → SUCCEEDED | FAILED | CANCELLED}。
 * {@code PENDING → RUNNING} 是<b>条件更新</b>，是执行幂等的唯一闸门：
 * 重复投递（重试、worker 重跑）只有第一次能把状态推过去。</p>
 */
public enum SkillExecutionStatus {

    /** 已提交，等待 worker 领取。 */
    PENDING,

    /** 执行中（worker 已领取）。 */
    RUNNING,

    /** 执行成功，产物已落成会话附件。 */
    SUCCEEDED,

    /** 执行失败（模型抽不出输入 / 渲染失败 / 产物读不回 / 超时）。 */
    FAILED,

    /** 已取消（用户显式取消，或同会话互斥拒绝）。 */
    CANCELLED;

    /** 是否终态（终态不再变化）。 */
    public boolean terminal() {
        return this == SUCCEEDED || this == FAILED || this == CANCELLED;
    }
}
