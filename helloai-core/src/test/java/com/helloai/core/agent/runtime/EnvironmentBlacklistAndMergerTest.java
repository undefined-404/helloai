package com.helloai.core.agent.runtime;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Paperclip 阶段一·1：配置 env 不可覆盖运行时 key。
 *
 * <p>覆盖 {@link EnvironmentBlacklist}（黑名单判定 / 过滤 / 拦截键清单）与
 * {@link RuntimeEnvironmentMerger}（base &lt; overrides &lt; 运行时 key 合并优先级）。</p>
 */
@DisplayName("ExecutionEnvironment env 黑名单与合并优先级")
class EnvironmentBlacklistAndMergerTest {

    // ==================== EnvironmentBlacklist ====================

    @Test
    @DisplayName("黑名单：平台前缀 HELLOAI_ 恒被拦截（大小写不敏感）")
    void shouldBlockPlatformPrefixCaseInsensitive() {
        assertThat(EnvironmentBlacklist.isBlocked("HELLOAI_CREDENTIAL_AES_KEY_BASE64")).isTrue();
        assertThat(EnvironmentBlacklist.isBlocked("helloai_anything_else")).isTrue();
        assertThat(EnvironmentBlacklist.isBlocked("HelloAI_Upper")).isTrue();
    }

    @Test
    @DisplayName("黑名单：精确敏感键被拦截（provider key / token / secret / 注入向量）")
    void shouldBlockExactSensitiveKeys() {
        assertThat(EnvironmentBlacklist.isBlocked("DEEPSEEK_API_KEY")).isTrue();
        assertThat(EnvironmentBlacklist.isBlocked("java_tool_options")).isTrue();
        assertThat(EnvironmentBlacklist.isBlocked("LD_PRELOAD")).isTrue();
        assertThat(EnvironmentBlacklist.isBlocked("_JAVA_OPTIONS")).isTrue();
        assertThat(EnvironmentBlacklist.isBlocked("MOONSHOT_API_KEY")).isTrue();
    }

    @Test
    @DisplayName("黑名单：普通业务键放行；null / 空键按命中处理")
    void shouldAllowNormalKeys() {
        assertThat(EnvironmentBlacklist.isBlocked("OPENAI_BASE_URL")).isFalse();
        assertThat(EnvironmentBlacklist.isBlocked("HTTPS_PROXY")).isFalse();
        assertThat(EnvironmentBlacklist.isBlocked("TMPDIR")).isFalse();
        assertThat(EnvironmentBlacklist.isBlocked(null)).isTrue();
        assertThat(EnvironmentBlacklist.isBlocked("")).isTrue();
    }

    @Test
    @DisplayName("过滤：命中黑名单的键被丢弃，普通键保留，null/空输入返回空")
    void shouldFilterBlockedKeys() {
        Map<String, String> overrides = new LinkedHashMap<>();
        overrides.put("HTTPS_PROXY", "http://proxy:8080");
        overrides.put("HELLOAI_CREDENTIAL_AES_KEY_BASE64", "SECRET");
        overrides.put("TMPDIR", "/tmp/agent");
        overrides.put("DEEPSEEK_API_KEY", "sk-xxx");

        Map<String, String> filtered = EnvironmentBlacklist.filter(overrides);

        assertThat(filtered).containsOnlyKeys("HTTPS_PROXY", "TMPDIR");
        assertThat(EnvironmentBlacklist.filter(null)).isEmpty();
        assertThat(EnvironmentBlacklist.filter(Map.of())).isEmpty();
    }

    @Test
    @DisplayName("拦截键清单：按输入顺序返回被拦截的键，供告警日志")
    void shouldReportBlockedKeysInOrder() {
        Map<String, String> overrides = new LinkedHashMap<>();
        overrides.put("OK_ONE", "1");
        overrides.put("helloai_secret", "2");
        overrides.put("OK_TWO", "3");
        overrides.put("LD_PRELOAD", "4");

        List<String> blocked = EnvironmentBlacklist.blockedKeys(overrides);

        assertThat(blocked).containsExactly("helloai_secret", "LD_PRELOAD");
        assertThat(EnvironmentBlacklist.blockedKeys(null)).isEmpty();
    }

    // ==================== RuntimeEnvironmentMerger ====================

    @Test
    @DisplayName("合并优先级：运行时 key 恒赢，overrides 中间，base 最底")
    void shouldMergeWithRuntimeKeysWinning() {
        Map<String, String> base = new LinkedHashMap<>();
        base.put("SHARED", "base-value");
        base.put("BASE_ONLY", "base-only");

        Map<String, String> overrides = new LinkedHashMap<>();
        overrides.put("SHARED", "override-value");
        overrides.put("OVERRIDE_ONLY", "override-only");

        Map<String, String> runtimeKeys = new LinkedHashMap<>();
        runtimeKeys.put("SHARED", "runtime-value");
        runtimeKeys.put("RUNTIME_ONLY", "runtime-only");

        Map<String, String> merged = RuntimeEnvironmentMerger.merge(base, overrides, runtimeKeys);

        assertThat(merged.get("SHARED")).isEqualTo("runtime-value");
        assertThat(merged.get("BASE_ONLY")).isEqualTo("base-only");
        assertThat(merged.get("OVERRIDE_ONLY")).isEqualTo("override-only");
        assertThat(merged.get("RUNTIME_ONLY")).isEqualTo("runtime-only");
    }

    @Test
    @DisplayName("合并：overrides 中的黑名单键被丢弃，不进入合并结果")
    void shouldDropBlockedKeysDuringMerge() {
        Map<String, String> overrides = new LinkedHashMap<>();
        overrides.put("HTTPS_PROXY", "http://proxy:8080");
        overrides.put("HELLOAI_CREDENTIAL_AES_KEY_BASE64", "SECRET");

        Map<String, String> merged = RuntimeEnvironmentMerger.merge(null, overrides, null);

        assertThat(merged).containsOnlyKeys("HTTPS_PROXY");
        assertThat(merged).doesNotContainKey("HELLOAI_CREDENTIAL_AES_KEY_BASE64");
    }

    @Test
    @DisplayName("合并：三类来源均 null/空时返回空 Map")
    void shouldReturnEmptyWhenAllSourcesNull() {
        assertThat(RuntimeEnvironmentMerger.merge(null, null, null)).isEmpty();
        assertThat(RuntimeEnvironmentMerger.merge(Map.of(), Map.of(), Map.of())).isEmpty();
    }

    @Test
    @DisplayName("合并：base 缺失键由 overrides/运行时补齐，顺序稳定")
    void shouldFillMissingKeysFromHigherPriority() {
        Map<String, String> base = Map.of("A", "a", "B", "b");
        Map<String, String> overrides = Map.of("B", "b2", "C", "c");
        Map<String, String> runtimeKeys = Map.of("D", "d");

        Map<String, String> merged = RuntimeEnvironmentMerger.merge(base, overrides, runtimeKeys);

        assertThat(merged).containsEntry("A", "a")
                .containsEntry("B", "b2")
                .containsEntry("C", "c")
                .containsEntry("D", "d");
    }
}
