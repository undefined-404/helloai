package com.helloai.core.task.support;

import com.helloai.common.base.BizException;
import com.helloai.core.system.storage.ArtifactStorage;
import com.helloai.core.task.entity.Attachment;
import com.helloai.core.task.mapper.AttachmentMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.Collection;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link AttachmentObjectPurgeSupport} 单测（P3-3 / P2，2026-10-07）。
 *
 * <p>覆盖：无事务上下文即回收、同 objectKey 活跃引用护栏、本行 deleted=1 不阻塞、
 * removeObject 失败 fail-safe、外部地址跳过；并钉死护栏走<b>窄查询</b>
 * （{@code selectActiveObjectKeysIn}）而非全表载入。</p>
 */
class AttachmentObjectPurgeSupportTest {

    private AttachmentMapper attachmentMapper;
    private ArtifactStorage artifactStorage;
    private AttachmentObjectPurgeSupport support;

    @BeforeEach
    void setUp() {
        attachmentMapper = mock(AttachmentMapper.class);
        artifactStorage = mock(ArtifactStorage.class);
        support = new AttachmentObjectPurgeSupport(attachmentMapper, artifactStorage);
    }

    private Attachment att(Long id, String objectKey, String storageUrl) {
        Attachment a = new Attachment();
        a.setId(id);
        a.setBucketName("helloai-artifacts");
        a.setObjectKey(objectKey);
        a.setStorageUrl(storageUrl);
        return a;
    }

    @Test
    @DisplayName("无事务上下文时立即回收平台可读对象")
    void purgeWithoutTx_shouldRemoveObjectImmediately() {
        Attachment a = att(1L, "k/1.md", "minio://helloai-artifacts/k/1.md");
        when(artifactStorage.supports(anyString())).thenReturn(true);
        when(attachmentMapper.selectActiveObjectKeysIn(any())).thenReturn(List.of());

        support.purgeAfterCommit(List.of(a));

        verify(artifactStorage).removeObject("helloai-artifacts", "k/1.md");
        // 护栏改动：不得再回退到全表载入（P2/P3-3 性能收敛）
        verify(attachmentMapper, never()).selectAllIncludingDeleted();
    }

    @Test
    @DisplayName("护栏：同 objectKey 仍被另一条活跃（deleted=0）行引用 → 跳过回收")
    void purge_shouldSkipWhenReferencedByActiveRow() {
        Attachment target = att(1L, "shared/x.md", "minio://helloai-artifacts/shared/x.md");
        when(artifactStorage.supports(anyString())).thenReturn(true);
        // 窄查询语义：返回「仍被活跃行引用」的 key 集合，命中 shared/x.md
        when(attachmentMapper.selectActiveObjectKeysIn(any())).thenReturn(List.of("shared/x.md"));

        support.purgeAfterCommit(List.of(target));

        verify(artifactStorage, never()).removeObject(anyString(), anyString());
        verify(attachmentMapper, never()).selectAllIncludingDeleted();
    }

    @Test
    @DisplayName("护栏：仅本行（deleted=1）引用 → 不视为被活跃引用，正常回收")
    void purge_shouldNotBlockOnSelfDeletedRow() {
        Attachment target = att(1L, "k/1.md", "minio://helloai-artifacts/k/1.md");
        target.setDeleted(1);
        when(artifactStorage.supports(anyString())).thenReturn(true);
        when(attachmentMapper.selectActiveObjectKeysIn(any())).thenReturn(List.of());

        support.purgeAfterCommit(List.of(target));

        verify(artifactStorage).removeObject("helloai-artifacts", "k/1.md");
    }

    @Test
    @DisplayName("护栏走窄查询：仅以候选 objectKey 集合入参（有界），且不触全表")
    void purge_shouldQueryOnlyCandidateKeys() {
        Attachment a = att(1L, "k/1.md", "minio://helloai-artifacts/k/1.md");
        Attachment b = att(2L, "k/2.md", "minio://helloai-artifacts/k/2.md");
        when(artifactStorage.supports(anyString())).thenReturn(true);
        when(attachmentMapper.selectActiveObjectKeysIn(any())).thenReturn(List.of());

        support.purgeAfterCommit(List.of(a, b));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Collection<String>> captor = ArgumentCaptor.forClass(Collection.class);
        verify(attachmentMapper).selectActiveObjectKeysIn(captor.capture());
        // 入参即候选 key（去重后），证明查询有界、非全表
        assertThat(captor.getValue()).containsExactlyInAnyOrder("k/1.md", "k/2.md");
        verify(attachmentMapper, never()).selectAllIncludingDeleted();
    }

    @Test
    @DisplayName("fail-safe：removeObject 抛异常不外溢（不阻断删除主流程）")
    void purge_shouldSwallowRemoveFailure() {
        Attachment a = att(1L, "k/1.md", "minio://helloai-artifacts/k/1.md");
        when(artifactStorage.supports(anyString())).thenReturn(true);
        when(attachmentMapper.selectActiveObjectKeysIn(any())).thenReturn(List.of());
        doThrow(new BizException(500, "minio down")).when(artifactStorage)
                .removeObject(eq("helloai-artifacts"), eq("k/1.md"));

        assertThatCode(() -> support.purgeAfterCommit(List.of(a))).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("fail-safe：护栏查询抛错 ⇒ 不冒泡、主流程仍成功、保守跳过回收（不调 removeObject）")
    void purge_shouldSwallowGuardQueryFailureAndSkipConservatively() {
        Attachment a = att(1L, "k/1.md", "minio://helloai-artifacts/k/1.md");
        when(artifactStorage.supports(anyString())).thenReturn(true);
        when(attachmentMapper.selectActiveObjectKeysIn(any()))
                .thenThrow(new RuntimeException("db down"));

        // 不得冒泡（afterCommit 阶段抛异常会污染已提交事务的响应）
        assertThatCode(() -> support.purgeAfterCommit(List.of(a))).doesNotThrowAnyException();

        // 护栏不可信 ⇒ 保守跳过：绝不删对象（防误删共享对象）
        verify(artifactStorage, never()).removeObject(anyString(), anyString());
        verify(attachmentMapper, never()).selectAllIncludingDeleted();
    }

    @Test
    @DisplayName("外部 https 地址（平台不可读）不参与回收，且不触发任何引用查询")
    void purge_shouldSkipNonPlatformUrl() {
        Attachment a = att(1L, "k/1.md", "https://example.com/k/1.md");
        when(artifactStorage.supports(anyString())).thenReturn(false);

        support.purgeAfterCommit(List.of(a));

        verify(artifactStorage, never()).removeObject(anyString(), anyString());
        verify(attachmentMapper, never()).selectActiveObjectKeysIn(any());
        verify(attachmentMapper, never()).selectAllIncludingDeleted();
    }
}
