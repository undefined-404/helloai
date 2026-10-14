package com.helloai.api.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import com.helloai.api.dto.admin.CreateLlmProviderRequest;
import com.helloai.api.dto.admin.LlmProviderApiKeyBatchResponse;
import com.helloai.api.dto.admin.LlmProviderApiKeyChangeResponse;
import com.helloai.api.dto.admin.LlmProviderApiKeyItemResponse;
import com.helloai.api.dto.admin.LlmProviderApiKeyResponse;
import com.helloai.api.dto.admin.LlmProviderModelResponse;
import com.helloai.api.dto.admin.LlmProviderResponse;
import com.helloai.api.dto.admin.UpdateLlmProviderRequest;
import com.helloai.common.base.R;
import com.helloai.core.agent.SkillCapabilityRequirements;
import com.helloai.core.agent.dto.ApiKeyBatchSaveOutcome;
import com.helloai.core.agent.dto.ApiKeySaveOutcome;
import com.helloai.core.agent.service.LlmProviderKeyVerifyService;
import com.helloai.core.agent.service.PlatformProviderConfigService;
import com.helloai.core.system.entity.LlmProvider;
import com.helloai.core.system.entity.LlmProviderModel;
import com.helloai.core.system.port.ModelCapabilities;
import com.helloai.core.system.port.PlatformApiKeyChange;
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
import java.util.Objects;
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
 *   <li>PUT    /api/admin/llm-providers/saveApiKeyById/{id}  写入 API Key（走 credential_vault）</li>
 *   <li>PUT    /api/admin/llm-providers/saveApiKeysById/{id} 批量写入 API Key（`M-7`，一次粘贴多把）</li>
 *   <li>GET    /api/admin/llm-providers/listApiKeysByProviderId/{id}  Key 列表（`M-8`，主 / 备 / 历史）</li>
 *   <li>PUT    /api/admin/llm-providers/activateApiKeyByProviderIdAndCredentialId/{id}/{credentialId}   手动切换启用位（`M-8`）</li>
 *   <li>PUT    /api/admin/llm-providers/deactivateApiKeyByProviderIdAndCredentialId/{id}/{credentialId} 停用（`M-8`）</li>
 *   <li>DELETE /api/admin/llm-providers/deleteApiKeyByProviderIdAndCredentialId/{id}/{credentialId}     删除（`M-8`，软删）</li>
 *   <li>POST   /api/admin/llm-providers/verifyApiKeyById/{id} 验证 API Key（最小请求探测）</li>
 *   <li>GET    /api/admin/llm-providers/listModelsByProviderId/{id}           模型列表（含禁用）</li>
 *   <li>POST   /api/admin/llm-providers/addModelByProviderId/{id}               添加模型</li>
 *   <li>PUT    /api/admin/llm-providers/saveAllModelsByProviderId/{id}       批量保存模型配置</li>
 *   <li>DELETE /api/admin/llm-providers/deleteModelByProviderIdAndName/{providerId}/{modelName} 删除模型</li>
 *   <li>PUT    /api/admin/llm-providers/toggleModelByProviderIdAndName/{providerId}/{modelName} 启用/禁用模型</li>
 *   <li>PUT    /api/admin/llm-providers/setDefaultModelByProviderIdAndName/{providerId}/{modelName} 设为默认模型</li>
 * </ul>
 *
 * <p>与 {@link AdminProviderConfigController}（旧 /api/admin/platform/providers）共存，
 * 前端 Settings.vue 优先使用本套端点；旧端点保留用于兼容既有脚本。</p>
 */
@Slf4j
@RestController
@RequestMapping("/api/admin/llm-providers")
@RequiredArgsConstructor
public class AdminLlmProviderController {

    private final LlmProviderService providerService;
    private final LlmProviderQueryService queryService;
    private final PlatformProviderConfigService platformProviderConfigService;
    private final LlmProviderKeyVerifyService llmProviderKeyVerifyService;
    private final LlmProviderModelService llmProviderModelService;
    private final LlmProviderModelQueryService llmProviderModelQueryService;

    @SaCheckPermission("llm-provider:view")
    @GetMapping("/list")
    public R<List<LlmProviderResponse>> list() {
        List<LlmProviderResponse> items = queryService.listAll().stream()
                .map(this::toResponse)
                .collect(Collectors.toList());
        return R.ok(items);
    }

    @SaCheckPermission("llm-provider:view")
    @GetMapping("/getById/{id}")
    public R<LlmProviderResponse> getById(@PathVariable("id") Long id) {
        LlmProvider p = providerService.getById(id);
        if (p == null) {
            return R.fail("Provider 不存在");
        }
        return R.ok(toResponse(p));
    }

    @SaCheckPermission("llm-provider:add")
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
        // 计费类型：前端"添加模型"弹窗类型字段；不传由 Service 兜底 API_KEY
        entity.setBillingType(req.getBillingType());
        entity.setExtraConfig(req.getExtraConfig());
        LlmProvider saved = providerService.create(entity);
        log.info("管理员创建 LLM Provider: code={}, name={}",
                saved.getProviderCode(), saved.getProviderName());
        return R.ok(toResponse(saved));
    }

    @SaCheckPermission("llm-provider:edit")
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
        if (req.getBillingType() != null) patch.setBillingType(req.getBillingType());
        providerService.update(id, patch);
        return R.ok();
    }

    @SaCheckPermission("llm-provider:delete")
    @DeleteMapping("/deleteById/{id}")
    public R<Void> deleteById(@PathVariable("id") Long id) {
        providerService.deleteById(id);
        return R.ok();
    }

    @SaCheckPermission("llm-provider:edit")
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
     *
     * <p>`M-6`：同一 provider 下同一 Key 二次录入 ⇒ 返回 {@code created=false} +
     * 「已存在」文案，**不重复落库**；响应含 Key 指纹（不暴露明文）。</p>
     */
    @SaCheckPermission("llm-provider:key")
    @PutMapping("/saveApiKeyById/{id}")
    public R<LlmProviderApiKeyResponse> saveApiKey(@PathVariable("id") Long id, @RequestBody String apiKey) {
        if (apiKey == null || apiKey.isBlank()) {
            return R.fail("apiKey 不能为空");
        }
        LlmProvider p = providerService.getById(id);
        if (p == null) {
            return R.fail("Provider 不存在");
        }
        ApiKeySaveOutcome outcome = platformProviderConfigService.saveApiKey(p.getProviderCode(), apiKey);
        log.info("管理员配置平台级 API Key: id={}, provider={}, created={}, fingerprint={}",
                id, p.getProviderCode(), outcome.created(), outcome.fingerprint());
        return R.ok(LlmProviderApiKeyResponse.from(outcome));
    }

    /**
     * `M-7` **批量**写入平台级 API Key：一次粘贴多把（换行 / 逗号 / 分号 / 空格分隔）。
     *
     * <p>请求体同样是纯字符串（可能含多把），空值返回 400。服务端拆分 + 剥引号 + 输入内去重，
     * 再逐把与库内指纹比对：命中即跳过、不重复落库。</p>
     *
     * <p>`M-8` 之前 `credential_vault` 仍是「同 (owner, provider, type) 只可一条 ACTIVE」，
     * 故本批**只有在该 provider 尚无启用凭证时**才把首把升为 ACTIVE，其余与已在主 Key 之后新增的
     * 一把一律落 {@code DISABLED} 备位（不覆盖正在使用的主 Key）—— 备位转正归 `M-8`。</p>
     */
    @SaCheckPermission("llm-provider:key")
    @PutMapping("/saveApiKeysById/{id}")
    public R<LlmProviderApiKeyBatchResponse> saveApiKeys(@PathVariable("id") Long id,
                                                         @RequestBody String rawText) {
        if (rawText == null || rawText.isBlank()) {
            return R.fail("apiKey 不能为空");
        }
        LlmProvider p = providerService.getById(id);
        if (p == null) {
            return R.fail("Provider 不存在");
        }
        ApiKeyBatchSaveOutcome outcome =
                platformProviderConfigService.saveApiKeys(p.getProviderCode(), rawText);
        log.info("管理员批量配置平台级 API Key: id={}, provider={}, added={}, alreadyExists={}, primaryActivated={}",
                id, p.getProviderCode(), outcome.added(), outcome.alreadyExists(),
                outcome.primaryActivated());
        return R.ok(LlmProviderApiKeyBatchResponse.from(outcome));
    }

    // ══════════════════════════════════════════════════════════════
    //  `M-8` 多 Key 主备管理
    //
    //  红线 `N-1`：只做**手动**主/备切换。不做按健康度 / 配额 / 限流的自动换 Key
    //  （不移植 magpie internal/gateway/fallback.go），也不做加权 routing。
    // ══════════════════════════════════════════════════════════════

    /**
     * `M-8` 列出该 Provider 的平台级 API Key（主 / 备 / 历史，不含密文）。
     *
     * <p>只读端点，故复用 {@code llm-provider:view} 权限，与 {@code /list} 同级。</p>
     */
    @SaCheckPermission("llm-provider:view")
    @GetMapping("/listApiKeysByProviderId/{id}")
    public R<List<LlmProviderApiKeyItemResponse>> listApiKeys(@PathVariable("id") Long id) {
        LlmProvider p = providerService.getById(id);
        if (p == null) {
            return R.fail("Provider 不存在");
        }
        List<LlmProviderApiKeyItemResponse> items = platformProviderConfigService
                .listApiKeys(p.getProviderCode())
                .stream()
                .map(LlmProviderApiKeyItemResponse::from)
                .collect(Collectors.toList());
        return R.ok(items);
    }

    /**
     * `M-8` 手动把指定备位升为启用位（原启用位降为备位）。
     *
     * <p>路径同时带 {@code providerId} 与 {@code credentialId}：后者必须属于前者，
     * 否则服务层 fail-close 拒绝（防跨 Provider 误操作）。</p>
     */
    @SaCheckPermission("llm-provider:key")
    @PutMapping("/activateApiKeyByProviderIdAndCredentialId/{id}/{credentialId}")
    public R<LlmProviderApiKeyChangeResponse> activateApiKey(
            @PathVariable("id") Long id,
            @PathVariable("credentialId") Long credentialId) {
        LlmProvider p = providerService.getById(id);
        if (p == null) {
            return R.fail("Provider 不存在");
        }
        PlatformApiKeyChange change =
                platformProviderConfigService.activateApiKey(p.getProviderCode(), credentialId);
        log.info("管理员手动切换平台级 API Key 启用位: id={}, provider={}, previousActiveId={}, newActiveId={}",
                id, p.getProviderCode(), change.previousActiveId(), change.activeId());
        return R.ok(LlmProviderApiKeyChangeResponse.from(change));
    }

    /**
     * `M-8` 停用平台级 Key。
     *
     * <p>停用**启用位**时自动把最旧的一把备位顶为启用位；无备位可顶则拒绝
     * （不得让 provider 静默变成「未配置」）。停用**备位**是幂等空操作。</p>
     */
    @SaCheckPermission("llm-provider:key")
    @PutMapping("/deactivateApiKeyByProviderIdAndCredentialId/{id}/{credentialId}")
    public R<LlmProviderApiKeyChangeResponse> deactivateApiKey(
            @PathVariable("id") Long id,
            @PathVariable("credentialId") Long credentialId) {
        LlmProvider p = providerService.getById(id);
        if (p == null) {
            return R.fail("Provider 不存在");
        }
        PlatformApiKeyChange change =
                platformProviderConfigService.deactivateApiKey(p.getProviderCode(), credentialId);
        log.info("管理员停用平台级 API Key: id={}, provider={}, credentialId={}, newActiveId={}",
                id, p.getProviderCode(), credentialId, change.activeId());
        return R.ok(LlmProviderApiKeyChangeResponse.from(change));
    }

    /**
     * `M-8` 删除（软删）平台级 Key。
     *
     * <p>删除**启用位**时同样先顶替；删除**备位 / 历史行**直接放行。软删保留行以备审计追溯
     * （{@code credential_audit_log} 仍有指向它的记录）。</p>
     */
    @SaCheckPermission("llm-provider:key")
    @DeleteMapping("/deleteApiKeyByProviderIdAndCredentialId/{id}/{credentialId}")
    public R<LlmProviderApiKeyChangeResponse> deleteApiKey(
            @PathVariable("id") Long id,
            @PathVariable("credentialId") Long credentialId) {
        LlmProvider p = providerService.getById(id);
        if (p == null) {
            return R.fail("Provider 不存在");
        }
        PlatformApiKeyChange change =
                platformProviderConfigService.deleteApiKey(p.getProviderCode(), credentialId);
        log.info("管理员删除平台级 API Key: id={}, provider={}, credentialId={}, wasActive={}, newActiveId={}",
                id, p.getProviderCode(), credentialId,
                Objects.equals(change.previousActiveId(), credentialId), change.activeId());
        return R.ok(LlmProviderApiKeyChangeResponse.from(change));
    }

    /**
     * 验证 Provider 平台级 API Key：以最小请求（max_tokens=1）直探 Provider 端点。
     *
     * <p>返回 {@code success / message / model / elapsedMs}；验证失败不抛异常，
     * success=false + 可读 message，前端直接展示。</p>
     */
    @SaCheckPermission("llm-provider:key")
    @PostMapping("/verifyApiKeyById/{id}")
    public R<Map<String, Object>> verifyApiKey(@PathVariable("id") Long id) {
        return R.ok(llmProviderKeyVerifyService.verifyById(id));
    }

    // ══════════════════════════════════════════════════════════════
    //  模型管理端点
    // ══════════════════════════════════════════════════════════════

    /**
     * 查询 Provider 的模型列表（含禁用）。
     */
    @SaCheckPermission("llm-provider:view")
    @GetMapping("/listModelsByProviderId/{id}")
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
    @SaCheckPermission("llm-provider:model")
    @PostMapping("/addModelByProviderId/{id}")
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
    @SaCheckPermission("llm-provider:model")
    @PutMapping("/saveAllModelsByProviderId/{id}")
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
    @SaCheckPermission("llm-provider:model")
    @DeleteMapping("/deleteModelByProviderIdAndName/{providerId}/{modelName}")
    public R<Void> deleteModel(@PathVariable("providerId") Long id,
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
    @SaCheckPermission("llm-provider:model")
    @PutMapping("/toggleModelByProviderIdAndName/{providerId}/{modelName}")
    public R<Void> toggleModel(@PathVariable("providerId") Long id,
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
    @SaCheckPermission("llm-provider:model")
    @PutMapping("/setDefaultModelByProviderIdAndName/{providerId}/{modelName}")
    public R<Void> setDefaultModel(@PathVariable("providerId") Long id,
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
     *
     * <p><b>`M-9`</b>：两列表由能力谓词派生（{@code thinking←reasoning}、{@code web-search←webSearch}；
     * 作业类型技能恒真，见 {@link com.helloai.core.agent.SkillCapabilityRequirements}）；
     * 新增 {@code capabilities}（object；{@code degraded=true} 时为 {@code null}）。
     * 出参字段名与错误码均<b>未变</b>，仅新增 1 个字段。</p>
     */
    @SaCheckPermission("llm-provider:view")
    @GetMapping("/getSkillOptionsByModelType/{modelType}")
    public R<Map<String, Object>> getSkillOptions(@PathVariable("modelType") String modelType) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("modelType", modelType);
        LlmProviderModel capability = llmProviderModelQueryService.findCapabilityByModelType(modelType);
        if (capability == null) {
            // 降级：返回默认可选项，前端提示使用已上架模型（与切换前逐字一致）
            body.put("capabilitySkills", List.of());
            body.put("availableOptionalSkills", List.of("shell", "code-review"));
            body.put("degraded", true);
            body.put("capabilities", null);
            return R.ok(body);
        }
        ModelCapabilities caps = ModelCapabilities.fromRaw(capability.getCapabilities());
        body.put("capabilitySkills", SkillCapabilityRequirements.lockedSkills(caps));
        body.put("availableOptionalSkills", SkillCapabilityRequirements.optionalSkills(caps));
        body.put("degraded", false);
        body.put("capabilities", caps.toRawMap());
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
        // 计费类型：历史数据无此列值时兜底按量付费，与 V59 迁移 DEFAULT 语义一致
        r.setBillingType(p.getBillingType() != null && !p.getBillingType().isBlank()
                ? p.getBillingType() : "API_KEY");
        r.setExtraConfig(p.getExtraConfig());
        r.setApiKeyConfigured(platformProviderConfigService.isApiKeyConfigured(p.getProviderCode()));
        r.setApiKeyMasked(platformProviderConfigService.maskApiKey(p.getProviderCode()));
        r.setApiKeyFromVault(platformProviderConfigService.isApiKeyFromVault(p.getProviderCode()));
        return r;
    }
}
