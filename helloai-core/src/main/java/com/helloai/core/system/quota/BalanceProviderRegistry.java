package com.helloai.core.system.quota;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 余额查询 provider 注册表：按 key 路由。
 *
 * <p>遍历时**不写 try/catch**（fetch 不抛异常是契约）；supports=false 的 provider 会被跳过，
 * 保证「有凭据但无接口」的供应商（如通义）不出现、也不报错。</p>
 */
public class BalanceProviderRegistry {

    private final Map<String, BalanceProvider> providers;

    public BalanceProviderRegistry(List<BalanceProvider> providerList) {
        Map<String, BalanceProvider> map = new LinkedHashMap<>();
        for (BalanceProvider p : providerList) {
            map.put(p.key(), p);
        }
        this.providers = Collections.unmodifiableMap(map);
    }

    public BalanceProvider get(String providerKey) {
        return providers.get(providerKey);
    }

    /** 所有已注册的 provider（插入序）。 */
    public List<BalanceProvider> all() {
        return List.copyOf(providers.values());
    }
}