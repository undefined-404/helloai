package com.helloai.core.system.quota.impl;

import com.helloai.core.system.quota.BalanceCredentialResolver;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 凭证来源注册表：按 provider key 路由到对应 resolver。
 *
 * <p>deepseek / moonshot / minimax → {@link PlatformApiKeyResolver}（平台级 Key）；
 * bocha → {@link WebSearchApiKeyResolver}（联网搜索 Key）。两个 resolver 都经
 * {@code system.port} 取数，不反向依赖 agent / planner。</p>
 */
@Component
public class BalanceCredentialResolverRegistry {

    private final Map<String, BalanceCredentialResolver> byKey;

    public BalanceCredentialResolverRegistry(
            PlatformApiKeyResolver platformApiKeyResolver,
            WebSearchApiKeyResolver webSearchApiKeyResolver) {
        this.byKey = Map.of(
                "deepseek", platformApiKeyResolver,
                "moonshot", platformApiKeyResolver,
                "minimax", platformApiKeyResolver,
                "bocha", webSearchApiKeyResolver
        );
    }

    /** 取 provider 对应的明文 key；provider 未知/无凭据返回空。 */
    public Optional<String> resolve(String providerKey) {
        BalanceCredentialResolver resolver = byKey.get(providerKey);
        return resolver == null ? Optional.empty() : resolver.resolve(providerKey);
    }
}