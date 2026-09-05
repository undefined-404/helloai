package com.helloai.core.agent.runtime;

import com.helloai.common.constant.AgentAccessType;
import org.springframework.stereotype.Component;

/**
 * 远程 Agent 执行环境（Phase 1 Step 4，坑 4 结论两个实现之一）。
 *
 * <p>执行发生在平台外的外部 Agent 自有环境（Qoder / Trae / Codex / Claude Code 等
 * CLI_CLIENT，经 MCP-over-SSE 拉取任务并回传结果）；WEB_BROWSER（网页版 AI 经
 * Playwright 桥接，执行主体为外部 AI 服务）同归本环境。平台不托管其进程，
 * 仅以 MCP/REST 单向通信（外部 Agent 单向架构，坑 4：远程 Agent 已有此现实）。</p>
 */
@Component
public class RemoteAgentEnvironment implements ExecutionEnvironment {

    /** 环境标识（与 {@code AgentExecutor.getName()} 同风格的轻量路由标识）。 */
    public static final String NAME = "remote-agent";

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public boolean supports(AgentAccessType accessType) {
        // CLI_CLIENT：外部 CLI Agent 在自己环境执行；WEB_BROWSER：网页版 AI 桥接（外部 AI 服务执行）
        return accessType == AgentAccessType.CLI_CLIENT
                || accessType == AgentAccessType.WEB_BROWSER;
    }
}
