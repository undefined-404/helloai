package com.helloai.core.agent.tool.impl;

import com.helloai.core.agent.tool.ToolDefinition;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.ToolCallback;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 「模型可见工具回调」选择器（REF-8.5 覆盖面收敛抽出的共用实现）。
 *
 * <p><b>为什么抽出</b>：本逻辑原先内嵌在 {@code RuntimeTurnExecutor#resolveVisibleCallbacks}，
 * 只有执行链一个消费方。REF-8.5 把拆解 / 子任务核验 / 终报告整合 / 终报告审查四个业务面
 * 接进循环后出现<b>第二个</b>消费方——若各自实现一份「按名字集合过滤回调」，
 * 就会形成 §10.4 判据 2「可见面单口径」所禁止的<b>第二处二次过滤</b>。
 * 故抽成纯静态工具：<b>过滤判据唯一</b>（{@link com.helloai.core.agent.tool.ToolRegistry#resolve}
 * 的结果），两个消费方逐字共用。</p>
 *
 * <p><b>语义（与抽出前逐字一致）</b>：</p>
 * <ul>
 *   <li>过滤依据 = <b>解析结果</b>（{@code resolve} 的生效形态），<b>不是</b>入参名字集合——
 *       前者含「条件可用摘除 + 描述重写」语义，后者只做名字匹配；</li>
 *   <li>描述被改写的工具以 {@link DescribedToolCallback} 委托包装，使
 *       <b>模型读到的 schema 与生效描述一致</b>；</li>
 *   <li>顺序 = 目录注册顺序（调用方按 provider 顺序传入 {@code all}）；描述相同 / 生效描述为空白
 *       ⇒ 原回调直接透传（零包装、零开销）；</li>
 *   <li>{@code resolvedTools} 为空 ⇒ 提前返回空列表：「全部被条件可用摘除」是合法情形，
 *       此时既正确又省掉一次目录遍历；</li>
 *   <li>目录不可用 / 异常 ⇒ <b>降级返回已装配部分</b>（best-effort，不阻断调用方）。</li>
 * </ul>
 *
 * <p><b>边界</b>：本类<b>不</b>做「取目录」与「并集」——那是调用方的定位差异
 * （执行链用 {@code InternalToolCallbacks.union(provider, internalToolCallbacks)} 合成
 * 对外 MCP 面 ∪ 内部进程内面）。本类只回答「给定目录与解析结果，哪些回调对模型可见」。</p>
 */
@Slf4j
public final class VisibleToolCallbacks {

    private VisibleToolCallbacks() {
    }

    /**
     * 按 {@code resolvedTools} 的「生效形态」从目录中选出模型可见回调。
     *
     * @param resolvedTools {@code ToolRegistry.resolve} 的结果（生效形态；null / 空 ⇒ 返回空列表）
     * @param all           候选目录（调用方已合成的回调数组；null ⇒ 返回空列表）
     * @return 模型可见回调（顺序 = 目录顺序）；异常时返回已装配部分
     */
    public static List<ToolCallback> select(List<ToolDefinition> resolvedTools, ToolCallback[] all) {
        if (resolvedTools == null || resolvedTools.isEmpty()) {
            return List.of();
        }
        Map<String, String> effective = new LinkedHashMap<>();
        for (ToolDefinition definition : resolvedTools) {
            effective.put(definition.name(), definition.description());
        }
        List<ToolCallback> callbacks = new ArrayList<>();
        try {
            if (all != null) {
                for (ToolCallback callback : all) {
                    if (callback == null || callback.getToolDefinition() == null) {
                        continue;
                    }
                    String name = callback.getToolDefinition().name();
                    if (!effective.containsKey(name)) {
                        continue; // 未启用 / 被条件可用摘除 ⇒ 模型不可见
                    }
                    String description = effective.get(name);
                    if (description == null || description.isBlank()
                            || description.equals(callback.getToolDefinition().description())) {
                        callbacks.add(callback); // 无生效描述 / 描述未变 ⇒ 原样透传
                    } else {
                        callbacks.add(new DescribedToolCallback(callback, description));
                    }
                }
            }
        } catch (Exception e) {
            log.warn("VisibleToolCallbacks: 工具回调解析失败（使用已装配部分）: err={}", e.getMessage());
        }
        return callbacks;
    }
}
