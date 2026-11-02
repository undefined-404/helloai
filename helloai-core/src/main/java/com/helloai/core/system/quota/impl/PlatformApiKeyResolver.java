package com.helloai.core.system.quota.impl;

import com.helloai.core.system.port.PlatformApiKeyPort;
import com.helloai.core.system.quota.BalanceCredentialResolver;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * deepseek / moonshot / minimax 的余额查询 Key：经 {@link PlatformApiKeyPort} 取平台级
 * ACTIVE 凭证（vault 明文 → secretRef 环境变量 → yml 兜底）。
 *
 * <p><b>为什么经端口而非直接调 agent Service</b>：余额查询在 system 域，直接依赖
 * {@code PlatformProviderConfigService} 会形成 {@code system → agent} 反向依赖
 * （CODE_STYLE §6）。端口由消费方 system 定义、agent 域实现，取数优先级仍只有一处定义。</p>
 */
@Component
@RequiredArgsConstructor
public class PlatformApiKeyResolver implements BalanceCredentialResolver {

    private final PlatformApiKeyPort platformApiKeyPort;

    @Override
    public Optional<String> resolve(String providerKey) {
        return platformApiKeyPort.resolveApiKey(providerKey);
    }
}
