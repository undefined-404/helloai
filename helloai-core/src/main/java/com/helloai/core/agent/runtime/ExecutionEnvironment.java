package com.helloai.core.agent.runtime;

import com.helloai.common.constant.AgentAccessType;

/**
 * 执行环境抽象（坑 4 预留，Phase 0 仅定义接口；Phase 1 Step 4 落位两个实现）。
 *
 * <p>定义「在哪里执行」的抽象：远程 Agent（MCP/REST）、本地进程，以及未来 Docker Sandbox。
 * Phase 1 Step 4 起由 {@link ExecutionEnvironmentProvider} 按 {@code Agent.accessType}
 * 解析并注入 {@link AgentContext#environment}；DockerSandbox 推迟到 P2、K8sSandbox
 * 推迟到 P3（执行方案坑 4 结论：当前真正缺的不是沙箱隔离，而是「谁在执行」的显式定义）。</p>
 */
public interface ExecutionEnvironment {

    /**
     * 环境名称（如 remote-agent / local-process / docker-sandbox），
     * 用于日志与路由标识，与 {@code AgentExecutor.getName()} 同风格的轻量标识。
     */
    String name();

    /**
     * 当前环境是否承接该接入类型的执行。
     *
     * <p>路由契约与 {@code AgentExecutorRouter} 同构：Provider 按 supports 过滤取首个命中。</p>
     *
     * @param accessType Agent 接入类型（可空：null 恒不匹配）
     */
    boolean supports(AgentAccessType accessType);
}
