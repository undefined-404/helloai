package com.helloai.core.agent.runtime.sandbox;

/**
 * 空闲工作区回收通道（`SL-7`）。
 *
 * <p><b>解决的问题</b>：槽池的空闲淘汰只在「同键再次访问」时发生（{@code SandboxSlotPool#acquire}），
 * 一个再也不会被访问的键（任务被放弃、会话结束）永远不会再触发淘汰。此前能把 TTL 写小，
 * 正是靠「写小仍会被下次访问清掉、写小也就是让容器 15 分钟死掉」在两者之间硬撑；
 * 容器保活拉到 8 小时后，<b>没有后台回收就等于泄漏</b>。本通道把「回收」从访问路径上摘出来，
 * 由定时任务周期驱动。</p>
 *
 * <p><b>职责边界</b>：只回答「哪些槽可以销毁、销毁了几个」，不判断任务状态、不发布事件——
 * 任务终态回收仍归 {@link SandboxExecutionService#releaseByTask(Long)}（事件驱动，语义更准）。
 * 本通道是「事件丢失 / 进程异常 / 无终态释放点」的兜底。</p>
 *
 * <p><b>调度位置</b>：实现放在 core（可单测），{@code @Scheduled} 触发放在 {@code helloai-job}
 * （`CODE_STYLE` §22）。</p>
 */
public interface SandboxIdleReaper {

    /**
     * 执行一轮空闲回收。
     *
     * <p>实现约定：任务级槽按 {@code keep-alive} 阈值回收（任务可能长时间等人工审核，
     * 不能按 15 分钟空闲销毁）；会话/子任务等无终态释放点的作用域按 {@code idle-ttl} 回收；
     * 命令正在执行的槽本轮跳过。任何异常都应在本方法内消化为「本轮零回收」，
     * 不得抛给调度线程——一次失败的回收不该让定时任务停摆。</p>
     *
     * @return 本轮回收计数
     */
    ReapReport reapIdle();

    /**
     * 一轮回收的计数快照。
     *
     * @param reaped 已销毁的槽数
     * @param busy   因命令在跑而跳过的槽数
     * @param kept   未到空闲阈值的槽数
     */
    record ReapReport(int reaped, int busy, int kept) {

        /** 无动作的一轮（沙箱未启用 / 池为空）。 */
        public static ReapReport empty() {
            return new ReapReport(0, 0, 0);
        }
    }
}
