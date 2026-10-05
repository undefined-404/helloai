package com.helloai.core.system.service;

import com.helloai.core.system.entity.LlmProvider;
import com.helloai.core.system.port.LlmProviderProfile;

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

    // ── 只读快照变体（RM5 批 3）：供 agent 域消费，避免其 import system.entity ──

    /**
     * 按 provider_code 查询对外只读快照（语义与 {@link #findByCode} 逐字一致）。
     *
     * @return Provider 快照；不存在或已删除返回 {@link Optional#empty()}
     */
    Optional<LlmProviderProfile> findProfileByCode(String code);

    /**
     * 列出所有启用 Provider 的只读快照（顺序与 {@link #listEnabled} 一致）。
     */
    List<LlmProviderProfile> listEnabledProfiles();

    /**
     * 列出所有 Provider 的只读快照（顺序与 {@link #listAll} 一致）。
     */
    List<LlmProviderProfile> listAllProfiles();
}
