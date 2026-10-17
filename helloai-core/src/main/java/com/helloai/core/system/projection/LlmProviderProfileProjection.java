package com.helloai.core.system.projection;

import com.helloai.core.system.entity.LlmProvider;
import com.helloai.core.system.port.LlmProviderProfile;

import java.util.List;

/**
 * {@code LlmProvider} 实体 → 对外只读快照的映射（提供方 system 域内部，消费方零映射）。
 *
 * <p>镜像既有 {@code AgentProfileSnapshotProjection} / {@code SubTaskSnapshotProjection} 形态。</p>
 *
 * <p><b>命名（2026-10-17 命名整改）</b>：原名 {@code LlmProviderProfileMapper}。§17 将 {@code *Mapper}
 * 保留给 MyBatis 持久化映射，本类与 DB 无关（纯内存投影），故去 {@code Mapper} 后缀并迁出
 * {@code service.impl}（该包只应容纳 {@code *ServiceImpl}），置入 {@code system.projection}。后缀取
 * {@code Projection} 而非裸 {@code LlmProviderProfile}，以避免与 {@code system.port.LlmProviderProfile}
 * 简单名混淆。</p>
 */
public final class LlmProviderProfileProjection {

    private LlmProviderProfileProjection() {
    }

    /** null → null（与既有 getById/findByCode 的「不存在返回 null/empty」语义对齐）。 */
    public static LlmProviderProfile toProfile(LlmProvider p) {
        if (p == null) {
            return null;
        }
        return new LlmProviderProfile(p.getProviderCode(), p.getProviderName(), p.getBaseUrl(),
                p.getDefaultModel(), p.getProtocolType(), p.getEnabled(), p.getExtraConfig());
    }

    public static List<LlmProviderProfile> toProfiles(List<LlmProvider> providers) {
        return providers == null ? List.of() : providers.stream().map(LlmProviderProfileProjection::toProfile).toList();
    }
}
