package com.helloai.core.planner.attachment;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 解析路由（方案 §3.3）：按内容族挑实现，重复注册要炸出来。
 */
@DisplayName("DocumentParserRouter 解析路由")
class DocumentParserRouterTest {

    @Test
    @DisplayName("TEXT → 直读实现，STRUCTURED → Tika 实现")
    void shouldRouteByKind() {
        DocumentParserRouter router = new DocumentParserRouter(
                List.of(new TextDocumentParser(), new TikaDocumentParser()));

        assertThat(router.find(ContentKind.TEXT)).containsInstanceOf(TextDocumentParser.class);
        assertThat(router.find(ContentKind.STRUCTURED)).containsInstanceOf(TikaDocumentParser.class);

        ParsedContent text = router.parse(ContentKind.TEXT,
                new ParsedInput("a.md", "text/markdown", "# 标题".getBytes(StandardCharsets.UTF_8)));
        assertThat(text.text()).isEqualTo("# 标题");
    }

    @Test
    @DisplayName("图片/未知在没有实现时返回空，调用方据此标 SKIPPED 而不是 FAILED")
    void shouldReturnEmptyForMissingImplementation() {
        DocumentParserRouter router = new DocumentParserRouter(List.of(new TextDocumentParser()));
        assertThat(router.find(ContentKind.MEDIA)).isEmpty();
        assertThatThrownBy(() -> router.parse(ContentKind.MEDIA,
                new ParsedInput("a.png", "image/png", new byte[]{1, 2, 3})))
                .isInstanceOf(AttachmentParseException.class);
    }

    @Test
    @DisplayName("同一内容族注册两个实现：启动即失败（避免实现悄悄覆盖）")
    void shouldFailFastOnDuplicateKind() {
        List<DocumentParser> duplicated = List.of(new TextDocumentParser(), new TextDocumentParser());
        assertThatThrownBy(() -> new DocumentParserRouter(duplicated))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("多个解析器实现");
    }
}
