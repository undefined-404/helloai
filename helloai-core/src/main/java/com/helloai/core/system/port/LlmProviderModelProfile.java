package com.helloai.core.system.port;

import java.util.List;

/**
 * LLM Provider 模型「能力配置」只读快照（system 域对外契约）。
 *
 * <p><b>归属判据（CODE_STYLE §7.2 情形②）</b>：消费方 agent 域高于提供方 system 域
 * ⇒ 契约落【提供方】{@code system.port}；消费方不再 import {@code system.entity.LlmProviderModel}。</p>
 *
 * <p>仅暴露消费方（agent 域技能推导 / skill-options 端点）实际读取的两列技能白名单。</p>
 */
public record LlmProviderModelProfile(
        List<String> capabilitySkills,
        List<String> availableOptionalSkills
) {
}
