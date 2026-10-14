package com.helloai.core.system.port;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link ModelCapabilities} 解析归一（`M-9`）单测。
 *
 * <p>覆盖：完整谓词 / 缺键 / 类型不符 / 空数组 ⇒ 保守面 / 未知模态 token（pdf）原样保留不抛错 /
 * 三个 bool 宽松解析 / {@code conservative()} 与 {@code toRawMap()} 往返。</p>
 */
class ModelCapabilitiesTest {

    private static Map<String, Object> raw(Object input, Object output,
                                           Object reasoning, Object toolCalling, Object webSearch) {
        Map<String, Object> m = new HashMap<>();
        m.put("input", input);
        m.put("output", output);
        m.put("reasoning", reasoning);
        m.put("toolCalling", toolCalling);
        m.put("webSearch", webSearch);
        return m;
    }

    @Test
    @DisplayName("完整谓词：五字段原样解析")
    void parsesFullPredicate() {
        ModelCapabilities caps = ModelCapabilities.fromRaw(raw(
                List.of("text", "image"), List.of("text"), true, true, false));
        assertThat(caps.inputModalities()).containsExactly("text", "image");
        assertThat(caps.outputModalities()).containsExactly("text");
        assertThat(caps.reasoning()).isTrue();
        assertThat(caps.toolCalling()).isTrue();
        assertThat(caps.webSearch()).isFalse();
    }

    @Test
    @DisplayName("保守面①：输入/输出缺键 ⇒ [\"text\"]，bool 缺键 ⇒ false")
    void missingKeysFallBackToConservativeFace() {
        ModelCapabilities caps = ModelCapabilities.fromRaw(Map.of());
        assertThat(caps.inputModalities()).containsExactly("text");
        assertThat(caps.outputModalities()).containsExactly("text");
        assertThat(caps.reasoning()).isFalse();
        assertThat(caps.toolCalling()).isFalse();
        assertThat(caps.webSearch()).isFalse();
    }

    @Test
    @DisplayName("保守面②：input 类型不符（字符串而非数组）⇒ [\"text\"]")
    void nonArrayInputFallsBackToConservativeFace() {
        ModelCapabilities caps = ModelCapabilities.fromRaw(raw(
                "text", List.of("text"), false, false, false));
        assertThat(caps.inputModalities()).containsExactly("text");
    }

    @Test
    @DisplayName("保守面③：input 为空数组 ⇒ [\"text\"]")
    void emptyInputArrayFallsBackToConservativeFace() {
        ModelCapabilities caps = ModelCapabilities.fromRaw(raw(
                List.of(), List.of("text"), false, false, false));
        assertThat(caps.inputModalities()).containsExactly("text");
    }

    @Test
    @DisplayName("保守面④：input 元素全为 null/空白 ⇒ [\"text\"]")
    void nullOnlyInputFallsBackToConservativeFace() {
        ModelCapabilities caps = ModelCapabilities.fromRaw(raw(
                Arrays.asList(null, "  "), List.of("text"), false, false, false));
        assertThat(caps.inputModalities()).containsExactly("text");
    }

    @Test
    @DisplayName("保守面⑤：raw 为 null ⇒ 全保守面（不抛错）")
    void nullRawFallsBackToConservativeFace() {
        ModelCapabilities caps = ModelCapabilities.fromRaw(null);
        assertThat(caps.inputModalities()).containsExactly("text");
        assertThat(caps.webSearch()).isFalse();
    }

    @Test
    @DisplayName("★未知模态 token（pdf）原样保留，不抛错（V110 词表声明封闭但实测含 pdf 44 行）")
    void unknownModalityTokenPreservedWithoutError() {
        ModelCapabilities caps = ModelCapabilities.fromRaw(raw(
                List.of("text", "image", "pdf"), List.of("text"), false, true, true));
        assertThat(caps.inputModalities()).containsExactly("text", "image", "pdf");
        // 未知 token 原样保留（可在列表中），未列出的模态仍为 false
        assertThat(caps.supportsInput("pdf")).isTrue();
        assertThat(caps.supportsInput("image")).isTrue();
        assertThat(caps.supportsInput("audio")).isFalse();
    }

    @Test
    @DisplayName("三个 bool 宽松解析：Number / 字符串均可")
    void looseBooleanParsing() {
        ModelCapabilities caps = ModelCapabilities.fromRaw(raw(
                List.of("text"), List.of("text"), 1, "true", "false"));
        assertThat(caps.reasoning()).isTrue();
        assertThat(caps.toolCalling()).isTrue();
        assertThat(caps.webSearch()).isFalse();
    }

    @Test
    @DisplayName("conservative() 与 toRawMap() 往返一致")
    void conservativeRoundTrip() {
        ModelCapabilities caps = ModelCapabilities.conservative();
        assertThat(caps.inputModalities()).containsExactly("text");
        assertThat(caps.reasoning()).isFalse();
        assertThat(caps.webSearch()).isFalse();
        Map<String, Object> raw = caps.toRawMap();
        assertThat(raw).containsKeys("input", "output", "reasoning", "toolCalling", "webSearch");
        assertThat(ModelCapabilities.fromRaw(raw)).isEqualTo(caps);
    }
}
