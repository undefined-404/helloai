package com.helloai.core.review.support;

import com.helloai.common.config.AgentDispatchProperties;
import com.helloai.common.constant.ReviewResult;
import com.helloai.common.constant.SubTaskStatus;
import com.helloai.core.task.port.SubTaskView;
import com.helloai.core.task.service.AttachmentService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;

/**
 * {@link ReviewEvidenceAssembler} 的驳回冻结证据（`RV-6`）单测。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ReviewEvidenceAssembler（RV-6 冻结证据）")
class ReviewEvidenceAssemblerFrozenTest {

    @Mock
    private AttachmentService attachmentService;
    @Mock
    private AgentDispatchProperties dispatchProperties;

    private ReviewEvidenceAssembler assembler;

    @BeforeEach
    void setUp() {
        assembler = new ReviewEvidenceAssembler(attachmentService, dispatchProperties);
        lenient().when(attachmentService.listActiveViews(any())).thenReturn(List.of());
    }

    private SubTaskView subTask() {
        return new SubTaskView(1L, 2L, SubTaskStatus.REVIEW, null, 0,
                "标题", "内容", "交付物", "验收", null,
                Map.of("lastExecution", Map.of("output", "原始产出")), List.of());
    }

    private ReviewEvidenceView evidence(String tag) {
        return new ReviewEvidenceView("产出-" + tag, "清单-" + tag, "附件正文-" + tag,
                "验证信号-" + tag, "自审信号-" + tag);
    }

    @Test
    @DisplayName("REJECTED ⇒ 五段证据冻结进 snapshot，可原样读回")
    void freezesEvidenceForRejected() {
        Map<String, Object> snapshot = assembler.buildReviewSnapshot(
                subTask(), ReviewResult.REJECTED, evidence("ORIGINAL"));

        ReviewEvidenceView restored = assembler.frozenEvidenceView(snapshot);
        assertThat(restored).isNotNull();
        assertThat(restored.executionOutput()).isEqualTo("产出-ORIGINAL");
        assertThat(restored.attachmentList()).isEqualTo("清单-ORIGINAL");
        assertThat(restored.attachmentContent()).isEqualTo("附件正文-ORIGINAL");
        assertThat(restored.verificationSignal()).isEqualTo("验证信号-ORIGINAL");
        assertThat(restored.selfReviewSignal()).isEqualTo("自审信号-ORIGINAL");
    }

    @Test
    @DisplayName("APPROVED / INCONCLUSIVE ⇒ 不冻结（快照只存引用与摘要）")
    void doesNotFreezeForNonRejected() {
        for (ReviewResult result : List.of(ReviewResult.APPROVED, ReviewResult.INCONCLUSIVE)) {
            Map<String, Object> snapshot = assembler.buildReviewSnapshot(
                    subTask(), result, evidence(result.name()));
            assertThat(snapshot).doesNotContainKey("frozenEvidence");
            assertThat(assembler.frozenEvidenceView(snapshot)).isNull();
        }
    }

    @Test
    @DisplayName("冻结证据超限 ⇒ 不冻结，复判退回漂移跳过（不拿不完整对象度量）")
    void doesNotFreezeOversizeEvidence() {
        String huge = "x".repeat(ReviewEvidenceAssembler.FROZEN_EVIDENCE_MAX_CHARS + 1);
        ReviewEvidenceView oversized = new ReviewEvidenceView(huge, "", "", "", "");

        Map<String, Object> snapshot = assembler.buildReviewSnapshot(
                subTask(), ReviewResult.REJECTED, oversized);

        assertThat(snapshot).doesNotContainKey("frozenEvidence");
        assertThat(assembler.frozenEvidenceView(snapshot)).isNull();
    }

    @Test
    @DisplayName("旧快照 / 空快照 ⇒ frozenEvidenceView 返回 null（调用方走漂移对账）")
    void returnsNullForLegacySnapshot() {
        assertThat(assembler.frozenEvidenceView(null)).isNull();
        assertThat(assembler.frozenEvidenceView(Map.of())).isNull();
        assertThat(assembler.frozenEvidenceView(Map.of("schemaVersion", 1))).isNull();
    }
}