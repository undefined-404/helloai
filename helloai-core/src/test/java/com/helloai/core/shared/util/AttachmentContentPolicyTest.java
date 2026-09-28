package com.helloai.core.shared.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * AttachmentContentPolicy 口径单源断言（G-016）。
 *
 * <p>附件限额常量与文本/媒体族判定为报告链与核验链共享的单一口径：
 * 本测试锁定常量值与判定语义，任何一方改动限额/判定都会在此暴露漂移。</p>
 */
@DisplayName("AttachmentContentPolicy 附件注入口径单源")
class AttachmentContentPolicyTest {

    @Test
    @DisplayName("限额常量锁定：摘要 4000 / 每附件 8000 / 总计 24000")
    void shouldLockLimitConstants() {
        assertThat(AttachmentContentPolicy.OUTPUT_SUMMARY_LIMIT).isEqualTo(4000);
        assertThat(AttachmentContentPolicy.ATTACHMENT_CONTENT_PER_FILE_LIMIT).isEqualTo(8000);
        assertThat(AttachmentContentPolicy.ATTACHMENT_CONTENT_TOTAL_LIMIT).isEqualTo(24000);
    }

    @Test
    @DisplayName("文本族判定：text/*、application 文本族、扩展名兜底、octet-stream 回退")
    void shouldJudgeTextualFamily() {
        // text/* 前缀命中
        assertThat(AttachmentContentPolicy.isTextual("text/plain", "readme.txt")).isTrue();
        assertThat(AttachmentContentPolicy.isTextual("text/markdown", null)).isTrue();
        // 文本族 application 精确集（大小写不敏感）
        assertThat(AttachmentContentPolicy.isTextual("application/json", "data.json")).isTrue();
        assertThat(AttachmentContentPolicy.isTextual("APPLICATION/YAML", "cfg.yaml")).isTrue();
        // mimeType 缺失：扩展名兜底
        assertThat(AttachmentContentPolicy.isTextual(null, "schema.sql")).isTrue();
        assertThat(AttachmentContentPolicy.isTextual(null, "Report.md")).isTrue();
        // octet-stream：回退扩展名判定
        assertThat(AttachmentContentPolicy.isTextual("application/octet-stream", "run.sh")).isTrue();
        // 非文本族 MIME + 未知扩展名：fail-close 非文本
        assertThat(AttachmentContentPolicy.isTextual("application/pdf", "doc.pdf")).isFalse();
        assertThat(AttachmentContentPolicy.isTextual("image/png", "pic.png")).isFalse();
        assertThat(AttachmentContentPolicy.isTextual(null, "archive.zip")).isFalse();
        assertThat(AttachmentContentPolicy.isTextual(null, null)).isFalse();
    }

    @Test
    @DisplayName("媒体族判定：image/audio/video 前缀、扩展名兜底")
    void shouldJudgeMediaFamily() {
        assertThat(AttachmentContentPolicy.isMedia("image/png", "pic.png")).isTrue();
        assertThat(AttachmentContentPolicy.isMedia("audio/mpeg", "a.mp3")).isTrue();
        assertThat(AttachmentContentPolicy.isMedia("video/mp4", "v.mp4")).isTrue();
        assertThat(AttachmentContentPolicy.isMedia(null, "screen.webp")).isTrue();
        assertThat(AttachmentContentPolicy.isMedia("application/octet-stream", "movie.mkv")).isTrue();
        assertThat(AttachmentContentPolicy.isMedia("text/plain", "readme.txt")).isFalse();
        assertThat(AttachmentContentPolicy.isMedia(null, "data.json")).isFalse();
    }

    @Test
    @DisplayName("文本与媒体判定互斥：同一附件不会既文本又媒体")
    void shouldNotOverlapFamilies() {
        String[] names = {"pic.png", "video.mp4", "audio.wav", "doc.pdf", "archive.zip"};
        for (String name : names) {
            boolean textual = AttachmentContentPolicy.isTextual(null, name);
            boolean media = AttachmentContentPolicy.isMedia(null, name);
            assertThat(textual && media)
                    .as("附件族判定互斥失败: %s", name)
                    .isFalse();
        }
    }

    @Test
    @DisplayName("扩展名解析：小写、去点、无扩展名/空名返回空串")
    void shouldResolveExtension() {
        assertThat(AttachmentContentPolicy.extensionOf("Report.MD")).isEqualTo("md");
        assertThat(AttachmentContentPolicy.extensionOf("a.b.c.java")).isEqualTo("java");
        assertThat(AttachmentContentPolicy.extensionOf("noext")).isEmpty();
        assertThat(AttachmentContentPolicy.extensionOf("trailing.")).isEmpty();
        assertThat(AttachmentContentPolicy.extensionOf(null)).isEmpty();
        assertThat(AttachmentContentPolicy.extensionOf("")).isEmpty();
    }
}