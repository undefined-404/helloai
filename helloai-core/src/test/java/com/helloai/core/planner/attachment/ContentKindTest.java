package com.helloai.core.planner.attachment;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 内容族判定（方案 §3.3 路由依据）。
 *
 * <p>锁定的语义：文本族直读、结构化格式走 Tika、图片 P0 跳过、未知拒收。
 * 尤其要锁住 html——它同时出现在 {@code AttachmentContentPolicy} 的文本扩展名集里，
 * 但解析路由必须归结构化（转 Markdown），否则原始标签会原样灌进 Prompt。</p>
 */
@DisplayName("ContentKind 内容族判定")
class ContentKindTest {

    @Test
    @DisplayName("结构化格式：pdf / docx / pptx / xlsx / html 都归 STRUCTURED")
    void shouldRouteStructuredFormats() {
        assertThat(ContentKind.of("application/pdf", "spec.pdf")).isEqualTo(ContentKind.STRUCTURED);
        assertThat(ContentKind.of("application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                "spec.docx")).isEqualTo(ContentKind.STRUCTURED);
        assertThat(ContentKind.of("application/vnd.openxmlformats-officedocument.presentationml.presentation",
                "deck.pptx")).isEqualTo(ContentKind.STRUCTURED);
        assertThat(ContentKind.of("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                "data.xlsx")).isEqualTo(ContentKind.STRUCTURED);
        assertThat(ContentKind.of("text/html", "page.html")).isEqualTo(ContentKind.STRUCTURED);
    }

    @Test
    @DisplayName("MIME 缺失时按扩展名兜底：pdf 仍结构化、md 仍文本")
    void shouldFallbackByExtensionWhenMimeMissing() {
        assertThat(ContentKind.of(null, "spec.pdf")).isEqualTo(ContentKind.STRUCTURED);
        assertThat(ContentKind.of("application/octet-stream", "page.html")).isEqualTo(ContentKind.STRUCTURED);
        assertThat(ContentKind.of(null, "notes.md")).isEqualTo(ContentKind.TEXT);
        assertThat(ContentKind.of("application/octet-stream", "Main.java")).isEqualTo(ContentKind.TEXT);
    }

    @Test
    @DisplayName("文本族：md / txt / csv / json / 代码都归 TEXT（直读，不经 Tika 渲染）")
    void shouldRouteTextFormats() {
        assertThat(ContentKind.of("text/markdown", "notes.md")).isEqualTo(ContentKind.TEXT);
        assertThat(ContentKind.of("text/plain", "a.txt")).isEqualTo(ContentKind.TEXT);
        assertThat(ContentKind.of("text/csv", "b.csv")).isEqualTo(ContentKind.TEXT);
        assertThat(ContentKind.of("application/json", "c.json")).isEqualTo(ContentKind.TEXT);
        assertThat(ContentKind.of("text/x-java-source", "Main.java")).isEqualTo(ContentKind.TEXT);
    }

    @Test
    @DisplayName("图片/音视频归 MEDIA（P0 无解析器 → SKIPPED，不当作文本读）")
    void shouldRouteMedia() {
        assertThat(ContentKind.of("image/png", "shot.png")).isEqualTo(ContentKind.MEDIA);
        assertThat(ContentKind.of(null, "shot.jpeg")).isEqualTo(ContentKind.MEDIA);
        assertThat(ContentKind.of("video/mp4", "demo.mp4")).isEqualTo(ContentKind.MEDIA);
    }

    @Test
    @DisplayName("不认识的类型归 UNSUPPORTED（入口直接拒收，不进解析队列）")
    void shouldRouteUnknown() {
        assertThat(ContentKind.of("application/x-msdownload", "setup.exe")).isEqualTo(ContentKind.UNSUPPORTED);
        assertThat(ContentKind.of(null, "noext")).isEqualTo(ContentKind.UNSUPPORTED);
    }
}
