package com.helloai.api.controller;

import com.helloai.api.dto.admin.ProviderApiKeyRequest;
import com.helloai.api.dto.admin.ProviderConfigItem;
import com.helloai.api.dto.admin.ProviderSettingsRequest;
import com.helloai.common.base.R;
import com.helloai.core.agent.chat.LlmProviderCatalogService;
import com.helloai.core.agent.chat.PlatformProviderConfigService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.List;

/**
 * 平台级 LLM Provider 配置管理接口（先启动后配置）。
 *
 * <p>API Key 写入 credential_vault（PLATFORM 级，AES-GCM 加密），Base URL / 默认模型
 * 写入 sys_config；保存后实时生效无需重启。与现有 /api/admin/* 同风格，由
 * AuthInterceptor（WebMvcConfig）统一鉴权保护。</p>
 */
@Slf4j
@RestController
@RequestMapping("/api/admin/platform/providers")
@RequiredArgsConstructor
public class AdminProviderConfigController {

    private final PlatformProviderConfigService platformProviderConfigService;
    private final LlmProviderCatalogService llmProviderCatalogService;

    /**
     * 枚举全部已配置 provider 及其实时状态（不含 api-key 明文）。
     */
    @GetMapping("/list")
    public R<List<ProviderConfigItem>> list() {
        List<ProviderConfigItem> items = new ArrayList<>();
        for (LlmProviderCatalogService.ProviderCatalogItem catalog : llmProviderCatalogService.listProviders()) {
            ProviderConfigItem item = new ProviderConfigItem();
            item.setName(catalog.provider());
            item.setDefaultModel(platformProviderConfigService.getDefaultModel(catalog.provider()));
            item.setBaseUrl(platformProviderConfigService.getBaseUrl(catalog.provider()));
            item.setApiKeyConfigured(catalog.apiKeyConfigured());
            item.setApiKeyMasked(platformProviderConfigService.maskApiKey(catalog.provider()));
            item.setAvailable(catalog.available());
            item.setApiKeyFromVault(platformProviderConfigService.isApiKeyFromVault(catalog.provider()));
            items.add(item);
        }
        return R.ok(items);
    }

    /**
     * 写入（轮换）平台级 API Key，实时生效无需重启。
     */
    @PutMapping("/{provider}/api-key")
    public R<Void> saveApiKey(@PathVariable("provider") String provider,
                              @RequestBody ProviderApiKeyRequest req) {
        if (req == null || req.getApiKey() == null || req.getApiKey().isBlank()) {
            return R.fail("apiKey 不能为空");
        }
        platformProviderConfigService.saveApiKey(provider, req.getApiKey());
        log.info("管理员配置平台级 API Key 已生效: provider={}", provider);
        return R.ok();
    }

    /**
     * 更新 provider 的 Base URL / 默认模型（均可选；传空表示清除覆盖，回到 yml 默认）。
     */
    @PutMapping("/{provider}/settings")
    public R<Void> saveSettings(@PathVariable("provider") String provider,
                                @RequestBody ProviderSettingsRequest req) {
        if (req == null || (req.getBaseUrl() == null && req.getDefaultModel() == null)) {
            return R.fail("baseUrl / defaultModel 至少提供一个");
        }
        platformProviderConfigService.saveSettings(provider, req.getBaseUrl(), req.getDefaultModel());
        log.info("管理员更新平台级 Provider 设置: provider={}", provider);
        return R.ok();
    }
}
