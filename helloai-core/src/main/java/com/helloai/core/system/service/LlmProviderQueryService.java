package com.helloai.core.system.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.helloai.core.system.entity.LlmProvider;
import com.helloai.core.system.mapper.LlmProviderMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.List;
import java.util.Optional;

/**
 * LlmProvider 运行时查询服务（只读）。
 *
 * <p>被 PlatformProviderConfigService / LlmProviderCatalogService / Registry 等
 * 热路径调用，要求无副作用、可高频调用。仅按 code 查单条 / 列启用 / 列全部，
 * 复杂连表查询不在本类范围。</p>
 */
@Service
@RequiredArgsConstructor
public class LlmProviderQueryService {

    private final LlmProviderMapper llmProviderMapper;

    /**
     * 按 provider_code 查询（大小写不敏感，自动 trim）。
     *
     * @return Provider 实体；不存在或已删除返回 {@link Optional#empty()}
     */
    public Optional<LlmProvider> findByCode(String code) {
        if (code == null || code.isBlank()) {
            return Optional.empty();
        }
        LlmProvider p = llmProviderMapper.selectOne(new LambdaQueryWrapper<LlmProvider>()
                .eq(LlmProvider::getProviderCode, code.toLowerCase().trim())
                .eq(LlmProvider::getDeleted, 0));
        return Optional.ofNullable(p);
    }

    /**
     * 列出所有启用状态的 Provider（按 sort_order 升序）。
     */
    public List<LlmProvider> listEnabled() {
        List<LlmProvider> list = llmProviderMapper.selectList(new LambdaQueryWrapper<LlmProvider>()
                .eq(LlmProvider::getEnabled, 1)
                .eq(LlmProvider::getDeleted, 0)
                .orderByAsc(LlmProvider::getSortOrder));
        return list != null ? list : Collections.emptyList();
    }

    /**
     * 列出所有 Provider（含禁用），按 sort_order 升序。
     */
    public List<LlmProvider> listAll() {
        List<LlmProvider> list = llmProviderMapper.selectList(new LambdaQueryWrapper<LlmProvider>()
                .eq(LlmProvider::getDeleted, 0)
                .orderByAsc(LlmProvider::getSortOrder));
        return list != null ? list : Collections.emptyList();
    }
}
