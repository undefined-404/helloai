package com.helloai.core.agent.browser.gateway;

import com.helloai.core.agent.domain.AgentTask;
import com.helloai.core.agent.entity.Agent;

/**
 * Browser Agent 推送桥接契约（N-003，C3-S2/S3）。
 *
 * <p>形态 A（外部 AI 服务 + Playwright 桥接）：平台把 sub_task 执行命令推送给外部
 * Browser Agent（自持浏览器），外部服务执行网页任务后回传结果（output 文本，
 * 可为 manifest JSON 产物协议，平台 ExecutionOutputParser 物化复用）。
 * 本接口 = 推送通道抽象，供 BrowserAgentExecutor 复用，可单测 mock。</p>
 */
public interface BrowserAgentGateway {

    /**
     * 推送执行命令并同步等待外部 Browser Agent 结果。
     *
     * @return 外部服务回传的 output 文本（可为 manifest JSON：{summary, files[]}）
     * @throws Exception 推送/等待/超时失败（由上层契约化处理）
     */
    String push(Agent agent, AgentTask task) throws Exception;
}
