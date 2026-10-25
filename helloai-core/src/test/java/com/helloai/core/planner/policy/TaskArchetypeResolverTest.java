package com.helloai.core.planner.policy;

import com.helloai.common.constant.TaskArchetype;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * `B3` 终稿 {@code archetype} 判定：**回落允许，但必须可区分**。
 *
 * <p>正例取 2026-10-25 真机现场：任务《三家竞品核心功能与定价分析报告》的终稿包顶层键是
 * {@code goal / scope / outOfScope / assumptions / openQuestions / acceptanceCriteria}，
 * 没有 {@code archetype}，落库却是 {@code BUILD}——本类要保证这种情况
 * **一定**带出 {@code fallbackReason}，而不是和「模型真的判成 BUILD」混在一起。</p>
 */
class TaskArchetypeResolverTest {

    /** 假想中的「模型显式判成 BUILD」。 */
    private static Map<String, Object> packageWith(String archetype) {
        Map<String, Object> pkg = new LinkedHashMap<>();
        pkg.put("goal", "三家竞品核心功能与定价分析");
        if (archetype != null) {
            pkg.put("archetype", archetype);
        }
        return pkg;
    }

    @Test
    @DisplayName("真机现场：终稿包无 archetype 键 ⇒ 回落 BUILD 且带 key_missing")
    void missingKeyIsFallback() {
        TaskArchetypeResolver.Decision d = TaskArchetypeResolver.resolve(packageWith(null));

        assertThat(d.value()).isEqualTo(TaskArchetype.BUILD.name());
        assertThat(d.fallback()).isTrue();
        assertThat(d.fallbackReason()).isEqualTo(TaskArchetypeResolver.REASON_KEY_MISSING);
        assertThat(d.rawValue()).isNull();
    }

    @Test
    @DisplayName("显式 BUILD 不是回落：fallbackReason 必须为 null（否则「判断」与「兜底」无法区分）")
    void explicitBuildIsNotFallback() {
        TaskArchetypeResolver.Decision d = TaskArchetypeResolver.resolve(packageWith("BUILD"));

        assertThat(d.value()).isEqualTo(TaskArchetype.BUILD.name());
        assertThat(d.fallback()).isFalse();
        assertThat(d.fallbackReason()).isNull();
    }

    @Test
    @DisplayName("正常判定：DOCUMENT 原样采用，小写也归一化")
    void documentIsAccepted() {
        assertThat(TaskArchetypeResolver.resolve(packageWith("DOCUMENT")).value())
                .isEqualTo(TaskArchetype.DOCUMENT.name());
        TaskArchetypeResolver.Decision lower = TaskArchetypeResolver.resolve(packageWith("document"));
        assertThat(lower.value()).isEqualTo(TaskArchetype.DOCUMENT.name());
        assertThat(lower.fallback()).isFalse();
    }

    @Test
    @DisplayName("非法值 ⇒ 回落 BUILD 且带 invalid_value + 原始字符串（排障要看原文）")
    void invalidValueIsFallback() {
        TaskArchetypeResolver.Decision d = TaskArchetypeResolver.resolve(packageWith("ANALYSIS"));

        assertThat(d.value()).isEqualTo(TaskArchetype.BUILD.name());
        assertThat(d.fallbackReason()).isEqualTo(TaskArchetypeResolver.REASON_INVALID_VALUE);
        assertThat(d.rawValue()).isEqualTo("ANALYSIS");
    }

    @Test
    @DisplayName("空白值等同缺失（不能因为「给了空串」就当成显式声明）")
    void blankValueTreatedAsMissing() {
        TaskArchetypeResolver.Decision d = TaskArchetypeResolver.resolve(packageWith("   "));

        assertThat(d.fallbackReason()).isEqualTo(TaskArchetypeResolver.REASON_KEY_MISSING);
    }

    @Test
    @DisplayName("终稿包为空 / null ⇒ package_empty，不是 key_missing")
    void emptyPackageHasOwnReason() {
        assertThat(TaskArchetypeResolver.resolve(null).fallbackReason())
                .isEqualTo(TaskArchetypeResolver.REASON_PACKAGE_EMPTY);
        assertThat(TaskArchetypeResolver.resolve(Map.of()).fallbackReason())
                .isEqualTo(TaskArchetypeResolver.REASON_PACKAGE_EMPTY);
    }

    @Test
    @DisplayName("isKnown 不做回落：null / 空白 / 非法一律 false（这是 parse 无法表达的事实）")
    void isKnownDoesNotFallback() {
        assertThat(TaskArchetype.isKnown(null)).isFalse();
        assertThat(TaskArchetype.isKnown("  ")).isFalse();
        assertThat(TaskArchetype.isKnown("ANALYSIS")).isFalse();
        assertThat(TaskArchetype.isKnown("decide")).isTrue();
        // 同样的入参，parse 会给出非 null 的 BUILD —— 不能拿它反推「是否显式声明」
        assertThat(TaskArchetype.parse(null)).isEqualTo(TaskArchetype.BUILD);
    }
}
