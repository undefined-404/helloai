package com.helloai.core.planner.attachment;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 结构化格式解析（方案 §3.3 / LOG-20261022-010）。
 *
 * <p>锁两件事：① 必须用 Markdown handler（表格输出成 {@code |a|b|}，纯文本 handler 会把结构剥平）；
 * ② 损坏文件抛 {@link AttachmentParseException}，由调用方标 FAILED 而不是静默产出空正文。</p>
 */
@DisplayName("TikaDocumentParser 结构化格式解析")
class TikaDocumentParserTest {

    private final TikaDocumentParser parser = new TikaDocumentParser();

    private static byte[] htmlWithTable() {
        String html = "<html><body><h1>接口规格</h1><table border=\"1\">"
                + "<tr><th>字段</th><th>类型</th></tr>"
                + "<tr><td>taskId</td><td>Long</td></tr>"
                + "<tr><td>priority</td><td>HIGH/MEDIUM/LOW</td></tr>"
                + "</table></body></html>";
        return html.getBytes(StandardCharsets.UTF_8);
    }

    @Test
    @DisplayName("HTML 表格 → Markdown 表格与标题（证明表格结构没被剥平）")
    void shouldConvertHtmlTableToMarkdown() {
        ParsedContent content = parser.parse(new ParsedInput("spec.html", "text/html", htmlWithTable()));

        assertThat(content.parser()).isEqualTo("tika");
        assertThat(content.text()).contains("# 接口规格");
        assertThat(content.text()).contains("|字段|类型|");
        assertThat(content.text()).contains("|taskId|Long|");
        assertThat(content.text()).contains("|priority|HIGH/MEDIUM/LOW|");
    }

    @Test
    @DisplayName("损坏的 PDF：抛 AttachmentParseException（可重试的失败，不是空正文）")
    void shouldThrowOnBrokenDocument() {
        byte[] broken = "%PDF-1.7\n 这不是一个合法的 PDF 结构".getBytes(StandardCharsets.UTF_8);

        assertThatThrownBy(() -> parser.parse(new ParsedInput("broken.pdf", "application/pdf", broken)))
                .isInstanceOf(AttachmentParseException.class);
    }

    @Test
    @DisplayName("结构化格式不产生 charset（判码只对文本族有意义）")
    void shouldNotReportCharsetForStructured() {
        ParsedContent content = parser.parse(new ParsedInput("spec.html", "text/html", htmlWithTable()));
        assertThat(content.charset()).isNull();
    }
}
