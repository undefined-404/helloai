package com.helloai.core.planner;

import com.helloai.common.constant.AcceptanceKind;
import com.helloai.common.constant.TaskArchetype;
import com.helloai.common.constant.UncertaintyKind;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * `TD-1` / `TD-2` / `TD-7` 三个值域枚举的归一化契约。
 *
 * <p>三者共用同一套口径：**非法 / 空白 / null 一律回落默认值**（落到「改造前的行为」），
 * 而库表 CHECK 约束是最后一道 fail-close。这里只锁住 Java 侧归一化行为——
 * 它是 {@code updateTask} / {@code updateDraft} / 拆解落库三处「人工或模型给了脏值」的唯一收口点。</p>
 *
 * <p>放在 {@code helloai-core} 的测试树而非 {@code helloai-common}：后者没有测试依赖
 * （pom 无 junit / assertj），而 core 依赖 common，验证效果等价。</p>
 */
@DisplayName("拆解领域值域枚举归一化（TD-1 / TD-2 / TD-7）")
class DecompositionEnumTest {

    @Test
    @DisplayName("TaskArchetype：六值合法，非法/空白/null 回落 BUILD")
    void taskArchetypeFallback() {
        assertThat(TaskArchetype.normalize("build")).isEqualTo("BUILD");
        assertThat(TaskArchetype.normalize(" decide ")).isEqualTo("DECIDE");
        assertThat(TaskArchetype.normalize("NOPE")).isEqualTo("BUILD");
        assertThat(TaskArchetype.normalize("")).isEqualTo("BUILD");
        assertThat(TaskArchetype.normalize(null)).isEqualTo("BUILD");
        assertThat(TaskArchetype.DEFAULT()).isEqualTo(TaskArchetype.BUILD);
    }

    @Test
    @DisplayName("TaskArchetype：RESEARCH / DECIDE 不是静态 DAG 友好类型（TD-10 触发判据）")
    void staticPlanFriendly() {
        assertThat(TaskArchetype.BUILD.staticPlanFriendly()).isTrue();
        assertThat(TaskArchetype.DOCUMENT.staticPlanFriendly()).isTrue();
        assertThat(TaskArchetype.DATA.staticPlanFriendly()).isTrue();
        assertThat(TaskArchetype.OPERATE.staticPlanFriendly()).isTrue();
        assertThat(TaskArchetype.RESEARCH.staticPlanFriendly()).isFalse();
        assertThat(TaskArchetype.DECIDE.staticPlanFriendly()).isFalse();
    }

    @Test
    @DisplayName("AcceptanceKind：四值合法，非法/空白/null 回落 AUTOMATED")
    void acceptanceKindFallback() {
        assertThat(AcceptanceKind.normalize("structural")).isEqualTo("STRUCTURAL");
        assertThat(AcceptanceKind.normalize("Attested")).isEqualTo("ATTESTED");
        assertThat(AcceptanceKind.normalize("MAYBE")).isEqualTo("AUTOMATED");
        assertThat(AcceptanceKind.normalize(null)).isEqualTo("AUTOMATED");
        assertThat(AcceptanceKind.DEFAULT()).isEqualTo(AcceptanceKind.AUTOMATED);
    }

    @Test
    @DisplayName("UncertaintyKind：四值合法，非法/空白/null 回落 UNCONFIRMED")
    void uncertaintyKindFallback() {
        assertThat(UncertaintyKind.isKnown("ASSUMPTION")).isTrue();
        assertThat(UncertaintyKind.isKnown("UNCONFIRMED")).isTrue();
        assertThat(UncertaintyKind.isKnown("TO_LOOK_UP")).isTrue();
        assertThat(UncertaintyKind.isKnown("to_derive")).isTrue();
        assertThat(UncertaintyKind.isKnown("WEIRD")).isFalse();
        assertThat(UncertaintyKind.isKnown("")).isFalse();
        assertThat(UncertaintyKind.isKnown(null)).isFalse();
        assertThat(UncertaintyKind.parse("WEIRD")).isEqualTo(UncertaintyKind.UNCONFIRMED);
        assertThat(UncertaintyKind.normalize(null)).isEqualTo("UNCONFIRMED");
    }
}
