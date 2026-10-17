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
}
