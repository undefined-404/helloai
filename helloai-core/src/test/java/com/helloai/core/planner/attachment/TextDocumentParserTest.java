package com.helloai.core.planner.attachment;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 文本族直读（方案 §3.3 / LOG-20261022-011）。
 *
 * <p>核心断言是「原文不走样」：用户上传的 md/代码要原样给模型看，
 * 因此 {@code #}、{@code |}、反引号、{@code {{VAR}}} 一个都不能被转义。
 * 这条正是「不把文本族交给 Tika 的 Markdown handler」的原因，属于回归防线。</p>
 */
@DisplayName("TextDocumentParser 文本族直读")
class TextDocumentParserTest {

    private final TextDocumentParser parser = new TextDocumentParser();

    @Test
    @DisplayName("md 原文不被转义：标题/表格/反引号/模板占位都原样保留")
    void shouldKeepMarkdownAsIs() {
        String md = "# 标题\n\n- 列表项 `code`\n\n|a|b|\n|---|---|\n|1|2|\n\n模板：{{REQUIRED_SKILLS}}\n";
        ParsedContent content = parser.parse(
                new ParsedInput("note.md", "text/markdown", md.getBytes(StandardCharsets.UTF_8)));

        assertThat(content.parser()).isEqualTo("text");
        assertThat(content.text()).isEqualTo(md);
        assertThat(content.text()).doesNotContain("\\#").doesNotContain("\\|").doesNotContain("\\`");
    }

    @Test
    @DisplayName("代码文件原样：方括号、位或运算符、注释里的 # 都不被转义，也不插文件名前缀")
    void shouldKeepCodeAsIs() {
        String code = "public class Demo {\n"
                + "    String tpl = \"{{REQUIRED_SKILLS}}\";\n"
                + "    int x = a | b; // bitwise\n"
                + "    String[] arr = new String[0];\n"
                + "}\n";
        ParsedContent content = parser.parse(
                new ParsedInput("Demo.java", "text/x-java-source", code.getBytes(StandardCharsets.UTF_8)));

        assertThat(content.text()).isEqualTo(code);
        assertThat(content.text()).doesNotContain("Demo.javapublic");
        assertThat(content.text()).doesNotContain("String\\[\\]");
    }

    @Test
    @DisplayName("GBK 中文 txt：判码后不乱码，charset 落到结果里可观测")
    void shouldDecodeGbkTxt() {
        Charset gbk = Charset.forName("GBK");
        String text = "需求说明：并发模型与限流策略。";
        ParsedContent content = parser.parse(
                new ParsedInput("legacy.txt", "text/plain", text.getBytes(gbk)));

        assertThat(content.text()).isEqualTo(text);
        assertThat(content.charset()).isIn("GB18030", "GBK", "GB2312");
    }

    @Test
    @DisplayName("超 200k 字符：截断并显式标注，避免模型把前 200k 当全文")
    void shouldTruncateWithMarker() {
        String big = "x".repeat(DocumentParser.MAX_EXTRACTED_CHARS + 500);
        ParsedContent content = parser.parse(
                new ParsedInput("big.txt", "text/plain", big.getBytes(StandardCharsets.UTF_8)));

        assertThat(content.truncated()).isTrue();
        assertThat(content.chars()).isGreaterThan(DocumentParser.MAX_EXTRACTED_CHARS);
        assertThat(content.text()).contains("正文已截断").contains(String.valueOf(DocumentParser.MAX_EXTRACTED_CHARS));
    }

    @Test
    @DisplayName("空文件：返回空正文，不抛异常")
    void shouldHandleEmptyFile() {
        ParsedContent content = parser.parse(new ParsedInput("empty.txt", "text/plain", new byte[0]));
        assertThat(content.text()).isEmpty();
        assertThat(content.truncated()).isFalse();
    }
}
