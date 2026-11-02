package com.helloai.core.system.quota.impl;

import com.helloai.core.system.port.WebSearchApiKeyPort;
import com.helloai.core.system.quota.BalanceCredentialResolver;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * 博查余额查询 Key：经 {@link WebSearchApiKeyPort} 复用设置页写入的
 * {@code sys_config["web-search.bocha.api-key"]}（加密存储），与搜索调用同源。
 *
 * <p><b>为什么经端口而非直接调 planner KeyStore</b>：余额查询在 system 域，直接依赖
 * {@code WebSearchCredentialKeyStore} 会形成 {@code system → planner} 反向依赖
 * （CODE_STYLE §6）。端口由消费方 system 定义、planner 域实现，解密逻辑不重写。</p>
 */
@Component
@RequiredArgsConstructor
public class WebSearchApiKeyResolver implements BalanceCredentialResolver {

    private final WebSearchApiKeyPort webSearchApiKeyPort;

    @Override
    public Optional<String> resolve(String providerKey) {
        return webSearchApiKeyPort.resolveBochaApiKey();
    }
}
