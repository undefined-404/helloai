package com.helloai.core.system.service.impl;

import com.helloai.core.system.entity.LlmProvider;
import com.helloai.core.system.port.LlmProviderProfile;

import java.util.List;

/**
 * {@code LlmProvider} 实体 → 对外只读快照的映射（提供方 system 域内部，消费方零映射）。
 *
 * <p>镜像既有 {@code AgentProfileSnapshotMapper} / {@code SubTaskSnapshotMapper} 形态。</p>
 */
public final class LlmProviderProfileMapper {

    private LlmProviderProfileMapper() {
    }

    /** null → null（与既有 getById/findByCode 的「不存在返回 null/empty」语义对齐）。 */
    public static LlmProviderProfile toProfile(LlmProvider p) {
        if (p == null) {
            return null;
        }
        return new LlmProviderProfile(p.getProviderCode(), p.getProviderName(), p.getBaseUrl(),
                p.getDefaultModel(), p.getProtocolType(), p.getEnabled());
    }

    public static List<LlmProviderProfile> toProfiles(List<LlmProvider> providers) {
        return providers == null ? List.of() : providers.stream().map(LlmProviderProfileMapper::toProfile).toList();
    }
}
