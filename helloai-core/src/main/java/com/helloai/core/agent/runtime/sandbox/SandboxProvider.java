package com.helloai.core.agent.runtime.sandbox;

/**
 * 沙箱提供方（P0-C Phase 4；AgentRuntime 八件套成员之一）。
 *
 * <p>契约：从 {@link SandboxContext} 解析执行环境 + 诚实隔离策略，返回 {@link Sandbox}。
 * 第一阶段只定义契约（不实现 Docker / K8s 完整安全体系，P2/P3 后置）；
 * 当前实现复用既有 {@code ExecutionEnvironmentProvider}，策略一律不标 ISOLATED。</p>
 *
 * <p>边界：Sandbox 只回答「在哪执行 + 隔离到什么程度」，不承载调度 / 状态机 /
 * 编排职责；具体隔离能力（文件/网络/进程/资源/凭证）由策略诚实表达。</p>
 */
public interface SandboxProvider {

    /**
     * 解析沙箱。
     *
     * @param context 解析上下文（整个 context 或 accessType 为 null 时返回 null）
     * @return 解析结果；accessType 为 null 或无环境命中时返回 null
     *         （契约：不抛异常，与 ExecutionEnvironmentProvider 的 null 语义一致）
     */
    Sandbox resolve(SandboxContext context);
}
