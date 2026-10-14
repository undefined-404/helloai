package com.helloai.core.system.port;

/**
 * LLM Provider 模型「能力配置」只读快照（system 域对外契约）。
 *
 * <p><b>归属判据（CODE_STYLE §7.2 情形②）</b>：消费方 agent 域高于提供方 system 域
 * ⇒ 契约落【提供方】{@code system.port}；消费方不再 import {@code system.entity.LlmProviderModel}。</p>
 *
 * <p><b>`M-9` 换内容</b>：由 {@code (capabilitySkills, availableOptionalSkills)} 两列白名单
 * 改为单一 {@link ModelCapabilities} 谓词（两字段的唯一消费方即本次切换的消费方；保留会造
 * 「V52 列的代码幽灵」）。注意与 `M-5` 的「追加末位字段」不同：那是新增能力，此处是<b>退役</b>。</p>
 */
public record LlmProviderModelProfile(
        ModelCapabilities capabilities
) {
}
