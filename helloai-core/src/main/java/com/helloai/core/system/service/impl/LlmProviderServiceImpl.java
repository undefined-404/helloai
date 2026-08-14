package com.helloai.core.system.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.helloai.common.base.BizException;
import com.helloai.core.system.entity.LlmProvider;
import com.helloai.core.system.mapper.LlmProviderMapper;
import com.helloai.core.system.service.LlmProviderModelService;
import com.helloai.core.system.service.LlmProviderQueryService;
import com.helloai.core.system.service.LlmProviderService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Locale;
import java.util.Objects;

/**
 * LlmProvider 业务服务实现（CRUD + 启用/禁用）。
 *
 * <p>V49 扩展：保存 Provider 时校验必须配置可用模型（关联 llm_provider_model 表）。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LlmProviderServiceImpl extends ServiceImpl<LlmProviderMapper, LlmProvider> implements LlmProviderService {

    private final LlmProviderQueryService llmProviderQueryService;
    private final LlmProviderModelService llmProviderModelService;

    /**
     * 新增 Provider。
     *
     * <p>校验项：code 格式、protocol 枚举、code 唯一。code 自动转小写。</p>
     *
     * @return 保存后的实体（已含 id）
     */
    @Transactional(rollbackFor = Exception.class)
    @Override
    public LlmProvider create(LlmProvider provider) {
        // 大小写归一化放最前面，让后续 validateCode 的正则作用于归一化后的 code；
        // 这样 Controller 与 Service 都各自兜底归一化，行为一致。
        if (provider.getProviderCode() != null) {
            provider.setProviderCode(provider.getProviderCode().toLowerCase(Locale.ROOT));
        }
        validateCode(provider.getProviderCode());
        validateProtocol(provider.getProtocolType());
        if (llmProviderQueryService.findByCode(provider.getProviderCode()).isPresent()) {
            throw new BizException("Provider 已存在: " + provider.getProviderCode());
        }
        if (provider.getEnabled() == null) {
            provider.setEnabled(1);
        }
        if (provider.getBuiltin() == null) {
            provider.setBuiltin(0);
        }
        if (provider.getSortOrder() == null) {
            provider.setSortOrder(100);
        }
        save(provider);
        log.info("LLM Provider 已创建: code={}, name={}, protocol={}",
                provider.getProviderCode(), provider.getProviderName(), provider.getProtocolType());
        return provider;
    }

    /**
     * 局部更新 Provider（patch 中非 null 字段覆盖）。
     *
     * <p>内置 Provider 不可修改 provider_code。protocol / 关键字段校验在写入前完成。</p>
     */
    @Transactional(rollbackFor = Exception.class)
    @Override
    public void update(Long id, LlmProvider patch) {
        LlmProvider existing = getById(id);
        if (existing == null) {
            throw new BizException("Provider 不存在: " + id);
        }
        if (Integer.valueOf(1).equals(existing.getBuiltin()) && patch.getProviderCode() != null
                && !Objects.equals(patch.getProviderCode().toLowerCase(Locale.ROOT),
                        existing.getProviderCode())) {
            throw new BizException("内置 Provider 不可修改 provider_code");
        }
        if (patch.getProtocolType() != null) {
            validateProtocol(patch.getProtocolType());
        }
        if (patch.getProviderName() != null) existing.setProviderName(patch.getProviderName());
        if (patch.getProtocolType() != null) existing.setProtocolType(patch.getProtocolType());
        if (patch.getBaseUrl() != null) existing.setBaseUrl(patch.getBaseUrl());
        if (patch.getDefaultModel() != null) existing.setDefaultModel(patch.getDefaultModel());
        if (patch.getEnabled() != null) existing.setEnabled(patch.getEnabled());
        if (patch.getSortOrder() != null) existing.setSortOrder(patch.getSortOrder());
        if (patch.getExtraConfig() != null) existing.setExtraConfig(patch.getExtraConfig());
        updateById(existing);
    }

    /**
     * 删除 Provider。
     *
     * <p>内置 Provider 拒绝删除；删除时级联物理清理该 Provider 的模型配置
     * （llm_provider_model，V51 起软删 Provider 不触发 FK 级联，需应用层补齐）；
     * 调用方需自行清理由 PlatformProviderConfigService#saveApiKey 写入的 PLATFORM 级
     * credential_vault 凭证（保留 vault 凭证不会自动清理）。</p>
     */
    @Transactional(rollbackFor = Exception.class)
    @Override
    public void deleteById(Long id) {
        LlmProvider existing = getById(id);
        if (existing == null) {
            throw new BizException("Provider 不存在: " + id);
        }
        if (Integer.valueOf(1).equals(existing.getBuiltin())) {
            throw new BizException("内置 Provider 不可删除");
        }
        // 级联物理清理模型配置，避免残留记录让 isModelAvailable 误判已删 Provider 的模型可用
        llmProviderModelService.deleteAllPhysicalByProviderId(id);
        removeById(id);
        log.info("LLM Provider 已删除: id={}, code={}", id, existing.getProviderCode());
    }

    private void validateCode(String code) {
        if (code == null || code.isBlank()) {
            throw new BizException("provider_code 不能为空");
        }
        if (!code.matches("[a-z0-9][a-z0-9-]{1,63}")) {
            throw new BizException("provider_code 必须全小写、字母数字中划线，长度 2-64");
        }
    }

    private void validateProtocol(String protocol) {
        if (!"OPENAI_COMPATIBLE".equals(protocol) && !"ANTHROPIC_COMPATIBLE".equals(protocol)) {
            throw new BizException("protocol_type 仅支持 OPENAI_COMPATIBLE / ANTHROPIC_COMPATIBLE");
        }
    }

    /**
     * 校验 Provider 是否有至少一个启用模型（V49 新增）。
     *
     * <p>委托给 LlmProviderModelService 执行具体校验逻辑。</p>
     */
    @Override
    public void validateProviderHasEnabledModels(Long providerId) {
        llmProviderModelService.validateProviderHasEnabledModels(providerId);
    }
}

