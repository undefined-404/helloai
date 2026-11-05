package com.helloai.core.system.port;

/**
 * LLM 模型注册项的只读快照（system 域对外契约）。
 *
 * <p><b>归属判据（CODE_STYLE §7.2 情形②）</b>：消费方为 agent 域（下标 4，高于 system 下标 5）
 * ⇒ 契约落【提供方】{@code system.port}；消费方不 import {@code system.entity.LlmProviderModel}，
 * 避免跨域实体泄漏（守卫组「跨域实体泄漏」）。</p>
 *
 * <p><b>字段只纳入消费方实际读取项</b>：{@code providerCode} / {@code modelName} /
 * {@code enabled} / {@code defaultModel} / {@code sortOrder}——足以拼出
 * {@code provider:model} 形式的 {@code modelType}，并判定「是否启用 / 是否默认」，</p>
 *
 * <p><b>刻意不含</b> {@code capabilities}：能力谓词已有独立契约
 * {@link LlmProviderModelProfile}（`M-9` 裁定「一列一个消费方」），在此重复携带会让
 * 两份能力快照并存、必分叉（判据「同一事实双写必分叉」）。</p>
 *
 * <p>{@code enabled} 以 {@code boolean} 承载（DB 列是 {@code SMALLINT} 0/1）：
 * 消费方只做「是否可选」的布尔判定，把 {@code Integer 0/1} 的归一责任留在提供方，
 * 避免每个消费点各写一遍 {@code Integer.valueOf(1).equals(...)}。</p>
 *
 * @param providerCode 供应商编码（小写，如 deepseek）
 * @param modelName    模型名（如 deepseek-v4-flash）
 * @param enabled      是否启用（注册表内的「可用」位）
 * @param defaultModel 是否为该供应商的默认模型
 * @param sortOrder    列表排序（数值越小越靠前，可空）
 */
public record LlmProviderModelRef(
        String providerCode,
        String modelName,
        boolean enabled,
        boolean defaultModel,
        Integer sortOrder
) {

    /** 拼 {@code modelType}（{@code provider:model}），与 {@code AgentProviderResolver} 解析口径一致。 */
    public String modelType() {
        return providerCode + ":" + modelName;
    }
}
