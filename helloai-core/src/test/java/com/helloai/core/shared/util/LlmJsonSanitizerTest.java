package com.helloai.core.shared.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link LlmJsonSanitizer} 单元测试：非法转义修复不破坏合法 JSON。
 */
class LlmJsonSanitizerTest {

    @Test
    @DisplayName("Windows 路径未转义反斜杠：\\w 补成 \\\\w，内容不丢")
    void shouldFixUnescapedWindowsPath() {
        String raw = "{\"message\": \"分析E:\\workspace\\AgentTeams-main项目\"}";
        String fixed = LlmJsonSanitizer.fixInvalidEscapes(raw);
        assertThat(fixed).isEqualTo("{\"message\": \"分析E:\\\\workspace\\\\AgentTeams-main项目\"}");
    }

    @Test
    @DisplayName("合法转义原样保留：\\n \\\" \\\\ \\uXXXX 不被改写")
    void shouldKeepLegalEscapes() {
        String raw = "{\"a\": \"x\\ny\", \"b\": \"q\\\"z\", \"c\": \"d\\\\e\", \"d\": \"\\u4e2d\"}";
        assertThat(LlmJsonSanitizer.fixInvalidEscapes(raw)).isEqualTo(raw);
    }

    @Test
    @DisplayName("\\u 后不足 4 位十六进制视为非法转义补反斜杠")
    void shouldFixInvalidUnicodeEscape() {
        String raw = "{\"p\": \"C:\\users\\x\"}";
        String fixed = LlmJsonSanitizer.fixInvalidEscapes(raw);
        assertThat(fixed).isEqualTo("{\"p\": \"C:\\\\users\\\\x\"}");
    }

    @Test
    @DisplayName("null / 无反斜杠输入原样返回")
    void shouldReturnAsIsWhenNoBackslash() {
        assertThat(LlmJsonSanitizer.fixInvalidEscapes(null)).isNull();
        String plain = "{\"a\": 1}";
        assertThat(LlmJsonSanitizer.fixInvalidEscapes(plain)).isSameAs(plain);
    }

    @Test
    @DisplayName("字符串外的反斜杠不处理（非字符串区域原样透传）")
    void shouldNotTouchBackslashOutsideString() {
        String raw = "{\"a\": 1}\\";
        assertThat(LlmJsonSanitizer.fixInvalidEscapes(raw)).isEqualTo(raw);
    }

    // ---------- repairBrokenStrings（2026-10-18：LLM 裸写引号/换行导致结构错乱） ----------

    @Test
    @DisplayName("repairBrokenStrings：字段值内裸英文引号 → 转义，JSON 可被 Jackson 解析")
    void repairShouldEscapeInnerQuotes() throws Exception {
        // 复现实测故障：message 里写 “稳妥档”（英文引号未转义）⇒ 提前结束字符串
        // ⇒ Unexpected character ('下'): was expecting comma to separate Object entries
        String broken = "{\"type\":\"final\",\"message\":\"会按\"稳妥档/进取档\"两档处理\","
                + "\"description\":\"## 背景与目标\n\n- 现状：下一位\"}";
        String repaired = LlmJsonSanitizer.repairBrokenStrings(broken);

        com.fasterxml.jackson.databind.JsonNode node =
                new com.fasterxml.jackson.databind.ObjectMapper().readTree(repaired);
        assertThat(node.path("message").asText()).contains("稳妥档/进取档");
        assertThat(node.path("description").asText()).contains("现状");
    }

    @Test
    @DisplayName("repairBrokenStrings：字段值内裸换行 → 转义为 \\n，Markdown 分段保留")
    void repairShouldEscapeRawNewlines() throws Exception {
        String broken = "{\"type\":\"final\",\"description\":\"## 背景\n\n- 现状：身高 172cm\n- 饮食：正常\"}";
        String repaired = LlmJsonSanitizer.repairBrokenStrings(broken);

        com.fasterxml.jackson.databind.JsonNode node =
                new com.fasterxml.jackson.databind.ObjectMapper().readTree(repaired);
        assertThat(node.path("description").asText())
                .contains("## 背景")
                .contains("\n")
                .contains("- 饮食：正常");
    }

    @Test
    @DisplayName("repairBrokenStrings：合规 JSON 幂等（结构引号不被误转义）")
    void repairShouldKeepValidJsonIntact() throws Exception {
        String valid = "{\"type\":\"final\",\"title\":\"标题\",\"package\":{\"scope\":[\"A\",\"B\"]}}";
        String repaired = LlmJsonSanitizer.repairBrokenStrings(valid);

        assertThat(repaired).isEqualTo(valid);
        assertThat(new com.fasterxml.jackson.databind.ObjectMapper().readTree(repaired)
                .path("package").path("scope")).hasSize(2);
    }
}
