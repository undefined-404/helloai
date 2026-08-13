package com.helloai.core.system.service;

import com.helloai.core.system.entity.LlmProvider;

import java.util.List;
import java.util.Optional;

/**
 * LlmProvider 运行时查询服务（只读）。
 *
 * <p>被 PlatformProviderConfigService / LlmProviderCatalogService / Registry 等
 * 热路径调用，要求无副作用、可高频调用。仅按 code 查单条 / 列启用 / 列全部，
 * 复杂连表查询不在本类范围。</p>
 */
public interface LlmProviderQueryService {

    /**
     * 按 provider_code 查询（大小写不敏感，自动 trim）。
     *
     * @return Provider 实体；不存在或已删除返回 {@link Optional#empty()}
     */
    Optional<LlmProvider> findByCode(String code);

    /**
     * 列出所有启用状态的 Provider（按 sort_order 升序）。
     */
    List<LlmProvider> listEnabled();

    /**
     * 列出所有 Provider（含禁用），按 sort_order 升序。
     */
    List<LlmProvider> listAll();
}
