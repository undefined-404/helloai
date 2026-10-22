package com.helloai.core.agent.runtime;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 执行环境 env 合并器（Paperclip 阶段一·1：配置 env 不可覆盖运行时 key）。
 *
 * <p><b>定位</b>：纯函数 —— 按固定优先级合并三类 env 来源，并在装配前对
 * 「配置 overrides」执行黑名单过滤。供任何「执行环境 → 上下文」装配路径统一调用。</p>
 *
 * <p><b>合并顺序（低 → 高，高者恒赢）</b>：
 * <pre>
 * base env &lt; 配置 overrides（经 {@link EnvironmentBlacklist} 过滤）&lt; 运行时 key（最后写入，不可被覆盖）
 * </pre>
 * 运行时 key 由平台在执行时动态解析（如 provider 凭证），具有最高优先级，
 * 配置 env 无论写什么都不能覆盖它。</p>
 *
 * <p><b>行为</b>：overrides 命中黑名单的键被丢弃（只丢键，不拒绝执行），
 * 返回的 Map 保留插入顺序（base → 过滤后 overrides → 运行时 key）。</p>
 *
 * <p><b>纯函数</b>：无状态、无 IO、无 Spring 依赖，可独立单测。</p>
 */
public final class RuntimeEnvironmentMerger {

    private RuntimeEnvironmentMerger() {
        // 工具类：禁止实例化
    }

    /**
     * 合并三类 env 来源（base &lt; overrides &lt; 运行时 key），overrides 过黑名单。
     *
     * @param base           基础 env（可为 null / 空）
     * @param overrides      配置 env overrides（可为 null / 空；黑名单键被丢弃）
     * @param runtimeKeys    运行时 key（可为 null / 空；恒最高优先级）
     * @return 合并后的新 Map（base → 过滤后 overrides → 运行时 key）
     */
    public static Map<String, String> merge(Map<String, String> base,
                                            Map<String, String> overrides,
                                            Map<String, String> runtimeKeys) {
        Map<String, String> merged = new LinkedHashMap<>();
        if (base != null) {
            merged.putAll(base);
        }
        if (overrides != null) {
            merged.putAll(EnvironmentBlacklist.filter(overrides));
        }
        if (runtimeKeys != null) {
            merged.putAll(runtimeKeys);
        }
        return merged;
    }
}
