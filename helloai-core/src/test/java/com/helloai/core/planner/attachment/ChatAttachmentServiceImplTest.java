package com.helloai.core.planner.attachment;

import com.helloai.common.base.BizException;
import com.helloai.common.config.ChatAttachmentProperties;
import com.helloai.core.planner.entity.ChatAttachment;
import com.helloai.core.planner.mapper.ChatAttachmentMapper;
import com.helloai.core.planner.service.ChatAttachmentService.UploadFile;
import com.helloai.core.planner.service.impl.ChatAttachmentServiceImpl;
import com.helloai.core.system.storage.ArtifactStorage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 会话附件服务语义（方案 §3.3 / §3.7）。
 *
 * <p>锁两条容易退化的行为：删除必须<b>幂等</b>（外部重试不该拿 404）；
 * 上传必须<b>先校验后写入</b>（超限文件不能先落库再报错）。</p>
 */
@DisplayName("ChatAttachmentServiceImpl 上传校验与删除幂等")
class ChatAttachmentServiceImplTest {

    private final ChatAttachmentMapper mapper = mock(ChatAttachmentMapper.class);
    private final ArtifactStorage storage = mock(ArtifactStorage.class);
    private final ChatAttachmentParseWorker parseWorker = mock(ChatAttachmentParseWorker.class);
    private ChatAttachmentServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new ChatAttachmentServiceImpl(storage, new ChatAttachmentProperties(), parseWorker);
        // ServiceImpl 的 baseMapper 由 Spring 注入，单测里手工塞
        ReflectionTestUtils.setField(service, "baseMapper", mapper);
    }

    @Test
    @DisplayName("删除幂等：记录不存在/已删时不抛异常，也不发起删除")
    void deleteShouldBeIdempotentWhenAbsent() {
        when(mapper.selectById(anyLong())).thenReturn(null);

        service.delete(123L);

        verify(mapper, never()).deleteById(anyLong());
        verify(storage, never()).removeObject(anyString(), anyString());
    }

    @Test
    @DisplayName("删除命中：先软删，对象回收在提交后执行")
    void deleteShouldRemoveRowAndPurgeObject() {
        ChatAttachment existing = new ChatAttachment();
        existing.setId(1L);
        existing.setBucketName("helloai");
        existing.setObjectKey("chat-1/2026/10/0/0/a.md");
        when(mapper.selectById(1L)).thenReturn(existing);
        when(mapper.deleteById(1L)).thenReturn(1);

        service.delete(1L);

        verify(mapper).deleteById(1L);
        // 无事务上下文（单测直调）→ afterCommit 退化为立即执行，回收动作应发生
        verify(storage).removeObject("helloai", "chat-1/2026/10/0/0/a.md");
    }

    @Test
    @DisplayName("单文件超限：抛 400 且不写存储（先校验后写入）")
    void shouldRejectOversizedFileBeforeStoring() {
        ChatAttachmentProperties props = new ChatAttachmentProperties();
        props.setMaxFileSizeBytes(16);
        ChatAttachmentServiceImpl small = new ChatAttachmentServiceImpl(storage, props, parseWorker);
        ReflectionTestUtils.setField(small, "baseMapper", mapper);

        byte[] big = "超过十六字节的内容".getBytes(StandardCharsets.UTF_8);
        List<UploadFile> files = List.of(new UploadFile("a.md", "text/markdown", big));

        assertThatThrownBy(() -> small.uploadBatch(1L, null, files))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("上限");
        verify(storage, never()).store(anyString(), anyString(), any(byte[].class));
    }

    @Test
    @DisplayName("缺少归属（无 conversationId 且无 clientToken）：直接拒绝，不产生无主附件")
    void shouldRejectWhenNoOwner() {
        List<UploadFile> files = List.of(new UploadFile("a.md", "text/markdown", "x".getBytes(StandardCharsets.UTF_8)));

        assertThatThrownBy(() -> service.uploadBatch(null, "  ", files))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("conversationId");
    }
}
