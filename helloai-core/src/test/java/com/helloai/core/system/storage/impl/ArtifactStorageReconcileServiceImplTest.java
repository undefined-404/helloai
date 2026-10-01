package com.helloai.core.system.storage.impl;

import com.helloai.common.config.ArtifactStorageProperties;
import com.helloai.core.system.port.ArtifactReference;
import com.helloai.core.system.port.ArtifactReferencePort;
import com.helloai.core.system.storage.ArtifactReconcileReport;
import com.helloai.core.system.storage.ArtifactStorage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * ArtifactStorageReconcileServiceImpl 单元测试：
 * 双向对账（悬空 / 孤儿 / 字节不符）与孤儿清理的三重保险
 * （开关默认关 + 时间窗 + 单轮上限 + 时间不可知跳过）。
 */
@DisplayName("存储对账巡检与孤儿清理")
class ArtifactStorageReconcileServiceImplTest {

    private static final String BUCKET = "helloai-artifacts";
    private static final String PROTOCOL = "minio://";

    private ArtifactStorage storage;
    private ArtifactStorageProperties properties;
    private ArtifactReferencePort artifactReferencePort;

    private ArtifactStorageReconcileServiceImpl service;

    @BeforeEach
    void setUp() {
        storage = mock(ArtifactStorage.class);
        properties = new ArtifactStorageProperties();
        properties.setType("minio");
        properties.setMinioBucket(BUCKET);
        artifactReferencePort = mock(ArtifactReferencePort.class);
        service = new ArtifactStorageReconcileServiceImpl(storage, properties, artifactReferencePort);
    }

    // ---------- helpers ----------

    private static ArtifactReference row(String objectKey, Long size) {
        return new ArtifactReference(PROTOCOL + BUCKET + "/" + objectKey, size);
    }

    private static ArtifactStorage.StoredObject obj(String objectKey, long size, OffsetDateTime lastModified) {
        return new ArtifactStorage.StoredObject(BUCKET, objectKey, size, lastModified);
    }

    private static OffsetDateTime daysAgo(long days) {
        return OffsetDateTime.now().minusDays(days);
    }

    // ---------- 对账方向 ----------

    @Test
    @DisplayName("两侧一致：无悬空/孤儿/字节不符，consistent=true")
    void shouldReportConsistentWhenBothSidesMatch() {
        when(artifactReferencePort.listAllIncludingDeleted()).thenReturn(List.of(row("a/b/x.md", 10L)));
        when(storage.listObjects(BUCKET, null)).thenReturn(List.of(obj("a/b/x.md", 10L, daysAgo(1))));

        ArtifactReconcileReport report = service.reconcile();

        assertThat(report.consistent()).isTrue();
        assertThat(report.attachmentRows()).isEqualTo(1);
        assertThat(report.objectCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("悬空：DB 有记录、桶中无对象 → dangling 命中（预览会 404）")
    void shouldDetectDanglingRecords() {
        when(artifactReferencePort.listAllIncludingDeleted())
                .thenReturn(List.of(row("a/b/x.md", 10L), row("a/b/gone.md", 20L)));
        when(storage.listObjects(BUCKET, null)).thenReturn(List.of(obj("a/b/x.md", 10L, daysAgo(1))));

        ArtifactReconcileReport report = service.reconcile();

        assertThat(report.dangling()).containsExactly("a/b/gone.md");
        assertThat(report.orphaned()).isEmpty();
        assertThat(report.consistent()).isFalse();
    }

    @Test
    @DisplayName("孤儿：桶中有对象、无任何附件行引用 → orphaned 命中")
    void shouldDetectOrphanObjects() {
        when(artifactReferencePort.listAllIncludingDeleted()).thenReturn(List.of(row("a/b/x.md", 10L)));
        when(storage.listObjects(BUCKET, null))
                .thenReturn(List.of(obj("a/b/x.md", 10L, daysAgo(1)), obj("a/b/orphan.md", 5L, daysAgo(1))));

        ArtifactReconcileReport report = service.reconcile();

        assertThat(report.orphaned()).containsExactly("a/b/orphan.md");
        assertThat(report.dangling()).isEmpty();
    }

    @Test
    @DisplayName("字节不符：双侧都在但大小不同 → sizeMismatch 命中")
    void shouldDetectSizeMismatch() {
        when(artifactReferencePort.listAllIncludingDeleted()).thenReturn(List.of(row("a/b/x.md", 10L)));
        when(storage.listObjects(BUCKET, null)).thenReturn(List.of(obj("a/b/x.md", 7L, daysAgo(1))));

        ArtifactReconcileReport report = service.reconcile();

        assertThat(report.sizeMismatch()).hasSize(1);
        assertThat(report.sizeMismatch().get(0)).contains("db=10B").contains("storage=7B");
    }

    @Test
    @DisplayName("别的桶 / 别的协议的记录不参与本轮对账（不误判成悬空）")
    void shouldIgnoreForeignBucketOrProtocol() {
        ArtifactReference foreignBucket = new ArtifactReference("minio://trae-executor/a/b/x.md", null);
        ArtifactReference foreignProtocol = new ArtifactReference("local://helloai-local/a/b/x.md", null);
        ArtifactReference blank = new ArtifactReference(null, null);

        when(artifactReferencePort.listAllIncludingDeleted())
                .thenReturn(List.of(foreignBucket, foreignProtocol, blank));
        when(storage.listObjects(BUCKET, null)).thenReturn(List.of());

        ArtifactReconcileReport report = service.reconcile();

        assertThat(report.dangling()).isEmpty();
        assertThat(report.orphaned()).isEmpty();
    }

    // ---------- 清理：三重保险 ----------

    @Test
    @DisplayName("清理默认关闭：孤儿只报告不删除，且不触碰删除接口")
    void cleanupDisabled_shouldOnlyReport() {
        when(artifactReferencePort.listAllIncludingDeleted()).thenReturn(List.of());
        when(storage.listObjects(BUCKET, null)).thenReturn(List.of(obj("orphan.md", 1L, daysAgo(10))));

        ArtifactReconcileReport report = service.reconcile();

        assertThat(report.orphaned()).containsExactly("orphan.md");
        assertThat(report.removed()).isEmpty();
        assertThat(report.cleanupEnabled()).isFalse();
        verify(storage, never()).removeObject(anyString(), anyString());
    }

    @Test
    @DisplayName("清理开启且对象已过时间窗 → 删除，removed 记录")
    void cleanupEnabled_shouldRemoveAgedOrphan() {
        properties.setOrphanCleanupEnabled(true);
        when(artifactReferencePort.listAllIncludingDeleted()).thenReturn(List.of());
        when(storage.listObjects(BUCKET, null)).thenReturn(List.of(obj("orphan.md", 1L, daysAgo(2))));

        ArtifactReconcileReport report = service.reconcile();

        assertThat(report.removed()).containsExactly("orphan.md");
        verify(storage).removeObject(BUCKET, "orphan.md");
    }

    @Test
    @DisplayName("时间窗保险：对象最后修改时间未超窗 → 跳过删除（规避'已上传未注册'中间态）")
    void cleanupEnabled_shouldSkipTooNewOrphan() {
        properties.setOrphanCleanupEnabled(true);
        properties.setOrphanMinAgeHours(24);
        when(artifactReferencePort.listAllIncludingDeleted()).thenReturn(List.of());
        when(storage.listObjects(BUCKET, null))
                .thenReturn(List.of(obj("brand-new.md", 1L, OffsetDateTime.now().minusMinutes(5))));

        ArtifactReconcileReport report = service.reconcile();

        assertThat(report.orphaned()).containsExactly("brand-new.md");
        assertThat(report.removed()).isEmpty();
        verify(storage, never()).removeObject(anyString(), anyString());
    }

    @Test
    @DisplayName("时间保险：lastModified 不可知 → 保守跳过删除")
    void cleanupEnabled_shouldSkipOrphanWithUnknownTimestamp() {
        properties.setOrphanCleanupEnabled(true);
        when(artifactReferencePort.listAllIncludingDeleted()).thenReturn(List.of());
        when(storage.listObjects(BUCKET, null)).thenReturn(List.of(obj("no-time.md", 1L, null)));

        ArtifactReconcileReport report = service.reconcile();

        assertThat(report.removed()).isEmpty();
        verify(storage, never()).removeObject(anyString(), anyString());
    }

    @Test
    @DisplayName("数量保险：单轮删除不超上限，其余留待下一轮")
    void cleanupEnabled_shouldRespectMaxDeletesPerRound() {
        properties.setOrphanCleanupEnabled(true);
        properties.setOrphanMaxDeletesPerRound(3);
        List<ArtifactStorage.StoredObject> objects = IntStream.range(0, 10)
                .mapToObj(i -> obj("orphan-" + i + ".md", 1L, daysAgo(5)))
                .toList();
        when(artifactReferencePort.listAllIncludingDeleted()).thenReturn(List.of());
        when(storage.listObjects(BUCKET, null)).thenReturn(objects);

        ArtifactReconcileReport report = service.reconcile();

        assertThat(report.orphaned()).hasSize(10);
        assertThat(report.removed()).hasSize(3);
    }

    @Test
    @DisplayName("单个删除失败不影响其余对象（异常被吞并记 error）")
    void cleanupEnabled_shouldTolerateSingleFailure() {
        properties.setOrphanCleanupEnabled(true);
        when(artifactReferencePort.listAllIncludingDeleted()).thenReturn(List.of());
        when(storage.listObjects(BUCKET, null))
                .thenReturn(List.of(obj("bad.md", 1L, daysAgo(5)), obj("good.md", 1L, daysAgo(5))));
        doThrow(new RuntimeException("boom")).when(storage).removeObject(BUCKET, "bad.md");

        ArtifactReconcileReport report = service.reconcile();

        assertThat(report.removed()).containsExactly("good.md");
    }

    // ---------- objectKeyOf ----------

    @Test
    @DisplayName("objectKeyOf：只接受当前协议+当前桶的地址，其余返回 null")
    void objectKeyOf_shouldMatchOnlyCurrentProtocolAndBucket() {
        String head = PROTOCOL + BUCKET + "/";
        assertThat(ArtifactStorageReconcileServiceImpl.objectKeyOf(head + "a/b.md", PROTOCOL, BUCKET))
                .isEqualTo("a/b.md");
        assertThat(ArtifactStorageReconcileServiceImpl.objectKeyOf("minio://other/a.md", PROTOCOL, BUCKET)).isNull();
        assertThat(ArtifactStorageReconcileServiceImpl.objectKeyOf("local://" + BUCKET + "/a.md", PROTOCOL, BUCKET)).isNull();
        assertThat(ArtifactStorageReconcileServiceImpl.objectKeyOf(head, PROTOCOL, BUCKET)).isNull();
        assertThat(ArtifactStorageReconcileServiceImpl.objectKeyOf(null, PROTOCOL, BUCKET)).isNull();
    }

    @Test
    @DisplayName("local 类型：对账协议切到 local://，桶取本地 bucket")
    void shouldReconcileLocalTypeWithLocalProtocol() {
        properties.setType("local");
        properties.setBucket("helloai-local");
        when(artifactReferencePort.listAllIncludingDeleted()).thenReturn(List.of(row("a.md", 1L)));
        when(storage.listObjects("helloai-local", null))
                .thenReturn(List.of(obj("a.md", 1L, daysAgo(1))));

        ArtifactReconcileReport report = service.reconcile();

        assertThat(report.protocol()).isEqualTo("local://");
        assertThat(report.bucket()).isEqualTo("helloai-local");
    }
}
