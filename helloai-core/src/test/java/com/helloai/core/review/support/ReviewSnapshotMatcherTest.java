package com.helloai.core.review.support;

import com.helloai.core.task.port.AttachmentView;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link ReviewSnapshotMatcher} 单测（`RV-5` 复判对象对账）。
 *
 * <p>口径：无快照（历史行）/ 结构不认识 ⇒ 按一致处理（不把老数据与未来版本排除出抽检）；
 * 有快照则附件集合与产出摘要都要对上。</p>
 */
@DisplayName("ReviewSnapshotMatcher（RV-5 快照对账）")
class ReviewSnapshotMatcherTest {

    private static AttachmentView attachment(long id, long size) {
        return new AttachmentView(id, "f" + id + ".md", "MD", "text/markdown", size, true);
    }

    private static Map<String, Object> snapshotOf(List<AttachmentView> attachments, String output) {
        return ReviewSnapshotBuilder.build(attachments, output);
    }

    @Test
    @DisplayName("无快照 / 空快照 ⇒ 一致（历史行按当前视图复判，不排除老数据）")
    void legacyRecordsTreatedAsSame() {
        assertThat(ReviewSnapshotMatcher.check(null, List.of(attachment(1L, 10L)), "任意").matched()).isTrue();
        assertThat(ReviewSnapshotMatcher.check(Map.of(), List.of(), null).matched()).isTrue();
    }

    @Test
    @DisplayName("附件与产出都一致 ⇒ 一致")
    void sameEvidenceMatches() {
        Map<String, Object> snapshot = snapshotOf(List.of(attachment(1L, 10L)), "正文");

        ReviewSnapshotMatcher.Drift drift = ReviewSnapshotMatcher.check(
                snapshot, List.of(attachment(1L, 10L)), "正文");

        assertThat(drift.matched()).isTrue();
        assertThat(drift.reason()).isNull();
    }

    @Test
    @DisplayName("附件被替换（id 变）⇒ attachments_changed")
    void attachmentReplaced() {
        Map<String, Object> snapshot = snapshotOf(List.of(attachment(1L, 10L)), null);

        ReviewSnapshotMatcher.Drift drift = ReviewSnapshotMatcher.check(
                snapshot, List.of(attachment(2L, 10L)), null);

        assertThat(drift.matched()).isFalse();
        assertThat(drift.reason()).isEqualTo("attachments_changed");
    }

    @Test
    @DisplayName("同 id 但大小变（内容被覆盖）⇒ attachments_changed")
    void attachmentSizeChanged() {
        Map<String, Object> snapshot = snapshotOf(List.of(attachment(1L, 10L)), null);

        assertThat(ReviewSnapshotMatcher.check(snapshot, List.of(attachment(1L, 11L)), null).reason())
                .isEqualTo("attachments_changed");
    }

    @Test
    @DisplayName("产出内容变 ⇒ output_changed；产出从无到有 ⇒ output_appeared")
    void outputDrift() {
        Map<String, Object> withOutput = snapshotOf(List.of(), "原正文");
        Map<String, Object> noOutput = snapshotOf(List.of(), null);

        assertThat(ReviewSnapshotMatcher.check(withOutput, List.of(), "新正文").reason())
                .isEqualTo("output_changed");
        assertThat(ReviewSnapshotMatcher.check(noOutput, List.of(), "新出现的正文").reason())
                .isEqualTo("output_appeared");
    }

    @Test
    @DisplayName("JSONB 读回的数字类型不同（Integer vs Long）不被误判为漂移")
    void numericTypeDifferenceIsNotDrift() {
        Map<String, Object> item = new HashMap<>();
        item.put("id", 1);            // Integer：模拟 JSONB 读回小整数
        item.put("fileName", "f1.md");
        item.put("fileSize", 10);     // Integer
        Map<String, Object> snapshot = new HashMap<>();
        snapshot.put("schemaVersion", 1);
        snapshot.put("attachmentsTruncated", false);
        snapshot.put("attachments", List.of(item));
        snapshot.put("output", null);

        assertThat(ReviewSnapshotMatcher.check(snapshot, List.of(attachment(1L, 10L)), null).matched()).isTrue();
    }

    @Test
    @DisplayName("快照被截断时只要求「列出条目仍在」，不要求集合相等")
    void truncatedSnapshotUsesSubsetSemantics() {
        Map<String, Object> snapshot = snapshotOf(List.of(attachment(1L, 10L)), null);
        snapshot.put("attachmentsTruncated", true);

        assertThat(ReviewSnapshotMatcher.check(snapshot,
                List.of(attachment(1L, 10L), attachment(2L, 20L)), null).matched()).isTrue();
        assertThat(ReviewSnapshotMatcher.check(snapshot, List.of(), null).matched()).isFalse();
    }

    @Test
    @DisplayName("快照结构不认识（attachments 不是数组）⇒ 不拦")
    void unknownStructureDoesNotBlock() {
        Map<String, Object> snapshot = new HashMap<>();
        snapshot.put("attachments", "oops");

        assertThat(ReviewSnapshotMatcher.check(snapshot, List.of(), null).matched()).isTrue();
    }
}