package com.helloai.core.planner.skill;

import com.helloai.common.constant.SkillExecutionPhase;

/**
 * 技能执行的运行期控制口（SKX-13）。
 *
 * <p>存在的理由：执行链一旦跑在后台线程里，HTTP 请求就管不到它了，
 * 于是「跑到哪一步」「要不要停」这两件事必须由执行链自己上报与自查。</p>
 *
 * <p><b>为什么是协作式而不是强制中断</b>：执行链的每一步要么是一次 LLM 调用、要么是一条沙箱命令，
 * 强制中断意味着在 SDK 层做线程中断——而沙箱命令的中断语义由 SDK 决定、LLM 流的取消语义另有一套。
 * 协作式（阶段边界自查）的代价是「取消后最多多跑一个阶段」，换来的是不引入不可控的中断路径。</p>
 */
public interface SkillRunControl {

    /** 不做进度上报、也不中止的执行控制（同步调用方用）。 */
    SkillRunControl NOOP = new SkillRunControl() {
        @Override
        public void onPhase(SkillExecutionPhase phase) {
            // 无操作
        }

        @Override
        public boolean shouldAbort() {
            return false;
        }
    };

    /**
     * 推进阶段。
     *
     * @param phase 新阶段
     */
    void onPhase(SkillExecutionPhase phase);

    /**
     * 是否应当中止（已取消或已超时）。
     *
     * <p>实现方负责区分「取消」与「超时」——两者的对外文案不同。</p>
     */
    boolean shouldAbort();
}
