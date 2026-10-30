package com.helloai.common.constant;

/**
 * 对话内技能执行的阶段（{@code chat_skill_run.phase}，V128）。
 *
 * <p>存在的唯一理由：让「还在跑」这件事<b>可见</b>。技能执行实测 377s，
 * 用户若只看到一个转圈，等价于无反馈；阶段名把这条链路拆成可预期的几步。</p>
 *
 * <p>阶段划分对着 {@code ConversationSkillRunner} 的实际步骤切，不按「感觉」切：
 * 生成输入（可能两轮）→ 渲染（validate + deliver）→ 自检（check）→ 回读导出。</p>
 */
public enum SkillExecutionPhase {

    /** 生成结构化输入：模型读技能正文与 schema，产出图表 spec。 */
    SPEC,

    /** 渲染：把 spec 写进沙箱并跑 validate + deliver。 */
    RENDER,

    /** 自检：跑 check --require-provenance，核对产物来源与构图。 */
    CHECK,

    /** 回读导出：读回产物字节、落对象存储、登记会话附件。 */
    EXPORT,

    /** 终态（成功或失败）。 */
    DONE;

    /** 阶段的可读说明（给前端展示）。 */
    public String label() {
        return switch (this) {
            case SPEC -> "正在生成图表输入";
            case RENDER -> "正在沙箱中渲染";
            case CHECK -> "正在自检产物";
            case EXPORT -> "正在回读并导出产物";
            case DONE -> "已结束";
        };
    }
}
