package com.helloai.core.planner.adapter;

import com.helloai.core.planner.search.WebSearchCredentialKeyStore;
import com.helloai.core.system.port.WebSearchApiKeyPort;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.Optional;

/**
 * {@link WebSearchApiKeyPort} 适配实现（G-026 端口反转配套）。
 *
 * <p>直接委托 {@link WebSearchCredentialKeyStore#resolveBochaApiKey()}，不重写
 * {@code sys_config} 的读取与解密——设置页写入、搜索调用、余额查询共用同一 Key 源。
 * 实现落在 planner 域是因为 Key 存取属本域的联网搜索能力，且 {@code planner → system}
 * 是 §6 允许的向下依赖。</p>
 */
@Service
@RequiredArgsConstructor
public class WebSearchApiKeyPortAdapter implements WebSearchApiKeyPort {

    private final WebSearchCredentialKeyStore webSearchCredentialKeyStore;

    @Override
    public Optional<String> resolveBochaApiKey() {
        String key = webSearchCredentialKeyStore.resolveBochaApiKey();
        return (key == null || key.isBlank()) ? Optional.empty() : Optional.of(key);
    }
}
