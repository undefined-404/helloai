package com.helloai.api.controller;

import com.helloai.api.dto.admin.CreateLlmProviderRequest;
import com.helloai.api.dto.admin.LlmProviderModelResponse;
import com.helloai.api.dto.admin.LlmProviderResponse;
import com.helloai.api.dto.admin.UpdateLlmProviderRequest;
import com.helloai.common.base.R;
import com.helloai.core.agent.service.PlatformProviderConfigService;
import com.helloai.core.system.entity.LlmProvider;
import com.helloai.core.system.entity.LlmProviderModel;
import com.helloai.core.system.service.LlmProviderModelQueryService;
import com.helloai.core.system.service.LlmProviderModelService;
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

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
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
 *   <li>GET    /api/admin/llm-providers/{id}/models/list           模型列表（含禁用）</li>
 *   <li>POST   /api/admin/llm-providers/{id}/models               添加模型</li>
 *   <li>PUT    /api/admin/llm-providers/{id}/models/saveAll       批量保存模型配置</li>
 *   <li>DELETE /api/admin/llm-providers/{id}/models/deleteByName/{modelName} 删除模型</li>
 *   <li>PUT    /api/admin/llm-providers/{id}/models/toggleByName/{modelName} 启用/禁用模型</li>
 *   <li>PUT    /api/admin/llm-providers/{id}/models/setDefaultByName/{modelName} 设为默认模型</li>
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
    private final LlmProviderModelService llmProviderModelService;
    private final LlmProviderModelQueryService llmProviderModelQueryService;

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

    @PostMapping
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

    // ══════════════════════════════════════════════════════════════
    //  模型管理端点
    // ══════════════════════════════════════════════════════════════

    /**
     * 查询 Provider 的模型列表（含禁用）。
     */
    @GetMapping("/{id}/models/list")
    public R<List<LlmProviderModelResponse>> listModels(@PathVariable("id") Long id) {
        LlmProvider p = providerService.getById(id);
        if (p == null) {
            return R.fail("Provider 不存在");
        }
        List<LlmProviderModelResponse> items = llmProviderModelQueryService.listByProviderId(id).stream()
                .map(this::toModelResponse)
                .collect(Collectors.toList());
        return R.ok(items);
    }

    /**
     * 添加单个模型到 Provider。
     */
    @PostMapping("/{id}/models")
    public R<LlmProviderModelResponse> addModel(@PathVariable("id") Long id,
                                                 @RequestBody Map<String, Object> body) {
        LlmProvider p = providerService.getById(id);
        if (p == null) {
            return R.fail("Provider 不存在");
        }
        String modelName = (String) body.get("modelName");
        Boolean isDefault = (Boolean) body.getOrDefault("isDefault", false);
        LlmProviderModel saved = llmProviderModelService.addModel(id, p.getProviderCode(), modelName, isDefault);
        return R.ok(toModelResponse(saved));
    }

    /**
     * 批量保存 Provider 的模型配置（多选）。
     */
    @PutMapping("/{id}/models/saveAll")
    public R<Void> saveAllModels(@PathVariable("id") Long id,
                                  @RequestBody Map<String, Object> body) {
        LlmProvider p = providerService.getById(id);
        if (p == null) {
            return R.fail("Provider 不存在");
        }
        @SuppressWarnings("unchecked")
        List<String> modelNames = (List<String>) body.get("modelNames");
        String defaultModel = (String) body.get("defaultModel");
        llmProviderModelService.saveProviderModels(id, p.getProviderCode(), modelNames, defaultModel);
        return R.ok();
    }

    /**
     * 删除模型。
     */
    @DeleteMapping("/{id}/models/deleteByName/{modelName}")
    public R<Void> deleteModel(@PathVariable("id") Long id,
                                @PathVariable("modelName") String modelName) {
        LlmProvider p = providerService.getById(id);
        if (p == null) {
            return R.fail("Provider 不存在");
        }
        llmProviderModelService.deleteModel(id, modelName);
        return R.ok();
    }

    /**
     * 启用/禁用模型。
     */
    @PutMapping("/{id}/models/toggleByName/{modelName}")
    public R<Void> toggleModel(@PathVariable("id") Long id,
                                @PathVariable("modelName") String modelName,
                                @RequestBody Map<String, Boolean> body) {
        LlmProvider p = providerService.getById(id);
        if (p == null) {
            return R.fail("Provider 不存在");
        }
        Boolean enabled = body.get("enabled");
        if (enabled == null) {
            return R.fail("enabled 不能为空");
        }
        llmProviderModelService.toggleModel(id, modelName, enabled);
        return R.ok();
    }

    /**
     * 设置默认模型。
     */
    @PutMapping("/{id}/models/setDefaultByName/{modelName}")
    public R<Void> setDefaultModel(@PathVariable("id") Long id,
                                    @PathVariable("modelName") String modelName) {
        LlmProvider p = providerService.getById(id);
        if (p == null) {
            return R.fail("Provider 不存在");
        }
        llmProviderModelService.setDefaultModel(id, modelName);
        return R.ok();
    }

    /**
     * 查询指定 modelType 的技能选项。
     *
     * <p>供 Agent 注册/编辑弹窗技能区三段式渲染：{@code capabilitySkills}（模型能力锁定，
     * 不可取消）+ {@code availableOptionalSkills}（可扩展白名单）。modelType 形如
     * {@code deepseek:deepseek-v4-flash}（URL 中冒号需 {@code encodeURIComponent}）；
     * 模型未识别（表中不存在/已删除）时返回降级默认值并标注 {@code degraded=true}，
     * 前端提示「模型未上架，建议使用已上架模型」。</p>
     */
    @GetMapping("/{modelType}/skill-options")
    public R<Map<String, Object>> skillOptions(@PathVariable("modelType") String modelType) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("modelType", modelType);
        Optional<LlmProviderModel> capability = llmProviderModelQueryService.findCapabilityByModelType(modelType);
        if (capability.isEmpty()) {
            // 降级：返回 Phase 2 默认可选项，前端提示使用已上架模型
            body.put("capabilitySkills", List.of());
            body.put("availableOptionalSkills", List.of("shell", "code-review"));
            body.put("degraded", true);
            return R.ok(body);
        }
        body.put("capabilitySkills", capability.get().getCapabilitySkills() != null
                ? capability.get().getCapabilitySkills() : List.of());
        body.put("availableOptionalSkills", capability.get().getAvailableOptionalSkills() != null
                ? capability.get().getAvailableOptionalSkills() : List.of());
        body.put("degraded", false);
        return R.ok(body);
    }

    private LlmProviderModelResponse toModelResponse(LlmProviderModel m) {
        LlmProviderModelResponse r = new LlmProviderModelResponse();
        r.setId(m.getId());
        r.setModelName(m.getModelName());
        r.setIsDefault(m.getIsDefault());
        r.setEnabled(m.getEnabled());
        r.setSortOrder(m.getSortOrder());
        return r;
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
