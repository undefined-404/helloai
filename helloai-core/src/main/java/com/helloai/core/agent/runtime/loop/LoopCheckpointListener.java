package com.helloai.core.agent.runtime.loop;

/**
 * 循环进度检查点回调（P0-C checkpoint；write-only 纪律）。
 *
 * <p>由 {@code ChatModelToolLoop} 在每轮循环边界（工具执行与回喂已完成、
 * 下一轮 LLM 未开始）触发；实现方（如执行会话落库）须 best-effort：
 * 回调抛出的异常由循环侧捕获告警，不阻断执行主链路（与 AgentEventRecorder
 * 事件 write-only 同纪律）。</p>
 */
@FunctionalInterface
public interface LoopCheckpointListener {

    /** 一轮循环完成时回调（每轮至多一次；入参为不可变进度快照）。 */
    void onCheckpoint(LoopCheckpoint checkpoint);
}
