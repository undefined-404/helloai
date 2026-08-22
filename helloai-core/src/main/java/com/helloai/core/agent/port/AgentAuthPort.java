package com.helloai.core.agent.port;

import com.helloai.common.base.BizException;
import com.helloai.core.agent.entity.Agent;

/**
 * Agent 认证端口（认证内核专用）。
 *
 * <p>按 §3.x 依赖方向红线：system 域（AuthService / 鉴权链路）不得直接依赖
 * agent 域实体与 Service，本端口定义在 agent 域、由 {@code AgentServiceImpl}
 * 实现，system 域及 api 拦截器只依赖本接口。</p>
 */
public interface AgentAuthPort {

    /**
     * 按 API Key 校验并取回 Agent（等保存储加密后走 hash 点查 + 解密比对 + 惰性迁移）。
     *
     * @param apiKey Agent 的 consumerToken（api_key）
     * @return Agent 实体
     * @throws BizException 401 当 Key 无效；403 当 Agent 已禁用
     */
    Agent validateApiKey(String apiKey);
}