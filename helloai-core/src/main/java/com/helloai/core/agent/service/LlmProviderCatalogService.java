package com.helloai.core.agent.service;

import com.helloai.core.agent.entity.Agent;

import java.util.List;

/**
 * LLM Provider 目录服务：枚举平台已配置的 provider 及可用性。
 */
public interface LlmProviderCatalogService {

    /** Provider 目录项（对外展示用）。 */
    record ProviderCatalogItem(String provider, String providerName, String protocolType,
                               String defaultModel,
                               boolean apiKeyConfigured, boolean factorySupported,
                               boolean available) {
    }

    /**
     * 模型候选项（个人工作台 Composer 的「选模型」数据源，2026-11-05）。
     *
     * <p><b>与 {@link ProviderCatalogItem} 的分工</b>：前者是「供应商」维度的可用性，
     * 本项是「<b>已注册的纯 LLM 模型</b>」维度——一个供应商下有多个模型，
     * 各自可独立启用/停用。工作台只让用户选后者（无角色），需求对话页选的仍是
     * Planner <b>角色</b>（角色自带模型），两条候选集刻意不共用。</p>
     *
     * <p><b>本记录只承载「可选」的模型</b>：不可选的（模型停用 / 供应商未配 Key /
     * 协议不支持 / 供应商已下架）在 {@link #listModelOptions()} 内就被滤掉，不进入候选集。
     * 因此这里<b>没有</b> {@code usable} / {@code unusableReason}——恒真的布尔位是死字段，
     * 留着只会诱使后来者以为「前端还需判一次」。</p>
     *
     * @param providerCode   供应商编码（小写）
     * @param providerName   供应商显示名
     * @param modelName      模型名
     * @param modelType      钉住用的 {@code provider:model}
     * @param defaultModel   是否为该供应商的默认模型（前端排序 / 标注用）
     */
    record ModelOption(String providerCode,
                       String providerName,
                       String modelName,
                       String modelType,
                       boolean defaultModel) {
    }

    /**
     * 枚举当前<b>可选</b>的 LLM 模型（工作台下拉的数据源）。
     *
     * <p><b>过滤发生在提供方</b>（判据「判断依据属于谁，判定就由谁做」）：有效性 =
     * 模型启用 + 供应商启用 + 平台 Key 已配 + 协议可路由，四者同时成立才进候选集。
     * 用户 2026-11-05 裁定「不显示」而非「灰显」——不可用的模型在列表里占位却不给出
     * 「我该去哪里修」的路径，不如让下拉底部的维护指引统一回答（CodeStyle：不静默失败
     * ⇒ 被滤掉的条目按原因记日志，排查时查得到）。</p>
     *
     * <p>排序：供应商默认模型 → 供应商名 → 模型名。</p>
     *
     * @return 可选模型列表；无可用模型时返回空列表，绝不返回 {@code null}
     */
    List<ModelOption> listModelOptions();

    /**
     * 枚举全部已配置的 provider 及其可用性。
     */
    List<ProviderCatalogItem> listProviders();

    /**
     * 判断指定 provider 当前是否可用（apiKey 已配置且工厂支持且启用）。
     */
    boolean isProviderAvailable(String provider);

    /**
     * 为 API_KEY_LLM Agent 核对注册前的平台凭证就绪性（内部 LLM Agent 注册后置检查）。
     *
     * <p>内部 LLM Agent 不再持有 AGENT 级 API Key 副本，执行链按 modelType 关联
     * 平台级凭证实时取 Key（见 AgentLlmCredentialResolver）；本方法仅确认
     * provider 可用，返回 false 表示跳过（不阻断注册）。</p>
     */
    boolean provisionPlatformCredential(Agent agent);
}
