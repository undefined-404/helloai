package com.helloai.core.agent.runtime.sandbox;

/**
 * 沙箱执行通道（S-7，沙箱执行链设计方案 §3.3）。
 *
 * <p>单一执行通道——「把命令交给沙箱执行并返回统一结果」。屏蔽 OpenSandbox SDK 细节，
 * 供 {@code InternalLlmExecutorTools}（{@code internal_run_in_sandbox} 工具）与
 * {@code FinalReportFidelityGate}（S-9 机械判定）调用。</p>
 *
 * <p>边界：只回答「命令在沙箱内执行得怎样」，不承载判定（pass/fail 由消费方负责）；
 * 失败/超时/异常都返回结构化 {@link ExecuteResponse}，不抛异常（与
 * {@code SandboxProvider.resolve} 的 null 语义一致的容错哲学）。</p>
 *
 * <p>实现契约（见 {@code OpenSandboxExecutionService}）：沙箱未启用 ⇒ 返回
 * {@code ExecuteResponse("sandbox execution disabled", EXIT_CODE_ERROR, false)}
 * （工具已摘除，正常情况下不会被调用；此处是纵深防御）。</p>
 */
public interface SandboxExecutionService {

    /**
     * 在沙箱内执行一次命令，返回统一执行结果。
     *
     * @param subTaskId      调用方子任务 ID（上下文日志定位；可为 null）
     * @param command        要在沙箱内执行的命令（bash -lc 语义）或脚本正文；null/空白 ⇒ 结构化失败
     * @param timeoutSeconds 命令超时秒数；null / <=0 ⇒ 默认 30；上限 300（超出钳制到上限）
     * @return 统一执行结果（超时 / 失败 / SDK 异常也结构化返回，不抛异常）
     */
    ExecuteResponse execute(Long subTaskId, String command, Integer timeoutSeconds);

    /**
     * 释放某子任务的<b>复用</b>工作沙箱（`ADR-005` 生命周期出口，2026-10-25）。
     *
     * <p>对齐 deepseek-harness `dsh-tool-bash-persistent` 的口径：同一子任务内的多次调用
     * 复用同一沙箱（写入的文件跨调用保留，模型可 `sed -i` 定点修改而非整篇重写）；
     * 但<b>子任务执行结束即释放</b>——避免「上一次尝试的残留文件影响下一次尝试」，
     * 也保证「不跨子任务共享」。</p>
     *
     * <p>由 {@code LocalExecutionCommandConsumer#runViaRuntime} 在 turn 结束时调用；
     * 幂等（未登记 / null 键静默返回）。实现若不支持复用可置为空实现。</p>
     *
     * <p><b>并发假设</b>：调用方须保证与 {@link #execute} <b>不并发</b>（当前实现假设
     * 「同一子任务的消费是串行的」，`release` 落在同一消费线程的 `finally` 里）。
     * 若将来出现同子任务并发消费，实现需先取槽锁再销毁，否则可能杀掉在跑命令的工作区。</p>
     *
     * @param subTaskId 子任务 ID；null 或未登记时静默返回
     */
    default void release(Long subTaskId) {
        // 默认空实现：不支持复用的实现无需关心释放语义
    }

    /**
     * 回读该子任务工作沙箱里<b>篇幅最大的一份 {@code .md} 正文</b>（2026-10-25，`LOG-20261025-010`）。
     *
     * <p><b>为什么需要这条通道</b>：平台只采集<b>对话正文</b>（{@code AgentExecutionResult.output}），
     * 沙箱文件从不自动上传。实测 MiniMax-M3 两次执行都是「20 轮全在沙箱里写 {@code /workspace/draft.md}、
     * 对话区只剩 604 字节写作过程叙述」⇒ 终态 {@code STOP} + 围栏齐全 ⇒ 假成功 ⇒ 评审 3 轮全驳回 ⇒ 死信。
     * 正文其实早就写出来了，只是没交出去。本方法就是在 turn 末把它「捞回来」的通道。</p>
     *
     * <p><b>只读语义</b>：<b>不创建</b>沙箱（无活跃工作区直接返回 {@code null}）——
     * 为读一个文件白建沙箱要 3 秒 + 一份资源，且必然读到空目录。</p>
     *
     * <p><b>不承载判定</b>：方法只回答「工作区里最大那份 .md 是什么」，回传与否、是否构成
     * 可交付正文由调用方按 {@code DeliverableDraft} 判定（与 {@link #execute} 一致的边界）。</p>
     *
     * @param subTaskId 子任务 ID（null ⇒ 返回 null）
     * @return 文件正文；无工作区 / 无 {@code .md} / 读取失败 ⇒ {@code null}
     */
    default String readWorkspaceFile(Long subTaskId) {
        return null;
    }
}
