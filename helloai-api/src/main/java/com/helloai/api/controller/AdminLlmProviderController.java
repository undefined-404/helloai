package com.helloai.api.controller;

import com.helloai.api.dto.admin.CreateLlmProviderRequest;
import com.helloai.api.dto.admin.LlmProviderResponse;
import com.helloai.api.dto.admin.UpdateLlmProviderRequest;
import com.helloai.common.base.R;
import com.helloai.core.agent.chat.PlatformProviderConfigService;
import com.helloai.core.system.entity.LlmProvider;
import com.helloai.core.system.service.LlmProviderQueryService;
import com.helloai.core.system.service.LlmProviderService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;

/**
 * 平台级 LLM Provider 动态管理（方案B）。
 *
 * <p>CRUD 端点列表：</p>
 * <ul>
 *   <li>GET    /api/admin/llm-providers/list           列表（含禁用）</li>
 *   <li>GET    /api/admin/llm-providers/getById/{id}   详情</li>
 *   <li>POST   /api/admin/llm-providers               新增</li>
 *   <li>PUT    /api/admin/llm-providers/updateById/{id} 修改（局部更新）</li>
 *   <li>DELETE /api/admin/llm-providers/deleteById/{id} 删除（内置不可）</li>
 *   <li>PUT    /api/admin/llm-providers/toggleById/{id} 启用 / 禁用</li>
 *   <li>PUT    /api/admin/llm-providers/{id}/api-key  写入 API Key（走 credential_vault）</li>
 * </ul>
 *
 * <p>与 {@link AdminProviderConfigController}（旧 /api/admin/platform/providers）共存，
 * 前端 Settings.vue 优先使用本套端点；旧端点保留用于兼容既有脚本与老页面。</p>
 */
@Slf4j
@RestController
@RequestMapping("/api/admin/llm-providers")
@RequiredArgsConstructor
public class AdminLlmProviderController {

    private final LlmProviderService providerService;
    private final LlmProviderQueryService queryService;
    private final PlatformProviderConfigService platformProviderConfigService;

    @GetMapping("/list")
    public R<List<LlmProviderResponse>> list() {
        List<LlmProviderResponse> items = queryService.listAll().stream()
                .map(this::toResponse)
                .collect(Collectors.toList());
        return R.ok(items);
    }

    @GetMapping("/getById/{id}")
    public R<LlmProviderResponse> getById(@PathVariable("id") Long id) {
        LlmProvider p = providerService.getById(id);
        if (p == null) {
            return R.fail("Provider 不存在");
        }
        return R.ok(toResponse(p));
    }

    @PostMapping("/")
    public R<LlmProviderResponse> create(@RequestBody @Valid CreateLlmProviderRequest req) {
        LlmProvider entity = new LlmProvider();
        entity.setProviderCode(req.getProviderCode().toLowerCase(Locale.ROOT));
        entity.setProviderName(req.getProviderName());
        entity.setProtocolType(req.getProtocolType().toUpperCase(Locale.ROOT));
        entity.setBaseUrl(req.getBaseUrl());
        entity.setDefaultModel(req.getDefaultModel());
        entity.setEnabled(req.getEnabled() != null ? req.getEnabled() : 1);
        entity.setBuiltin(0);
        entity.setSortOrder(req.getSortOrder() != null ? req.getSortOrder() : 100);
        entity.setExtraConfig(req.getExtraConfig());
        LlmProvider saved = providerService.create(entity);
        log.info("管理员创建 LLM Provider: code={}, name={}",
                saved.getProviderCode(), saved.getProviderName());
        return R.ok(toResponse(saved));
    }

    @PutMapping("/updateById/{id}")
    public R<Void> updateById(@PathVariable("id") Long id,
                              @RequestBody @Valid UpdateLlmProviderRequest req) {
        LlmProvider patch = new LlmProvider();
        if (req.getProviderName() != null) patch.setProviderName(req.getProviderName());
        if (req.getProtocolType() != null) {
            patch.setProtocolType(req.getProtocolType().toUpperCase(Locale.ROOT));
        }
        if (req.getBaseUrl() != null) patch.setBaseUrl(req.getBaseUrl());
        if (req.getDefaultModel() != null) patch.setDefaultModel(req.getDefaultModel());
        if (req.getEnabled() != null) patch.setEnabled(req.getEnabled());
        if (req.getSortOrder() != null) patch.setSortOrder(req.getSortOrder());
        if (req.getExtraConfig() != null) patch.setExtraConfig(req.getExtraConfig());
        providerService.update(id, patch);
        return R.ok();
    }

    @DeleteMapping("/deleteById/{id}")
    public R<Void> deleteById(@PathVariable("id") Long id) {
        providerService.deleteById(id);
        return R.ok();
    }

    @PutMapping("/toggleById/{id}")
    public R<Void> toggleById(@PathVariable("id") Long id) {
        LlmProvider p = providerService.getById(id);
        if (p == null) {
            return R.fail("Provider 不存在");
        }
        int newEnabled = Integer.valueOf(1).equals(p.getEnabled()) ? 0 : 1;
        LlmProvider patch = new LlmProvider();
        patch.setEnabled(newEnabled);
        providerService.update(id, patch);
        return R.ok();
    }

    /**
     * 写入（轮换）平台级 API Key——key 走 credential_vault（PLATFORM 级 AES-GCM 加密）。
     *
     * <p>请求体为纯字符串（apiKey 明文），使用 {@link String} 直接接收避免 Jackson 把数字等
     * 误识别为 JSON 节点。空值返回 400。</p>
     */
    @PutMapping("/{id}/api-key")
    public R<Void> saveApiKey(@PathVariable("id") Long id, @RequestBody String apiKey) {
        if (apiKey == null || apiKey.isBlank()) {
            return R.fail("apiKey 不能为空");
        }
        LlmProvider p = providerService.getById(id);
        if (p == null) {
            return R.fail("Provider 不存在");
        }
        platformProviderConfigService.saveApiKey(p.getProviderCode(), apiKey);
        log.info("管理员配置平台级 API Key 已生效: id={}, provider={}",
                id, p.getProviderCode());
        return R.ok();
    }

    private LlmProviderResponse toResponse(LlmProvider p) {
        LlmProviderResponse r = new LlmProviderResponse();
        r.setId(p.getId());
        r.setProviderCode(p.getProviderCode());
        r.setProviderName(p.getProviderName());
        r.setProtocolType(p.getProtocolType());
        // 当前生效 Base URL / 默认模型：DB > sys_config > yml（PlatformProviderConfigService 已聚合）
        r.setBaseUrl(platformProviderConfigService.getBaseUrl(p.getProviderCode()));
        r.setDefaultModel(platformProviderConfigService.getDefaultModel(p.getProviderCode()));
        r.setEnabled(p.getEnabled());
        r.setBuiltin(p.getBuiltin());
        r.setSortOrder(p.getSortOrder());
        r.setExtraConfig(p.getExtraConfig());
        r.setApiKeyConfigured(platformProviderConfigService.isApiKeyConfigured(p.getProviderCode()));
        r.setApiKeyMasked(platformProviderConfigService.maskApiKey(p.getProviderCode()));
        r.setApiKeyFromVault(platformProviderConfigService.isApiKeyFromVault(p.getProviderCode()));
        return r;
    }
}
