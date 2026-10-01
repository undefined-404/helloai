package com.helloai.core.task.service.impl;

import com.helloai.core.system.port.ArtifactReference;
import com.helloai.core.task.entity.Attachment;
import com.helloai.core.task.service.AttachmentService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/**
 * {@link ArtifactReferencePortAdapter} 单元测试（§6.146 端口反转配套）：
 * 验证 attachment 实体 → {@link ArtifactReference} 值对象的映射
 * （storageUrl / fileSize 原样搬运、空行保留、不泄漏实体）。
 */
@ExtendWith(MockitoExtension.class)
class ArtifactReferencePortAdapterTest {

    @Mock
    private AttachmentService attachmentService;

    @InjectMocks
    private ArtifactReferencePortAdapter adapter;

    @Test
    @DisplayName("逐行映射：storageUrl/fileSize 原样搬运，顺序保持")
    void shouldMapRowsInOrder() {
        when(attachmentService.listAllIncludingDeleted())
                .thenReturn(List.of(row("minio://b/k1.md", 10L), row("local://b/k2.md", null)));

        List<ArtifactReference> refs = adapter.listAllIncludingDeleted();

        assertThat(refs).containsExactly(
                new ArtifactReference("minio://b/k1.md", 10L),
                new ArtifactReference("local://b/k2.md", null));
    }

    @Test
    @DisplayName("空行保留：地址为 null 的行不丢弃（对账侧需感知'有行无地址'）")
    void shouldKeepBlankRows() {
        when(attachmentService.listAllIncludingDeleted()).thenReturn(List.of(row(null, null)));

        List<ArtifactReference> refs = adapter.listAllIncludingDeleted();

        assertThat(refs).hasSize(1);
        assertThat(refs.get(0).storageUrl()).isNull();
        assertThat(refs.get(0).fileSize()).isNull();
    }

    @Test
    @DisplayName("无行返回空列表（绝不返回 null）")
    void shouldReturnEmptyWhenNoRows() {
        when(attachmentService.listAllIncludingDeleted()).thenReturn(List.of());

        assertThat(adapter.listAllIncludingDeleted()).isEmpty();
    }

    private static Attachment row(String storageUrl, Long fileSize) {
        Attachment a = new Attachment();
        a.setStorageUrl(storageUrl);
        a.setFileSize(fileSize);
        return a;
    }
}
