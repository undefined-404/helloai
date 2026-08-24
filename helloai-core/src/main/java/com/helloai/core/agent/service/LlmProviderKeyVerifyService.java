package com.helloai.core.agent.service;

import java.util.Map;

/**
 * LLM Provider API Key 连通性验证服务。
 *
 * <p>系统设置页「添加模型」保存 API Key 后，直接以 raw HTTP 向 Provider 端点发送
 * 最小 chat 请求（max_tokens=1）探测 Key 是否有效、端点是否可达。
 * 不复用 ChatClient 工厂，避免污染会话模型缓存并规避其默认长超时。</p>
 *
 * <p>失败语义：所有异常均收敛为 {@code success=false} + 可读 message，绝不抛出，
 * 便于前端直接展示。</p>
 */
public interface LlmProviderKeyVerifyService {

    /**
     * 验证指定 Provider 的平台级 API Key。
     *
     * @param providerId llm_provider.id
     * @return 验证结果：success（Boolean）/ message（String）/ model（String）/ elapsedMs（Long）
     */
    Map<String, Object> verifyById(Long providerId);
}
