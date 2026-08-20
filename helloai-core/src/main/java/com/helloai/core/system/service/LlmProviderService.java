package com.helloai.core.system.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.helloai.core.system.entity.LlmProvider;

/**
 * LlmProvider 业务服务（CRUD + 启用/禁用）。
 *
 * <p>对外暴露的 Provider 配置入口；API Key 仍走 PlatformProviderConfigService。
 * 命名规则：provider_code 必须全小写、字母数字中划线，长度 2-64；内置 Provider
 * 不可修改 code、不可删除。</p>
 *
 * <p>协议类型本轮仅支持 {@code OPENAI_COMPATIBLE} / {@code ANTHROPIC_COMPATIBLE}，
 * 后续扩展 Gemini 原生 / 其他厂商 SDK 时在此补充校验。</p>
 */
public interface LlmProviderService extends IService<LlmProvider> {

    /**
     * 新增 Provider。
     *
     * <p>校验项：code 格式、protocol 枚举、code 唯一。code 自动转小写。</p>
     *
     * @return 保存后的实体（已含 id）
     */
    LlmProvider create(LlmProvider provider);

    /**
     * 局部更新 Provider（patch 中非 null 字段覆盖）。
     *
     * <p>内置 Provider 不可修改 provider_code。protocol / 关键字段校验在写入前完成。</p>
     */
    void update(Long id, LlmProvider patch);

    /**
     * 删除 Provider。
     *
     * <p>内置 Provider 拒绝删除；调用方需自行清理由 PlatformProviderConfigService#saveApiKey
     * 写入的 PLATFORM 级 credential_vault 凭证（保留 vault 凭证不会自动清理）。</p>
     */
    void deleteById(Long id);

    /**
     * 校验 Provider 是否有至少一个启用模型。
     *
     * <p>保存 Provider 时调用，确保 Provider 必须配置可用模型。</p>
     *
     * @param providerId Provider ID
     * @throws com.helloai.common.base.BizException 当 Provider 没有启用模型时
     */
    void validateProviderHasEnabledModels(Long providerId);
}
