package com.helloai.core.agent.runtime;

/**
 * 执行环境抽象（坑 4 预留，Phase 0 仅定义接口不提供实现）。
 *
 * <p>定义「在哪里执行」的抽象：远程 Agent（MCP/REST）、本地进程，以及未来 Docker Sandbox。
 * Phase 0 阶段 {@link AgentContext#environment} 恒为 null（调用方不得依赖）；
 * Phase 1 提供 RemoteAgentEnvironment / LocalProcessEnvironment 两种实现，
 * DockerSandbox 推迟到 P2、K8sSandbox 推迟到 P3（执行方案坑 4 结论）。</p>
 */
public interface ExecutionEnvironment {

    /**
     * 环境名称（如 remote-agent / local-process / docker-sandbox），
     * 用于日志与路由标识，与 {@code AgentExecutor.getName()} 同风格的轻量标识。
     */
    String name();
}