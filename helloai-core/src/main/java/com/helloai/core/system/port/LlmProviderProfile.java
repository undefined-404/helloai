package com.helloai.core.system.port;

/**
 * LLM Provider 只读快照（system 域自有的对外数据契约）。
 *
 * <p><b>归属判据（CODE_STYLE §7.2 情形②）</b>：消费方 agent 域（依赖链下标 3）【高于】
 * 提供方 system 域（下标 4），故读契约落【提供方】{@code system.port}；
 * 消费方 {@code agent → system.port} 为顺向合法，从此不再 import {@code system.entity.LlmProvider}。</p>
 *
 * <p><b>字段只纳入消费方实际读取项</b>：{@code providerCode} / {@code providerName} /
 * {@code baseUrl} / {@code defaultModel} / {@code protocolType} / {@code enabled}
 * （agent 域 chat 工厂、Catalog、KeyVerify、PlatformProviderConfig、
 * ExecutorIssueResolutionAssessor 实测并集）。</p>
 */
public record LlmProviderProfile(
        String providerCode,
        String providerName,
        String baseUrl,
        String defaultModel,
        String protocolType,
        Integer enabled
) {
}
