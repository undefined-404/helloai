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
}
