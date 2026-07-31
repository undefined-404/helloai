package com.helloai.core.agent.output;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ExecutionOutputParser 单元测试：纯文本 → 单 Markdown 文件的解析规则
 * 与文件名清洗（方案2 当前形态）。
 */
@DisplayName("ExecutionOutputParser 执行产出解析")
class ExecutionOutputParserTest {

    private final ExecutionOutputParser parser = new ExecutionOutputParser();

    @Test
    @DisplayName("空/null 产出返回空结果，不物化")
    void shouldReturnEmptyWhenOutputBlank() {
        assertThat(parser.parse("标题", null).isEmpty()).isTrue();
        assertThat(parser.parse("标题", "").isEmpty()).isTrue();
        assertThat(parser.parse("标题", "   \n\t").isEmpty()).isTrue();
    }

    @Test
    @DisplayName("非空产出整体作为一个 .md 文件，内容原样保留")
    void shouldWrapOutputAsSingleMarkdownFile() {
        String output = "# 报告\n\n正文内容";
        ParsedOutput parsed = parser.parse("调度分析", output);

        assertThat(parsed.isEmpty()).isFalse();
        assertThat(parsed.files()).hasSize(1);
        ArtifactFile file = parsed.files().get(0);
        assertThat(file.fileName()).isEqualTo("调度分析.md");
        assertThat(file.mimeType()).isEqualTo("text/markdown");
        assertThat(file.content()).isEqualTo(output);
    }

    @Test
    @DisplayName("标题保留字符清洗为下划线")
    void shouldSanitizeReservedCharsInTitle() {
        assertThat(ExecutionOutputParser.buildFileName("a/b\\c:d*e?f\"g<h>i|j"))
                .isEqualTo("a_b_c_d_e_f_g_h_i_j.md");
        assertThat(ExecutionOutputParser.buildFileName("换行\n制表\t标题"))
                .isEqualTo("换行_制表_标题.md");
    }

    @Test
    @DisplayName("标题空白兜底 output.md")
    void shouldFallbackWhenTitleBlank() {
        assertThat(ExecutionOutputParser.buildFileName(null)).isEqualTo("output.md");
        assertThat(ExecutionOutputParser.buildFileName("   ")).isEqualTo("output.md");
    }

    @Test
    @DisplayName("超长标题截断到 60 字符后再加 .md")
    void shouldTruncateLongTitle() {
        String longTitle = "标".repeat(80);
        String fileName = ExecutionOutputParser.buildFileName(longTitle);
        assertThat(fileName).isEqualTo("标".repeat(60) + ".md");
    }
}
