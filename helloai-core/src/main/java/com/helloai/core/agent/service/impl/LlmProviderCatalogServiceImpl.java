package com.helloai.core.agent.service.impl;

import com.helloai.core.agent.chat.AgentProviderResolver;
import com.helloai.core.agent.service.LlmProviderCatalogService;
import com.helloai.core.agent.service.LlmProviderCatalogService.ModelOption;
import com.helloai.core.agent.service.LlmProviderCatalogService.ProviderCatalogItem;
import com.helloai.core.agent.service.PlatformProviderConfigService;
import com.helloai.common.base.BizException;
import com.helloai.common.config.AgentExecutionProperties;
import com.helloai.core.agent.chat.provider.LlmProviderChatClientFactoryRegistry;
import com.helloai.core.agent.entity.Agent;
import com.helloai.core.system.port.LlmProviderModelRef;
import com.helloai.core.system.port.LlmProviderProfile;
import com.helloai.core.system.service.LlmProviderModelQueryService;
import com.helloai.core.system.service.LlmProviderQueryService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * LLM Provider 目录服务（方案B）。
 *
 * <p>基于 {@code llm_provider} 表枚举"已生效"的 provider：已配置平台级 api-key 且存在对应
 * ChatClient 工厂实现（DeepSeek 专用 / OpenAI 兼容 / Anthropic 兼容）。供前端手动注册
 * 平台内 API_KEY_LLM Agent 时选择；执行链密钥由 AgentLlmCredentialResolver
 * 按 modelType 关联平台级凭证实时解析（内部 LLM Agent 不持有 AGENT 级快照，
 * 系统管理轮换 API Key 后已注册 Agent 实时生效）。</p>
 *
 * <p>新增 provider 仅需在 {@code llm_provider} 表插入一条记录，无需新增 Java 类
 * （仅 OpenAI / Anthropic 兼容协议范围内）。后续扩展 Gemini 原生协议时再补 Registry。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LlmProviderCatalogServiceImpl implements LlmProviderCatalogService {

    private final LlmProviderQueryService llmProviderQueryService;
    private final AgentExecutionProperties executionProperties;
    private final LlmProviderChatClientFactoryRegistry factoryRegistry;
    private final PlatformProviderConfigService platformProviderConfigService;
    /** 模型注册表（system 域只读查询；`agent → system` 顺向依赖）。 */
    private final LlmProviderModelQueryService llmProviderModelQueryService;

    /**
     * 枚举全部已配置的 provider 及其可用性。
     *
     * <p>数据源：{@code llm_provider} 表 + PlatformProviderConfigService 的 API Key 可用性。
     * 工厂支持性：通过 {@link LlmProviderChatClientFactoryRegistry#createChatClient} 试探路由
     * （不实际创建 ChatClient，仅判断 protocolType 是否被任何 Factory 支持）。</p>
     */
    public List<ProviderCatalogItem> listProviders() {
        List<LlmProviderProfile> providers = llmProviderQueryService.listAllProfiles();
        List<ProviderCatalogItem> items = new ArrayList<>(providers.size());
        for (LlmProviderProfile p : providers) {
            String name = p.providerCode().toLowerCase(Locale.ROOT);
            boolean enabled = Integer.valueOf(1).equals(p.enabled());
            boolean factorySupported = isFactorySupported(p);
            boolean apiKeyConfigured = platformProviderConfigService.isApiKeyConfigured(name);
            items.add(new ProviderCatalogItem(
                    name,
                    p.providerName(),
                    p.protocolType(),
                    p.defaultModel(),
                    apiKeyConfigured,
                    factorySupported,
                    enabled && apiKeyConfigured && factorySupported));
        }
        return items;
    }

    /**
     * 枚举当前<b>可选</b>的 LLM 模型（个人工作台 Composer 数据源）。
     *
     * <p>三段拼接，<b>不做循环查库</b>：供应商可用性一次 {@link #listProviders()}（内部已按表
     * 批量取）；模型注册项一次 {@code listAllRefs()}；随后在内存里按 providerCode 归并
     * （CODE_STYLE §18/§19：禁止 for 循环里逐条 select）。</p>
     *
     * <p><b>不可选的直接不进候选集</b>（用户 2026-11-05 裁定：无 API Key 的不显示、
     * 供应商已下架的也不显示）。被滤掉的条目按原因累计后记 debug 日志——下拉是无「为什么要
     * 灰这一条」的解释位的，但排查时不能查不到（「不静默失败」）。</p>
     *
     * <p><b>排除原因的判定顺序有语义</b>：先判模型自身停用（用户能自己改），再判供应商级
     * （需要管理员改），最后兜底「供应商已下架」——顺序颠倒会把「模型停用」报成
     * 「供应商停用」，把人引到错误的配置页。</p>
     */
    @Override
    public List<ModelOption> listModelOptions() {
        List<LlmProviderModelRef> refs = llmProviderModelQueryService.listAllRefs();
        if (refs.isEmpty()) {
            return List.of();
        }
        List<ProviderCatalogItem> providers = listProviders();
        if (providers.isEmpty()) {
            log.debug("模型候选为空: 供应商表无存活记录");
            return List.of();
        }
        Map<String, ProviderCatalogItem> providerByCode = new HashMap<>(providers.size() * 2);
        for (ProviderCatalogItem item : providers) {
            providerByCode.put(item.provider().toLowerCase(Locale.ROOT), item);
        }
        List<ModelOption> options = new ArrayList<>(refs.size());
        Map<String, Integer> excluded = new LinkedHashMap<>();
        for (LlmProviderModelRef ref : refs) {
            ProviderCatalogItem provider = providerByCode.get(ref.providerCode());
            if (provider == null) {
                // 供应商记录已不存在（软删）⇒ 该模型是孤儿，不再作为候选
                excluded.merge("供应商记录已不存在", 1, Integer::sum);
                continue;
            }
            String reason = exclusionReason(ref, provider);
            if (reason != null) {
                excluded.merge(reason, 1, Integer::sum);
                continue;
            }
            options.add(new ModelOption(provider.provider(), provider.providerName(),
                    ref.modelName(), ref.modelType(), ref.defaultModel()));
        }
        if (!excluded.isEmpty()) {
            log.debug("模型候选已过滤: 注册 {} 条, 可选 {} 条, 排除明细 {}", refs.size(), options.size(), excluded);
        }
        options.sort(Comparator
                .comparing(ModelOption::defaultModel, Comparator.reverseOrder())
                .thenComparing(ModelOption::providerName, Comparator.nullsLast(Comparator.naturalOrder()))
                .thenComparing(ModelOption::modelName, Comparator.nullsLast(Comparator.naturalOrder())));
        return options;
    }

    /** 进不了候选集的原因；可选时返回 {@code null}。四条件全成立才算「有效」。 */
    private static String exclusionReason(LlmProviderModelRef ref, ProviderCatalogItem provider) {
        if (!ref.enabled()) {
            return "模型已停用";
        }
        if (!provider.apiKeyConfigured()) {
            return "供应商未配置 API Key";
        }
        if (!provider.factorySupported()) {
            return "供应商协议暂不支持";
        }
        if (!provider.available()) {
            return "供应商已下架";
        }
        return null;
    }

    /**
     * 判断 provider 是否"已生效"（可用于手动注册平台内 LLM Agent）。
     */
    public boolean isProviderAvailable(String provider) {
        if (provider == null || provider.isBlank()) {
            return false;
        }
        return listProviders().stream()
                .anyMatch(item -> item.provider().equalsIgnoreCase(provider) && item.available());
    }

    /**
     * API_KEY_LLM Agent 注册后的平台凭证核对（尽力而为，不阻断注册）。
     *
     * <p>按 Agent.modelType 前缀（缺省回退 {@code helloai.execution.provider}）解析
     * provider；provider 未生效时仅记录日志跳过——内部 LLM Agent 执行链不再依赖
     * AGENT 级密钥快照（旧版注册时会把平台密钥复制进 credential_vault，导致
     * 系统管理轮换 API Key 后已注册 Agent 仍用旧 Key），执行密钥由
     * AgentLlmCredentialResolver 按 modelType 关联平台级凭证实时解析。</p>
     *
     * @return true 表示本次执行了凭证核对（语义保留，兼容旧调用方）
     */
    public boolean provisionPlatformCredential(Agent agent) {
        String provider = AgentProviderResolver.resolveProvider(agent, executionProperties.getProvider());
        if (!isProviderAvailable(provider)) {
            log.warn("Provider '{}' 未生效，跳过平台凭证核对: agentId={}", provider, agent.getId());
            return false;
        }
        log.info("内部 LLM Agent 平台凭证核对通过（执行链实时解析平台级 API Key）: agentId={}, provider={}",
                agent.getId(), provider);
        return true;
    }

    /**
     * 判断 llm_provider 记录是否被某个 ChatClient 工厂支持。
     *
     * <p>仅凭 protocolType 判定：deepseek 走专用 Factory；其他按协议类型分发。
     * 真正的 ChatClient 创建校验留给 {@link LlmProviderChatClientFactoryRegistry}。</p>
     */
    private boolean isFactorySupported(LlmProviderProfile provider) {
        if (provider == null || provider.protocolType() == null) {
            return false;
        }
        String type = provider.protocolType().toUpperCase(Locale.ROOT);
        if ("deepseek".equalsIgnoreCase(provider.providerCode())) {
            return true;
        }
        return "OPENAI_COMPATIBLE".equals(type) || "ANTHROPIC_COMPATIBLE".equals(type);
    }
}
