package com.helloai.core.agent.output;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ExecutionOutputParser 单元测试：纯文本 → 单 Markdown 文件（方案2 现状）
 * 与 LLM manifest 多文件协议（方案3）的解析规则、降级与文件名清洗。
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
    @DisplayName("非空产出整体作为一个 .md 文件，内容原样保留，displayText 即原文")
    void shouldWrapOutputAsSingleMarkdownFile() {
        String output = "# 报告\n\n正文内容";
        ParsedOutput parsed = parser.parse("调度分析", output);

        assertThat(parsed.isEmpty()).isFalse();
        assertThat(parsed.files()).hasSize(1);
        ArtifactFile file = parsed.files().get(0);
        assertThat(file.fileName()).isEqualTo("调度分析.md");
        assertThat(file.mimeType()).isEqualTo("text/markdown");
        assertThat(file.content()).isEqualTo(output);
        assertThat(parsed.displayText()).isEqualTo(output);
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

    @Test
    @DisplayName("合法 manifest（围栏包裹）解析为多文件，displayText 含摘要/概览/EXECUTION_RECORD 尾部")
    void shouldParseManifestIntoMultipleFiles() {
        String output = """
                ```json
                {
                  "summary": "生成了 3 个交付文件",
                  "files": [
                    {"name": "README.md", "type": "text/markdown", "content": "# 使用说明"},
                    {"name": "main.py", "content": "print('hello')"},
                    {"name": "config.json", "type": "application/json", "content": "{\\"port\\": 8080}"}
                  ]
                }
                ```
                EXECUTION_RECORD:
                - SUMMARY: 完成交付
                - KEY_DECISIONS: 采用方案3
                """;

        ParsedOutput parsed = parser.parse("编码任务", output);

        assertThat(parsed.files()).hasSize(3);
        assertThat(parsed.files().get(0).fileName()).isEqualTo("README.md");
        assertThat(parsed.files().get(0).mimeType()).isEqualTo("text/markdown");
        assertThat(parsed.files().get(0).content()).isEqualTo("# 使用说明");
        assertThat(parsed.files().get(1).fileName()).isEqualTo("main.py");
        assertThat(parsed.files().get(1).mimeType()).isEqualTo("text/x-python");
        assertThat(parsed.files().get(1).content()).isEqualTo("print('hello')");
        assertThat(parsed.files().get(2).fileName()).isEqualTo("config.json");
        assertThat(parsed.files().get(2).mimeType()).isEqualTo("application/json");

        String displayText = parsed.displayText();
        assertThat(displayText).contains("生成了 3 个交付文件");
        assertThat(displayText).contains("## 产出文件概览");
        assertThat(displayText).contains("- README.md").contains("- main.py").contains("- config.json");
        assertThat(displayText).contains("EXECUTION_RECORD:").contains("- SUMMARY: 完成交付");
        // 文件正文不进入 displayText（避免对话流刷屏）
        assertThat(displayText).doesNotContain("print('hello')");
    }

    @Test
    @DisplayName("裸 JSON（无围栏）同样命中 manifest")
    void shouldParseBareJsonManifest() {
        String output = "{\"summary\":\"s\",\"files\":[{\"name\":\"a.txt\",\"content\":\"body\"}]}";
        ParsedOutput parsed = parser.parse("t", output);

        assertThat(parsed.files()).hasSize(1);
        assertThat(parsed.files().get(0).fileName()).isEqualTo("a.txt");
        assertThat(parsed.files().get(0).mimeType()).isEqualTo("text/plain");
    }

    @Test
    @DisplayName("manifest name 缺失/空白按序号兜底，避免多文件同名覆盖")
    void shouldFallbackBlankNameByIndex() {
        String output = """
                ```json
                {"summary":"s","files":[{"content":"1"},{"name":"..","content":"2"},{"name":"ok.txt","content":"3"}]}
                ```
                """;
        ParsedOutput parsed = parser.parse("t", output);

        assertThat(parsed.files()).hasSize(3);
        assertThat(parsed.files().get(0).fileName()).isEqualTo("output-1.txt");
        assertThat(parsed.files().get(1).fileName()).isEqualTo("output-2.txt");
        assertThat(parsed.files().get(2).fileName()).isEqualTo("ok.txt");
    }

    @Test
    @DisplayName("manifest name 路径穿越字符被清洗")
    void shouldSanitizeMaliciousName() {
        String output = """
                ```json
                {"summary":"s","files":[{"name":"../evil.sh","content":"rm -rf"}]}
                ```
                """;
        ParsedOutput parsed = parser.parse("t", output);

        assertThat(parsed.files()).hasSize(1);
        assertThat(parsed.files().get(0).fileName()).isEqualTo("_evil.sh");
        assertThat(parsed.files().get(0).mimeType()).isEqualTo("text/x-shellscript");
    }

    @Test
    @DisplayName("manifest files 为空时降级纯文本单 .md")
    void shouldDegradeWhenManifestFilesEmpty() {
        String output = """
                ```json
                {"summary":"无文件","files":[]}
                ```
                """;
        ParsedOutput parsed = parser.parse("标题", output);

        assertThat(parsed.files()).hasSize(1);
        assertThat(parsed.files().get(0).fileName()).isEqualTo("标题.md");
        assertThat(parsed.files().get(0).content()).isEqualTo(output);
    }

    @Test
    @DisplayName("非法 JSON 降级纯文本单 .md，displayText 为原文")
    void shouldDegradeWhenJsonInvalid() {
        String output = "```json\n{\"summary\": \"未闭合\n```\n正文说明";
        ParsedOutput parsed = parser.parse("标题", output);

        assertThat(parsed.files()).hasSize(1);
        assertThat(parsed.files().get(0).fileName()).isEqualTo("标题.md");
        assertThat(parsed.files().get(0).content()).isEqualTo(output);
        assertThat(parsed.displayText()).isEqualTo(output);
    }

    @Test
    @DisplayName("manifest content 缺失时兜底空串，不影响物化")
    void shouldTolerateMissingContent() {
        String output = """
                ```json
                {"summary":"s","files":[{"name":"empty.txt"}]}
                ```
                """;
        ParsedOutput parsed = parser.parse("t", output);

        assertThat(parsed.files()).hasSize(1);
        assertThat(parsed.files().get(0).content()).isEmpty();
    }

    @Test
    @DisplayName("Windows 路径非法反斜杠转义（\\d）经 LlmJsonSanitizer 修复后仍可解析")
    void shouldFixInvalidEscapesBeforeParse() {
        String output = """
                ```json
                {"summary":"路径","files":[{"name":"log.txt","content":"see E:\\docs\\data"}]}
                ```
                """;
        ParsedOutput parsed = parser.parse("t", output);

        assertThat(parsed.files()).hasSize(1);
        // \\d 为非法 JSON 转义，修复为字面反斜杠后路径原样保留
        assertThat(parsed.files().get(0).content()).contains("E:\\docs\\data");
    }
}
