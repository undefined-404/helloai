package com.helloai.core.agent.runtime;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 执行环境 env 黑名单（Paperclip 阶段一·1：配置 env 不可覆盖运行时 key）。
 *
 * <p><b>定位</b>：纯函数守卫 —— 输入一个 env 键值 Map，输出过滤掉黑名单键后的新 Map。
 * 供任何「配置 env → 执行环境」装配路径统一调用，防止配置页填一个 env 就拿到
 * 平台敏感 key（典型越权路径）。</p>
 *
 * <p><b>黑名单口径（fail-closed 偏严）</b>：
 * <ul>
 *   <li><b>前缀黑名单</b>：{@code HELLOAI_} —— 平台所有内部约定前缀，外部配置一律不得注入；</li>
 *   <li><b>精确黑名单</b>：凭据主密钥 / provider key / 注入类攻击向量
 *       （{@code HELLOAI_CREDENTIAL_AES_KEY_BASE64}、{@code *_API_KEY}、{@code *_TOKEN}、
 *       {@code *_SECRET}、{@code JAVA_TOOL_OPTIONS}、{@code LD_PRELOAD}、{@code _JAVA_OPTIONS}）。</li>
 * </ul>
 * 大小写不敏感（env 键在多数平台大小写不敏感，统一按小写比较）。</p>
 *
 * <p><b>行为</b>：命中黑名单的键 <b>丢弃 + 返回被丢弃键清单</b>（供调用方告警日志），
 * <b>不拒绝整个执行</b> —— 只丢键、不阻断，符合「配置 env 是增强项」的定位。</p>
 *
 * <p><b>纯函数</b>：无状态、无 IO、无 Spring 依赖，可独立单测。</p>
 */
public final class EnvironmentBlacklist {

    /** 平台内部约定前缀（小写比较）。 */
    public static final String BLOCKED_PREFIX = "helloai_";

    /** 精确黑名单（小写比较）。 */
    public static final Set<String> BLOCKED_KEYS = Set.of(
            // 凭据主密钥（AES-GCM 解密 credential_vault 全量凭据）
            "helloai_credential_aes_key_base64",
            // provider 真实 key / token / secret（运行时 key 范畴，禁止配置注入覆盖）
            "deepseek_api_key", "dashscope_api_key", "moonshot_api_key", "minimax_api_key",
            // 注入类攻击向量（JVM / 动态库预加载）
            "java_tool_options", "_java_options", "ld_preload", "ld_library_path"
    );

    private EnvironmentBlacklist() {
        // 工具类：禁止实例化
    }

    /**
     * 过滤黑名单键。
     *
     * @param overrides 配置 env 键值（可为 null / 空）
     * @return 过滤后的新 Map（保留插入顺序）；null 输入返回空 Map
     */
    public static Map<String, String> filter(Map<String, String> overrides) {
        if (overrides == null || overrides.isEmpty()) {
            return Map.of();
        }
        Map<String, String> result = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : overrides.entrySet()) {
            if (entry.getKey() != null && !isBlocked(entry.getKey())) {
                result.put(entry.getKey(), entry.getValue());
            }
        }
        return result;
    }

    /**
     * 判定某键是否命中黑名单（大小写不敏感）。
     *
     * @param key env 键（可为 null）
     * @return true = 命中黑名单，应丢弃
     */
    public static boolean isBlocked(String key) {
        if (key == null || key.isEmpty()) {
            return true; // 空键无意义，按命中处理
        }
        String lower = key.toLowerCase();
        if (lower.startsWith(BLOCKED_PREFIX)) {
            return true;
        }
        return BLOCKED_KEYS.contains(lower);
    }

    /**
     * 返回输入中被黑名单拦截的键清单（供告警日志定位）。
     *
     * @param overrides 配置 env 键值（可为 null / 空）
     * @return 被拦截的键列表（保持输入顺序）
     */
    public static List<String> blockedKeys(Map<String, String> overrides) {
        if (overrides == null || overrides.isEmpty()) {
            return List.of();
        }
        return overrides.keySet().stream()
                .filter(EnvironmentBlacklist::isBlocked)
                .toList();
    }
}
