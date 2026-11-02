package com.helloai.core.system.port;

import java.util.Optional;

/**
 * 平台级 LLM API Key 读取端口（G-026 端口反转配套，CODE_STYLE §7.2 情形①）。
 *
 * <p>余额查询在 system 域，需要「某 provider 当前生效的平台 Key」；取数优先级
 * （vault ACTIVE → secretRef 环境变量 → yml 兜底）属 agent 域
 * {@code PlatformProviderConfigService}。若 system 直接 import agent.service，即构成
 * {@code system → agent} 反向依赖（CODE_STYLE §6 禁止）。</p>
 *
 * <p>故端口定义在消费方 system，由提供方 agent 的 {@code PlatformApiKeyPortAdapter}
 * 实现：{@code agent → system} 是顺向依赖。端口只交「目标值」，不暴露 vault / yml
 * 的取数细节。</p>
 */
public interface PlatformApiKeyPort {

    /** 返回可直接放进 Authorization 头的明文 Key；未配置或为空串返回 {@link Optional#empty()}。 */
    Optional<String> resolveApiKey(String providerKey);
}
