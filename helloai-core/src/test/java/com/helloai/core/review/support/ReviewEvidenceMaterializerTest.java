package com.helloai.core.review.support;

import com.helloai.common.config.OpenSandboxProperties;
import com.helloai.common.config.ReviewProperties;
import com.helloai.common.constant.AttachmentStatus;
import com.helloai.core.agent.port.AttachmentPort;
import com.helloai.core.agent.port.AttachmentRef;
import com.helloai.core.agent.runtime.sandbox.SandboxExecutionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link ReviewEvidenceMaterializer} 单测（`RV-15`，2026-11-15）。
 *
 * <p>守的契约：</p>
 * <ol>
 *   <li><b>有界证据集</b>：数量 / 单文件 / 总量三闸任一触顶即停，剩余转 {@code deferred}
 *       （deferred 的兜底是模型 stage 补拉——正是 RV-14 实测不可靠的那条路，故护栏默认值
 *       {@code maxFiles=40} 必须覆盖 35 附件批，不让正常批回放先触顶）；</li>
 *   <li><b>F1 修正口径</b>：沙箱未启用 / 复用关闭 ⇒ {@code enabled=false} + 原因，
 *       不假装能写（只在可持久容器存在时才承诺物化）；</li>
 *   <li><b>fail-open</b>：单条读取 / 写入失败只记该条 {@code FAILED} 继续下一条，
 *       整体不抛异常 —— 物化是旁路增强，不得阻断核验主链路。</li>
 * </ol>
 */
@DisplayName("ReviewEvidenceMaterializer 证据物化（RV-15）")
class ReviewEvidenceMaterializerTest {

    private static final Long TASK_ID = 100L;
    private static final Long SUB_TASK_ID = 200L;

    private AttachmentPort attachmentPort;
    private SandboxExecutionService sandboxExecutionService;
    private OpenSandboxProperties sandboxProperties;
    private ReviewProperties reviewProperties;
    private ReviewEvidenceMaterializer materializer;

    @BeforeEach
    void setUp() {
        attachmentPort = mock(AttachmentPort.class);
        sandboxExecutionService = mock(SandboxExecutionService.class);
        sandboxProperties = mock(OpenSandboxProperties.class);
        reviewProperties = new ReviewProperties();
        materializer = new ReviewEvidenceMaterializer(attachmentPort, sandboxExecutionService,
                sandboxProperties, reviewProperties);
        when(sandboxProperties.isEnabled()).thenReturn(true);
        when(sandboxProperties.isReuseEnabled()).thenReturn(true);
        when(sandboxProperties.getScope()).thenReturn("task");
        when(sandboxExecutionService.writeReviewerArtifact(anyLong(), anyLong(), any(), any()))
                .thenReturn(true);
    }

    private static AttachmentRef ref(long id, String name, long size, boolean loadable) {
        return new AttachmentRef(id, name, "csv", "text/csv", size, AttachmentStatus.ACTIVE, loadable);
    }

    @Test
    @DisplayName("沙箱未启用 ⇒ enabled=false（与贡献者摘除工具同源，不承诺物化）")
    void shouldDisableWhenSandboxOff() {
        when(sandboxProperties.isEnabled()).thenReturn(false);

        ReviewEvidenceMaterializer.MaterializationResult result =
                materializer.materialize(TASK_ID, SUB_TASK_ID);

        assertThat(result.enabled()).isFalse();
        assertThat(result.disabledReason()).contains("OpenSandbox 未启用");
        verify(sandboxExecutionService, never()).writeReviewerArtifact(anyLong(), anyLong(), any(), any());
    }

    @Test
    @DisplayName("F1：复用关闭（reuse-enabled=false）⇒ enabled=false，不假装能写")
    void shouldDisableWhenReuseOff() {
        when(sandboxProperties.isReuseEnabled()).thenReturn(false);

        ReviewEvidenceMaterializer.MaterializationResult result =
                materializer.materialize(TASK_ID, SUB_TASK_ID);

        assertThat(result.enabled()).isFalse();
        assertThat(result.disabledReason()).contains("reuse-enabled=false");
        verify(sandboxExecutionService, never()).writeReviewerArtifact(anyLong(), anyLong(), any(), any());
    }

    @Test
    @DisplayName("物化开关关闭 ⇒ enabled=false（退回 RV-14 手动 stage 通道）")
    void shouldDisableWhenConfigOff() {
        reviewProperties.getEvidenceMaterialization().setEnabled(false);

        ReviewEvidenceMaterializer.MaterializationResult result =
                materializer.materialize(TASK_ID, SUB_TASK_ID);

        assertThat(result.enabled()).isFalse();
    }

    @Test
    @DisplayName("无有效附件 ⇒ enabled=true 且清单为空（零物化，不写文件）")
    void shouldHandleEmptyAttachments() {
        when(attachmentPort.listActive(SUB_TASK_ID)).thenReturn(List.of());

        ReviewEvidenceMaterializer.MaterializationResult result =
                materializer.materialize(TASK_ID, SUB_TASK_ID);

        assertThat(result.enabled()).isTrue();
        assertThat(result.attachments()).isEmpty();
        assertThat(result.materializedCount()).isZero();
    }

    @Test
    @DisplayName("正常物化：路径=/tmp/staged/<id>-<消毒名>，sha256 可对账，按台账序落盘")
    void shouldMaterializeAll() {
        when(attachmentPort.listActive(SUB_TASK_ID)).thenReturn(List.of(
                ref(300L, "check_data.py", 120, true),
                ref(301L, "../data.csv", 80, true)));
        when(attachmentPort.loadContent(300L)).thenReturn("print(1)".getBytes());
        when(attachmentPort.loadContent(301L)).thenReturn("a,b\n1,2".getBytes());

        ReviewEvidenceMaterializer.MaterializationResult result =
                materializer.materialize(TASK_ID, SUB_TASK_ID);

        assertThat(result.enabled()).isTrue();
        assertThat(result.materializedCount()).isEqualTo(2);
        assertThat(result.totalBytes()).isEqualTo(8 + 7);
        assertThat(result.attachments()).extracting(a -> a.path()).containsExactly(
                "/tmp/staged/300-check_data.py",
                "/tmp/staged/301-data.csv"); // `..` 消毒为 `_`、去路径分隔
        assertThat(result.attachments()).allMatch(a -> a.status() == ReviewEvidenceMaterializer.Status.MATERIALIZED);
        assertThat(result.attachments().get(0).sha256()).hasSize(64);
        // F2：显式 reviewer 身份写入（taskId + subTaskId 都到位）
        verify(sandboxExecutionService).writeReviewerArtifact(eq(TASK_ID), eq(SUB_TASK_ID),
                eq("/tmp/staged/300-check_data.py"), any());
    }

    @Test
    @DisplayName("数量触顶：达到 maxFiles 后剩余全部 deferred")
    void shouldDeferWhenFileCountExceeds() {
        reviewProperties.getEvidenceMaterialization().setMaxFiles(1);
        when(attachmentPort.listActive(SUB_TASK_ID)).thenReturn(List.of(
                ref(300L, "a.py", 10, true),
                ref(301L, "b.py", 10, true)));
        when(attachmentPort.loadContent(300L)).thenReturn("x".getBytes());

        ReviewEvidenceMaterializer.MaterializationResult result =
                materializer.materialize(TASK_ID, SUB_TASK_ID);

        assertThat(result.materializedCount()).isEqualTo(1);
        assertThat(result.deferredIds()).containsExactly(301L);
        verify(attachmentPort, never()).loadContent(301L);
    }

    @Test
    @DisplayName("单文件超限：按台账快照大小判 too_large，不产生实际读取")
    void shouldSkipTooLargeBySnapshot() {
        reviewProperties.getEvidenceMaterialization().setMaxBytesPerFile(100);
        when(attachmentPort.listActive(SUB_TASK_ID)).thenReturn(List.of(
                ref(300L, "big.csv", 10_000, true)));

        ReviewEvidenceMaterializer.MaterializationResult result =
                materializer.materialize(TASK_ID, SUB_TASK_ID);

        assertThat(result.attachments()).singleElement().satisfies(a -> {
            assertThat(a.status()).isEqualTo(ReviewEvidenceMaterializer.Status.TOO_LARGE);
            assertThat(a.reason()).contains("超过单文件上限");
        });
        verify(attachmentPort, never()).loadContent(300L);
    }

    @Test
    @DisplayName("总量触顶：累计字节越过 maxTotalBytes 后剩余全部 deferred")
    void shouldDeferWhenTotalBytesExceeds() {
        reviewProperties.getEvidenceMaterialization().setMaxTotalBytes(5);
        when(attachmentPort.listActive(SUB_TASK_ID)).thenReturn(List.of(
                ref(300L, "a.bin", 4, true),
                ref(301L, "b.bin", 4, true)));
        when(attachmentPort.loadContent(300L)).thenReturn(new byte[8]); // 实际字节 8 > 快照 4？快照为准

        ReviewEvidenceMaterializer.MaterializationResult result =
                materializer.materialize(TASK_ID, SUB_TASK_ID);

        // 快照 4 + 4 = 8 > 5 ⇒ 第二条触顶 deferred
        assertThat(result.materializedCount()).isEqualTo(1);
        assertThat(result.deferredIds()).containsExactly(301L);
    }

    @Test
    @DisplayName("contentLoadable=false ⇒ not_loadable（无物化通道）")
    void shouldNotLoadWhenNotLoadable() {
        when(attachmentPort.listActive(SUB_TASK_ID)).thenReturn(List.of(
                ref(300L, "ext.bin", 10, false)));

        ReviewEvidenceMaterializer.MaterializationResult result =
                materializer.materialize(TASK_ID, SUB_TASK_ID);

        assertThat(result.attachments()).singleElement().satisfies(a ->
                assertThat(a.status()).isEqualTo(ReviewEvidenceMaterializer.Status.NOT_LOADABLE));
        verify(attachmentPort, never()).loadContent(300L);
    }

    @Test
    @DisplayName("fail-open：单条读取失败记 FAILED，继续物化后续附件")
    void shouldContinueAfterSingleLoadFailure() {
        when(attachmentPort.listActive(SUB_TASK_ID)).thenReturn(List.of(
                ref(300L, "bad.csv", 10, true),
                ref(301L, "ok.csv", 10, true)));
        when(attachmentPort.loadContent(300L)).thenThrow(new IllegalStateException("minio down"));
        when(attachmentPort.loadContent(301L)).thenReturn("ok".getBytes());

        ReviewEvidenceMaterializer.MaterializationResult result =
                materializer.materialize(TASK_ID, SUB_TASK_ID);

        assertThat(result.attachments()).hasSize(2);
        assertThat(result.attachments().get(0).status()).isEqualTo(ReviewEvidenceMaterializer.Status.FAILED);
        assertThat(result.attachments().get(0).reason()).contains("minio down");
        assertThat(result.attachments().get(1).status()).isEqualTo(ReviewEvidenceMaterializer.Status.MATERIALIZED);
        assertThat(result.materializedCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("写沙箱失败（句柄不可用）⇒ 该条 FAILED，不抛异常")
    void shouldMarkFailedWhenWriteFails() {
        when(attachmentPort.listActive(SUB_TASK_ID)).thenReturn(List.of(
                ref(300L, "a.csv", 10, true)));
        when(attachmentPort.loadContent(300L)).thenReturn("abc".getBytes());
        when(sandboxExecutionService.writeReviewerArtifact(anyLong(), anyLong(), any(), any()))
                .thenReturn(false);

        ReviewEvidenceMaterializer.MaterializationResult result =
                materializer.materialize(TASK_ID, SUB_TASK_ID);

        assertThat(result.attachments()).singleElement().satisfies(a ->
                assertThat(a.status()).isEqualTo(ReviewEvidenceMaterializer.Status.FAILED));
        assertThat(result.materializedCount()).isZero();
    }

    @Test
    @DisplayName("renderPromptNote：已就位给路径+sha256，deferred 给补拉指引")
    void shouldRenderPromptNote() {
        ReviewEvidenceMaterializer.MaterializationResult result =
                new ReviewEvidenceMaterializer.MaterializationResult(true, null, List.of(
                        new ReviewEvidenceMaterializer.MaterializedAttachment(
                                300L, "check.py", "/tmp/staged/300-check.py", 7, "abcdef",
                                ReviewEvidenceMaterializer.Status.MATERIALIZED, null)),
                        List.of(301L), 1, 7);

        String note = result.renderPromptNote();

        assertThat(note)
                .contains("证据物化清单")
                .contains("/tmp/staged/300-check.py")
                .contains("sha256=abcdef")
                .contains("internal_stage_attachment_to_sandbox")
                .contains("[301]");
    }

    @Test
    @DisplayName("renderPromptNote：不可用时给退路提示（stage 补拉 / 读工具兜底）")
    void shouldRenderDisabledNote() {
        String note = ReviewEvidenceMaterializer.MaterializationResult
                .disabled("OpenSandbox 未启用").renderPromptNote();

        assertThat(note)
                .contains("自动物化不可用")
                .contains("internal_stage_attachment_to_sandbox")
                .contains("internal_read_attachment_range");
    }
}