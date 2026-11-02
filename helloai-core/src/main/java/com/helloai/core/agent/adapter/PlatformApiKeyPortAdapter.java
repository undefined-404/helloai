package com.helloai.core.agent.adapter;

import com.helloai.core.agent.service.PlatformProviderConfigService;
import com.helloai.core.system.port.PlatformApiKeyPort;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.Optional;

/**
 * {@link PlatformApiKeyPort} 适配实现（G-026 端口反转配套）。
 *
 * <p>直接委托 {@link PlatformProviderConfigService#getApiKey(String)}，不复制
 * 「vault ACTIVE → secretRef 环境变量 → yml 兜底」的取数优先级——同 provider 多条
 * ACTIVE 时取哪条的口径仍只有一处定义。实现落在 agent 域是因为 Key 取数逻辑归本域，
 * 且 {@code agent → system} 是 §6 允许的向下依赖。</p>
 */
@Service
@RequiredArgsConstructor
public class PlatformApiKeyPortAdapter implements PlatformApiKeyPort {

    private final PlatformProviderConfigService platformProviderConfigService;

    @Override
    public Optional<String> resolveApiKey(String providerKey) {
        String key = platformProviderConfigService.getApiKey(providerKey);
        return (key == null || key.isBlank()) ? Optional.empty() : Optional.of(key);
    }
}
