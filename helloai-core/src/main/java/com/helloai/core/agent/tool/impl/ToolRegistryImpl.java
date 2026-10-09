package com.helloai.core.agent.tool.impl;

import com.helloai.core.agent.tool.ToolCallbackContributor;
import com.helloai.core.agent.tool.ToolContext;
import com.helloai.core.agent.tool.ToolDefinition;
import com.helloai.core.agent.tool.ToolRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * {@link ToolRegistry} 实现——从 spring-ai {@link ToolCallbackProvider}（McpToolConfig
 * 注册的全部 @Tool，单一事实源）收集平台工具元数据目录，并叠加各域贡献者的
 * 「条件可用」/「动态描述」声明（REF-1.3）。
 *
 * <p>平台当前工具均为 MCP 工具（McpMcpServer 13 + EchoMcpTool 1 + 各域
 * {@link ToolCallbackContributor} 贡献），目录自动跟随 MCP 工具注册，零漂移；未来
 * GitTool / ShellTool 等工具类型（长期思路 P1）注册进同一目录即可，不另建平行 Registry（§50.7）。</p>
 *
 * <p><b>两条独立生命周期</b>（REF-1.3 的关键设计）：<b>工具目录</b>与<b>声明面</b>各自懒加载 + 双检锁，
 * 互不影响——目录加载失败<b>不得</b>关闭条件可用语义，声明装配失败<b>不得</b>阻断执行链。
 * 两者各自的降级口径见 {@link #ensureCatalog()} / {@link #ensureDeclarations()}。</p>
 *
 * <p><b>目录降级的 fail-open（REF-1.3 隐藏硬约束）</b>：REF-1.3 之前，{@code resolve} 的结果
 * 只喂 TOOL_RESOLVED 事件，目录为空不过是「事件里没有工具元数据」；REF-1.3 之后它升级为
 * <b>模型可见工具的唯一判据</b>——若沿用旧降级口径（静默空目录 + 「未知即跳过」），
 * 一次反射失败将导致<b>所有 Agent 的所有工具从模型视野消失</b>。
 * 故新增 {@link #catalogDegraded}：「未知」来自平台故障而非入参时不构成摘除理由。</p>
 */
@Slf4j
@Component
public class ToolRegistryImpl implements ToolRegistry {

    private final ToolCallbackProvider toolCallbackProvider;
    /** 声明面来源（各域贡献者；无声明者时所有工具可用，行为与 REF-1.3 前等价）。 */
    private final List<ToolCallbackContributor> toolContributors;

    /** 已注册工具名 → 元数据（LinkedHashMap 保序：MCP 工具注册顺序）；null = 尚未加载。 */
    private volatile Map<String, ToolDefinition> catalog;

    /** 目录是否<b>降级</b>（加载失败）：降级时「未知」不构成摘除理由（fail-open）。 */
    private volatile boolean catalogDegraded;

    /** 声明面快照（工具名 → 可用性判定 / 描述重写函数）；null = 尚未装配。 */
    private volatile Declarations declarations;

    /**
     * 显式构造器（不用 Lombok）：{@link ToolCallbackContributor} 候选数为 <b>0/1/N</b>，
     * 用 {@link ObjectProvider} 表达「无贡献者也要能装配」——与
     * {@code WebSearchServiceRouter} 的多候选探测同范式（零候选 ⇒ 空集合，不抛
     * {@code NoSuchBeanDefinitionException}）。
     */
    public ToolRegistryImpl(ToolCallbackProvider toolCallbackProvider,
                            ObjectProvider<ToolCallbackContributor> toolContributors) {
        this.toolCallbackProvider = toolCallbackProvider;
        this.toolContributors = toolContributors.orderedStream().toList();
    }

    @Override
    public List<ToolDefinition> resolve(List<String> enabledToolNames, ToolContext context) {
        if (enabledToolNames == null || enabledToolNames.isEmpty()) {
            return List.of();
        }
        Declarations decl = ensureDeclarations();
        ToolContext ctx = context != null ? context : ToolContext.empty();
        // 先确保目录已加载（catalogDegraded 在加载失败时置位，随后读取才是最终态）
        Map<String, ToolDefinition> current = ensureCatalog();

        List<ToolDefinition> resolved = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (String name : enabledToolNames) {
            if (name == null || name.isBlank() || !seen.add(name)) {
                continue;
            }
            // ① 条件不可用 ⇒ 摘除（不依赖工具目录，目录降级时照常生效）
            Predicate<ToolContext> available = decl.availability().get(name);
            if (available != null && !testSafely(available, ctx, name)) {
                continue;
            }
            // ③ 未知工具 ⇒ 跳过；目录降级 ⇒ fail-open 保留（「未知」不构成摘除理由）
            ToolDefinition fromCatalog = current.get(name);
            if (fromCatalog == null && !catalogDegraded) {
                continue;
            }
            // ② 生效描述：声明优先 ⇒ 目录静态 ⇒ 空白串（= 调用方保持 ToolCallback 原描述）
            String description = applySafely(decl.description().get(name), ctx, name);
            if (description == null || description.isBlank()) {
                description = fromCatalog != null ? fromCatalog.description() : "";
            }
            resolved.add(new ToolDefinition(name, description));
        }
        return resolved;
    }

    /**
     * 懒加载工具目录（首次 resolve 触发，之后复用缓存）。
     *
     * <p>加载失败降级为「空目录 + 不再重试」（catalog 赋空 Map，避免每次调用都反射），
     * 同时置 {@link #catalogDegraded} 让 {@code resolve} 对未知名字 fail-open——
     * 即目录降级时「不可用」只由声明面决定，不因平台故障而摘空模型视野。</p>
     */
    private Map<String, ToolDefinition> ensureCatalog() {
        Map<String, ToolDefinition> current = catalog;
        if (current != null) {
            return current;
        }
        synchronized (this) {
            if (catalog != null) {
                return catalog;
            }
            Map<String, ToolDefinition> built = new LinkedHashMap<>();
            try {
                ToolCallback[] callbacks = toolCallbackProvider.getToolCallbacks();
                if (callbacks != null) {
                    for (ToolCallback callback : callbacks) {
                        if (callback == null || callback.getToolDefinition() == null) {
                            continue;
                        }
                        String name = callback.getToolDefinition().name();
                        String description = callback.getToolDefinition().description();
                        if (name == null || name.isBlank()) {
                            continue;
                        }
                        built.put(name, new ToolDefinition(name, description));
                    }
                }
                log.info("ToolRegistry: 工具目录加载完成，共 {} 个工具", built.size());
            } catch (Exception e) {
                catalogDegraded = true;
                log.warn("ToolRegistry: 工具目录加载失败（降级为 fail-open：未知名字保留，不阻断执行链）: err={}",
                        e.getMessage());
            }
            catalog = built;
            return catalog;
        }
    }

    /**
     * 装配声明面（懒加载 + 双检锁，与工具目录<b>独立</b>）。
     *
     * <p>装配失败（贡献者抛异常）降级为「空声明 = 全部可用」，与 REF-1.3 前行为等价，
     * 且不阻断执行链。同名工具被两个域声明属配置错误：<b>先注册者胜</b>（保证确定性）+ WARN 可观测。</p>
     */
    private Declarations ensureDeclarations() {
        Declarations current = declarations;
        if (current != null) {
            return current;
        }
        synchronized (this) {
            if (declarations != null) {
                return declarations;
            }
            Map<String, Predicate<ToolContext>> availability = new LinkedHashMap<>();
            Map<String, Function<ToolContext, String>> description = new LinkedHashMap<>();
            try {
                for (ToolCallbackContributor contributor : toolContributors) {
                    if (contributor == null) {
                        continue;
                    }
                    mergeDeclarations(availability, contributor.toolAvailability(), contributor);
                    mergeDeclarations(description, contributor.toolDescription(), contributor);
                }
            } catch (Exception e) {
                availability.clear();
                description.clear();
                log.warn("ToolRegistry: 声明面装配失败（降级为全部可用，不阻断执行链）: err={}", e.getMessage());
            }
            log.info("ToolRegistry: 声明面装配完成，可用性声明 {} 个、动态描述声明 {} 个（贡献者 {} 个）",
                    availability.size(), description.size(), toolContributors.size());
            declarations = new Declarations(availability, description);
            return declarations;
        }
    }

    private static <T> void mergeDeclarations(Map<String, T> target, Map<String, T> source,
                                              ToolCallbackContributor contributor) {
        if (source == null || source.isEmpty()) {
            return;
        }
        for (Map.Entry<String, T> entry : source.entrySet()) {
            if (entry.getKey() == null || entry.getValue() == null) {
                continue;
            }
            if (target.putIfAbsent(entry.getKey(), entry.getValue()) != null) {
                log.warn("ToolRegistry: 工具 {} 被多个贡献者重复声明，先注册者胜，本次声明被忽略（贡献者={}）",
                        entry.getKey(), contributor.getClass().getSimpleName());
            }
        }
    }

    /**
     * 执行「条件可用」判定：声明函数违约（抛异常）⇒ WARN + <b>fail-open</b>（按可用处理）。
     * 单条声明的违约不得让整批解析失败（best-effort 契约）。
     */
    private boolean testSafely(Predicate<ToolContext> predicate, ToolContext ctx, String toolName) {
        try {
            return predicate.test(ctx);
        } catch (Exception e) {
            log.warn("ToolRegistry: 工具 {} 的可用性声明抛异常（按可用处理）: err={}", toolName, e.getMessage());
            return true;
        }
    }

    /**
     * 执行「动态描述」重写：无声明 / 声明违约（抛异常）⇒ 返回 null，
     * 由调用方回落目录静态描述（不重写）。
     */
    private String applySafely(Function<ToolContext, String> function, ToolContext ctx, String toolName) {
        if (function == null) {
            return null;
        }
        try {
            return function.apply(ctx);
        } catch (Exception e) {
            log.warn("ToolRegistry: 工具 {} 的动态描述声明抛异常（回落目录静态描述）: err={}", toolName, e.getMessage());
            return null;
        }
    }

    /** 声明面快照（工具名 → 可用性判定 / 描述重写函数）；与工具目录<b>独立</b>生命周期。 */
    private record Declarations(Map<String, Predicate<ToolContext>> availability,
                                Map<String, Function<ToolContext, String>> description) {
    }
}
