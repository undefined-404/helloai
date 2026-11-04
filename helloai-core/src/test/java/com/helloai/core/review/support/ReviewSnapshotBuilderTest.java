package com.helloai.core.review.support;

import com.helloai.core.task.port.AttachmentView;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link ReviewSnapshotBuilder} 单测（`RV-5` 快照结构 / 截断 / 体积估算 / 摘要）。
 */
@DisplayName("ReviewSnapshotBuilder（RV-5 判定快照）")
class ReviewSnapshotBuilderTest {

    private static AttachmentView attachment(long id, String name, long size) {
        return new AttachmentView(id, name, "MD", "text/markdown", size, true);
    }

    @Test
    @DisplayName("结构：schemaVersion + 附件条目 + 产出摘要（chars/sha256）")
    void buildsExpectedStructure() {
        Map<String, Object> snapshot = ReviewSnapshotBuilder.build(
                List.of(attachment(11L, "a.md", 100L), attachment(12L, "b.md", 200L)), "正文");

        assertThat(snapshot).containsEntry("schemaVersion", ReviewSnapshotBuilder.SCHEMA_VERSION)
                .containsEntry("attachmentCount", 2)
                .containsEntry("attachmentsTruncated", false);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> items = (List<Map<String, Object>>) snapshot.get("attachments");
        assertThat(items).hasSize(2);
        assertThat(items.get(0)).containsEntry("id", 11L).containsEntry("fileName", "a.md")
                .containsEntry("fileSize", 100L);
        @SuppressWarnings("unchecked")
        Map<String, Object> output = (Map<String, Object>) snapshot.get("output");
        assertThat(output).containsEntry("chars", 2).containsEntry("sha256",
                "d661c3d96d53ebc0ca8a55aae24b5df4a4d1bf28d37337b982fe8ebf54846eeb");
    }

    @Test
    @DisplayName("无产出 / 无附件：output=null、count=0、清单为空")
    void handlesNulls() {
        Map<String, Object> snapshot = ReviewSnapshotBuilder.build(List.of(), null);

        assertThat(snapshot).containsEntry("attachmentCount", 0)
                .containsEntry("attachmentsTruncated", false)
                .containsEntry("output", null);
        assertThat((List<?>) snapshot.get("attachments")).isEmpty();
    }

    @Test
    @DisplayName("附件超过上限：只留前 50 条，并标记截断（attachmentCount 保留总数）")
    void truncatesAttachmentList() {
        List<AttachmentView> many = new ArrayList<>();
        for (int i = 0; i < 55; i++) {
            many.add(attachment(i, "f" + i + ".md", i));
        }

        Map<String, Object> snapshot = ReviewSnapshotBuilder.build(many, null);

        assertThat((List<?>) snapshot.get("attachments")).hasSize(ReviewSnapshotBuilder.MAX_ATTACHMENTS);
        assertThat(snapshot).containsEntry("attachmentCount", 55)
                .containsEntry("attachmentsTruncated", true);
    }

    @Test
    @DisplayName("体积估算：随附件增长，且能识别超阈值（存储增长观测）")
    void estimatesBytesAndFlagsGrowth() {
        Map<String, Object> small = ReviewSnapshotBuilder.build(List.of(attachment(1L, "a.md", 1L)), null);
        List<AttachmentView> many = new ArrayList<>();
        // 长文件名是快照体积的唯一实际放大源（产出只存摘要）：用它触发告警路径
        for (int i = 0; i < ReviewSnapshotBuilder.MAX_ATTACHMENTS; i++) {
            many.add(attachment(i, "附".repeat(200) + i + ".md", 1024L));
        }
        Map<String, Object> large = ReviewSnapshotBuilder.build(many, "x".repeat(4096));

        assertThat(ReviewSnapshotBuilder.estimatedBytes(small))
                .isPositive()
                .isLessThan(ReviewSnapshotBuilder.estimatedBytes(large));
        assertThat(ReviewSnapshotBuilder.estimatedBytes(large)).isGreaterThan(ReviewSnapshotBuilder.WARN_BYTES);
        assertThat(ReviewSnapshotBuilder.estimatedBytes(null)).isZero();
    }

    @Test
    @DisplayName("sha256：已知向量对账（\"abc\"）")
    void sha256KnownVector() {
        assertThat(ReviewSnapshotBuilder.sha256("abc"))
                .isEqualTo("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad");
    }
}