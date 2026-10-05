package com.helloai.core.task.adapter;

import com.helloai.common.constant.AttachmentStatus;
import com.helloai.core.agent.port.AttachmentRef;
import com.helloai.core.task.entity.Attachment;
import com.helloai.core.task.service.AttachmentService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/**
 * {@code AttachmentPortAdapter} 单测（2026-10-01 W7 新建）。
 *
 * <p>覆盖三件事：① {@code register} 把实体收敛为主键；② {@code listActive} 的实体 → 快照
 * 映射（含 {@code contentLoadable} 由提供方顺带判定的口径）；③ 空值边界
 * （{@code null} / 空 / 含 {@code null} 元素 → 空列表，绝不返回 {@code null}）。</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("AttachmentPortAdapter")
class AttachmentPortAdapterTest {

    @Mock
    private AttachmentService attachmentService;

    @InjectMocks
    private AttachmentPortAdapter adapter;

    private Attachment attachment(Long id, String fileName, AttachmentStatus status) {
        Attachment attachment = new Attachment();
        attachment.setId(id);
        attachment.setFileName(fileName);
        attachment.setFileType("md");
        attachment.setMimeType("text/markdown");
        attachment.setFileSize(12L);
        attachment.setStatus(status);
        return attachment;
    }

    @Test
    @DisplayName("register：委派 AttachmentService.register 并收敛为附件主键")
    void shouldDelegateRegisterAndReturnId() {
        Attachment saved = attachment(555L, "报告.md", AttachmentStatus.ACTIVE);
        when(attachmentService.register(7L, 100L, "报告.md", "text/markdown", 12L, "local://x"))
                .thenReturn(saved);

        Long id = adapter.register(7L, 100L, "报告.md", "text/markdown", 12L, "local://x");

        assertThat(id).isEqualTo(555L);
    }

    @Test
    @DisplayName("listActive：实体 → 快照（含 contentLoadable 由提供方判定后透传）")
    void shouldMapEntitiesToRefs() {
        Attachment loadable = attachment(21L, "主文件.md", AttachmentStatus.ACTIVE);
        Attachment notLoadable = attachment(22L, "外部.pdf", AttachmentStatus.ACTIVE);
        when(attachmentService.listActive(11L)).thenReturn(List.of(loadable, notLoadable));
        when(attachmentService.isContentLoadable(loadable)).thenReturn(true);
        when(attachmentService.isContentLoadable(notLoadable)).thenReturn(false);

        List<AttachmentRef> refs = adapter.listActive(11L);

        assertThat(refs).hasSize(2);
        assertThat(refs.get(0).id()).isEqualTo(21L);
        assertThat(refs.get(0).fileName()).isEqualTo("主文件.md");
        assertThat(refs.get(0).fileType()).isEqualTo("md");
        assertThat(refs.get(0).mimeType()).isEqualTo("text/markdown");
        assertThat(refs.get(0).fileSize()).isEqualTo(12L);
        assertThat(refs.get(0).status()).isEqualTo(AttachmentStatus.ACTIVE);
        assertThat(refs.get(0).contentLoadable()).isTrue();
        assertThat(refs.get(1).contentLoadable()).isFalse();
    }

    @Test
    @DisplayName("listActive：null / 空源返回空列表（绝不返回 null）")
    void shouldReturnEmptyListForBlankSource() {
        when(attachmentService.listActive(11L)).thenReturn(null);
        when(attachmentService.listActive(12L)).thenReturn(List.of());

        assertThat(adapter.listActive(11L)).isEmpty();
        assertThat(adapter.listActive(12L)).isEmpty();
    }

    @Test
    @DisplayName("listActive：源列表含 null 元素时跳过而不抛异常")
    void shouldSkipNullElements() {
        List<Attachment> source = new ArrayList<>();
        source.add(null);
        source.add(attachment(21L, "主文件.md", AttachmentStatus.ACTIVE));
        when(attachmentService.listActive(11L)).thenReturn(source);
        when(attachmentService.isContentLoadable(source.get(1))).thenReturn(true);

        List<AttachmentRef> refs = adapter.listActive(11L);

        assertThat(refs).hasSize(1);
        assertThat(refs.get(0).id()).isEqualTo(21L);
    }

    @Test
    @DisplayName("loadContent：委派 AttachmentService.loadContent 原样返回字节")
    void shouldDelegateLoadContent() {
        byte[] bytes = "物化产出".getBytes(StandardCharsets.UTF_8);
        when(attachmentService.loadContent(21L)).thenReturn(bytes);

        assertThat(adapter.loadContent(21L)).isEqualTo(bytes);
    }
}
