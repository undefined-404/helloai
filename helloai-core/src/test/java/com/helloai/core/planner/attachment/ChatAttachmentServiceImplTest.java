package com.helloai.core.planner.attachment;

import com.helloai.common.base.BizException;
import com.helloai.common.config.ChatAttachmentProperties;
import com.helloai.core.planner.entity.ChatAttachment;
import com.helloai.core.planner.mapper.ChatAttachmentMapper;
import com.helloai.core.planner.service.ChatAttachmentService.UploadFile;
import com.helloai.core.planner.service.impl.ChatAttachmentServiceImpl;
import com.helloai.core.system.port.ArtifactReferencePort;
import com.helloai.core.system.storage.ArtifactStorage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;

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
    private final ArtifactReferencePort artifactReferencePort = mock(ArtifactReferencePort.class);
    private ChatAttachmentServiceImpl service;

    @BeforeEach
    void setUp() {
        // G-047 默认：子任务侧无活跃引用（会回收）。各用例按需覆盖。
        when(artifactReferencePort.listActiveObjectKeysIn(any())).thenReturn(Set.of());
        // G-041：回收前先问「该地址是不是本平台管的」。真实数据里 storageUrl 恒非空
        // （uploadBatch / saveArtifact 都写 store() 的返回值），故默认放行；
        // 「外部 https 地址不越权删」由专门用例覆盖为 false。
        when(storage.supports(any())).thenReturn(true);
        service = new ChatAttachmentServiceImpl(storage, new ChatAttachmentProperties(), parseWorker, artifactReferencePort);
        // ServiceImpl 的 baseMapper 由 Spring 注入，单测里手工塞
        ReflectionTestUtils.setField(service, "baseMapper", mapper);
    }

    @Test
    @DisplayName("删除幂等：记录不存在/已删时不抛异常，也不发起删除")
    void deleteShouldBeIdempotentWhenAbsent() {
        when(mapper.selectById(anyLong())).thenReturn(null);

        service.delete(123L);

        verify(mapper, never()).physicalDeleteById(anyLong());
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
        when(mapper.physicalDeleteById(1L)).thenReturn(1);

        service.delete(1L);

        verify(mapper).physicalDeleteById(1L);
        // 无事务上下文（单测直调）→ afterCommit 退化为立即执行，回收动作应发生
        verify(storage).removeObject("helloai", "chat-1/2026/10/0/0/a.md");
    }

    // ------------------------------------------------------------------
    // G-047 跨表护栏：同 objectKey 双行方案的地基
    // ------------------------------------------------------------------

    @Test
    @DisplayName("G-047 护栏：子任务产物仍活跃引用同一 objectKey → 跳过回收（会话删图不得打断子任务）")
    void deleteShouldKeepObjectWhenSubTaskStillReferencesIt() {
        ChatAttachment existing = new ChatAttachment();
        existing.setId(2L);
        existing.setBucketName("helloai");
        existing.setObjectKey("chat-1/2026/11/0/0/spec.png");
        when(mapper.selectById(2L)).thenReturn(existing);
        when(mapper.physicalDeleteById(2L)).thenReturn(1);
        // 子任务侧仍有活跃行指向同一对象（双行）
        when(artifactReferencePort.listActiveObjectKeysIn(any())).thenReturn(Set.of("chat-1/2026/11/0/0/spec.png"));

        service.delete(2L);

        // DB 行照删（会话侧的归属已解除），但字节必须留下
        verify(mapper).physicalDeleteById(2L);
        verify(storage, never()).removeObject(anyString(), anyString());
    }

    @Test
    @DisplayName("G-047 护栏：子任务侧无引用 → 正常回收对象（护栏不能把常态也拦掉）")
    void deleteShouldPurgeObjectWhenNoCrossReference() {
        ChatAttachment existing = new ChatAttachment();
        existing.setId(3L);
        existing.setBucketName("helloai");
        existing.setObjectKey("chat-1/2026/11/0/0/only.png");
        when(mapper.selectById(3L)).thenReturn(existing);
        when(mapper.physicalDeleteById(3L)).thenReturn(1);
        when(artifactReferencePort.listActiveObjectKeysIn(any())).thenReturn(Set.of());

        service.delete(3L);

        verify(storage).removeObject("helloai", "chat-1/2026/11/0/0/only.png");
    }

    @Test
    @DisplayName("G-047 fail-safe：护栏查询抛错 → 保守跳过回收，且不外溢（afterCommit 阶段不得抛）")
    void deleteShouldSkipPurgeWhenGuardFails() {
        ChatAttachment existing = new ChatAttachment();
        existing.setId(4L);
        existing.setBucketName("helloai");
        existing.setObjectKey("chat-1/2026/11/0/0/x.png");
        when(mapper.selectById(4L)).thenReturn(existing);
        when(mapper.physicalDeleteById(4L)).thenReturn(1);
        when(artifactReferencePort.listActiveObjectKeysIn(any()))
                .thenThrow(new RuntimeException("task db down"));

        // 不得抛：删除主流程（DB 行）已提交
        service.delete(4L);

        verify(mapper).physicalDeleteById(4L);
        verify(storage, never()).removeObject(anyString(), anyString());
    }

    @Test
    @DisplayName("G-047 护栏查询入参：只带本对象 objectKey（窄查询，不扫全表）")
    void deleteShouldQueryGuardWithSingleKey() {
        ChatAttachment existing = new ChatAttachment();
        existing.setId(5L);
        existing.setBucketName("helloai");
        existing.setObjectKey("chat-1/2026/11/0/0/y.png");
        when(mapper.selectById(5L)).thenReturn(existing);
        when(mapper.physicalDeleteById(5L)).thenReturn(1);

        service.delete(5L);

        org.mockito.ArgumentCaptor<java.util.Collection<String>> captor =
                org.mockito.ArgumentCaptor.forClass(java.util.Collection.class);
        verify(artifactReferencePort).listActiveObjectKeysIn(captor.capture());
        org.assertj.core.api.Assertions.assertThat(captor.getValue())
                .containsExactly("chat-1/2026/11/0/0/y.png");
    }

    @Test
    @DisplayName("单文件超限：抛 400 且不写存储（先校验后写入）")
    void shouldRejectOversizedFileBeforeStoring() {
        ChatAttachmentProperties props = new ChatAttachmentProperties();
        props.setMaxFileSizeBytes(16);
        ChatAttachmentServiceImpl small = new ChatAttachmentServiceImpl(storage, props, parseWorker, artifactReferencePort);
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

    @Test
    @DisplayName("下载：不可内联预览类型（docx）也能下载，不触发 413 MIME 白名单")
    void downloadShouldBypassPreviewableMimeGate() {
        ChatAttachment attachment = new ChatAttachment();
        attachment.setId(42L);
        attachment.setFileName("报告.docx");
        attachment.setMimeType("application/vnd.openxmlformats-officedocument.wordprocessingml.document");
        attachment.setFileSize(1024L);
        attachment.setStorageUrl("minio://helloai-artifacts/chat-1/2026/10/0/0/abc.docx");
        when(mapper.selectById(42L)).thenReturn(attachment);
        byte[] body = "PK fake docx".getBytes(StandardCharsets.UTF_8);
        when(storage.load(anyString())).thenReturn(body);

        var payload = service.download(42L);

        // 不抛 413；类型不重新探测（保留 DB 登记的 MIME 作参考），字节完整透出
        org.assertj.core.api.Assertions.assertThat(payload.content()).isEqualTo(body);
        org.assertj.core.api.Assertions.assertThat(payload.fileName()).isEqualTo("报告.docx");
    }

    @Test
    @DisplayName("下载：超过内联预览 5MB 上限的大产物同样能下载")
    void downloadShouldAllowLargeArtifact() {
        ChatAttachment attachment = new ChatAttachment();
        attachment.setId(43L);
        attachment.setFileName("big-arch.html");
        attachment.setMimeType("text/html");
        attachment.setFileSize(6L * 1024 * 1024); // 6MB > 5MB 预览上限
        attachment.setStorageUrl("minio://helloai-artifacts/chat-1/2026/10/0/0/big.html");
        when(mapper.selectById(43L)).thenReturn(attachment);
        byte[] body = "<html>big</html>".getBytes(StandardCharsets.UTF_8);
        when(storage.load(anyString())).thenReturn(body);

        var payload = service.download(43L);

        org.assertj.core.api.Assertions.assertThat(payload.content()).isEqualTo(body);
    }
    // ------------------------------------------------------------------
    // G-041 硬删四件套：会话删除不留下任何存活行 / 残留行
    // ------------------------------------------------------------------

    @Test
    @DisplayName("G-041 单条删除必须是物理删（不能走 @TableLogic 软删，否则对账永久报 dangling）")
    void deleteMustBePhysicalNotLogical() {
        ChatAttachment existing = new ChatAttachment();
        existing.setId(11L);
        existing.setBucketName("helloai");
        existing.setObjectKey("chat-1/2026/11/0/0/a.png");
        when(mapper.selectById(11L)).thenReturn(existing);

        service.delete(11L);

        verify(mapper).physicalDeleteById(11L);
        // 内置软删一次都不许调：它与物理删并存 = 同一事实两个写点（判据 4）
        verify(mapper, never()).deleteById(anyLong());
    }

    @Test
    @DisplayName("G-041 整片删除：先捞引用（含残留行）→ 再物理删行 → 提交后逐个回收")
    void deleteByConversationShouldPurgeRowsThenObjects() {
        ChatAttachment a1 = purgeRef(21L, "chat-7/2026/11/0/0/a.png");
        ChatAttachment a2 = purgeRef(22L, "chat-7/2026/11/0/0/b.png");
        when(mapper.selectPurgeRefsByConversationId(7L)).thenReturn(List.of(a1, a2));
        when(mapper.physicalDeleteByConversationId(7L)).thenReturn(2);
        when(artifactReferencePort.listActiveObjectKeysIn(any())).thenReturn(Set.of());

        int removed = service.deleteByConversation(7L);

        org.assertj.core.api.Assertions.assertThat(removed).isEqualTo(2);
        verify(mapper).selectPurgeRefsByConversationId(7L);
        verify(mapper).physicalDeleteByConversationId(7L);
        verify(storage).removeObject("helloai", "chat-7/2026/11/0/0/a.png");
        verify(storage).removeObject("helloai", "chat-7/2026/11/0/0/b.png");
    }

    @Test
    @DisplayName("G-041 整片删除顺序：必须先捞引用再删行（反了就捞不到任何引用，对象全部滞留成孤儿）")
    void deleteByConversationMustSelectRefsBeforeDeletingRows() {
        ChatAttachment a1 = purgeRef(23L, "chat-7/2026/11/0/0/c.png");
        when(mapper.selectPurgeRefsByConversationId(7L)).thenReturn(List.of(a1));
        when(mapper.physicalDeleteByConversationId(7L)).thenReturn(1);

        service.deleteByConversation(7L);

        // ★顺序断言不能省：mock 的桩对调用顺序不敏感，只 verify 各自被调过，
        //   把两行调换位置测试照样全绿——而真实库里换位就意味着一个对象都回收不了。
        org.mockito.InOrder inOrder = org.mockito.Mockito.inOrder(mapper);
        inOrder.verify(mapper).selectPurgeRefsByConversationId(7L);
        inOrder.verify(mapper).physicalDeleteByConversationId(7L);
    }

    @Test
    @DisplayName("G-041 整片删除：conversationId 为空直接返回 0（不得静默降级为全表）")
    void deleteByConversationShouldRefuseNullId() {
        org.assertj.core.api.Assertions.assertThat(service.deleteByConversation(null)).isZero();

        verify(mapper, never()).physicalDeleteByConversationId(any());
        verify(mapper, never()).selectPurgeRefsByConversationId(any());
    }

    @Test
    @DisplayName("G-041 整片删除幂等：该会话无附件行时返回 0，不抛、不查护栏")
    void deleteByConversationShouldBeIdempotentWhenNoRows() {
        when(mapper.selectPurgeRefsByConversationId(8L)).thenReturn(List.of());
        when(mapper.physicalDeleteByConversationId(8L)).thenReturn(0);

        org.assertj.core.api.Assertions.assertThat(service.deleteByConversation(8L)).isZero();

        verify(artifactReferencePort, never()).listActiveObjectKeysIn(any());
    }

    @Test
    @DisplayName("G-041 整片删除护栏：子任务仍需引用其中一张 → 只跳过那一张，其余照删")
    void deleteByConversationShouldSkipOnlyCrossReferencedObject() {
        ChatAttachment a1 = purgeRef(31L, "chat-9/2026/11/0/0/keep.png");
        ChatAttachment a2 = purgeRef(32L, "chat-9/2026/11/0/0/drop.png");
        when(mapper.selectPurgeRefsByConversationId(9L)).thenReturn(List.of(a1, a2));
        when(mapper.physicalDeleteByConversationId(9L)).thenReturn(2);
        when(artifactReferencePort.listActiveObjectKeysIn(any()))
                .thenReturn(Set.of("chat-9/2026/11/0/0/keep.png"));

        service.deleteByConversation(9L);

        // 行照删（会话侧归属已解除），但被引用的字节必须留下
        verify(mapper).physicalDeleteByConversationId(9L);
        verify(storage, never()).removeObject("helloai", "chat-9/2026/11/0/0/keep.png");
        verify(storage).removeObject("helloai", "chat-9/2026/11/0/0/drop.png");
    }

    @Test
    @DisplayName("G-041 整片删除护栏入参：一次批量查（不是逐条查 N 次）")
    void deleteByConversationShouldQueryGuardOnceWithAllKeys() {
        ChatAttachment a1 = purgeRef(41L, "k/a.png");
        ChatAttachment a2 = purgeRef(42L, "k/b.png");
        ChatAttachment a3 = purgeRef(43L, "k/c.png");
        when(mapper.selectPurgeRefsByConversationId(10L)).thenReturn(List.of(a1, a2, a3));
        when(mapper.physicalDeleteByConversationId(10L)).thenReturn(3);
        when(artifactReferencePort.listActiveObjectKeysIn(any())).thenReturn(Set.of());

        service.deleteByConversation(10L);

        @SuppressWarnings("unchecked")
        org.mockito.ArgumentCaptor<java.util.Collection<String>> captor =
                org.mockito.ArgumentCaptor.forClass(java.util.Collection.class);
        verify(artifactReferencePort, org.mockito.Mockito.times(1)).listActiveObjectKeysIn(captor.capture());
        org.assertj.core.api.Assertions.assertThat(captor.getValue())
                .containsExactlyInAnyOrder("k/a.png", "k/b.png", "k/c.png");
    }

    @Test
    @DisplayName("G-041 整片删除 fail-safe：护栏查询抛错 → 一个对象都不删，且不外溢")
    void deleteByConversationShouldSkipAllPurgeWhenGuardFails() {
        ChatAttachment a1 = purgeRef(51L, "k/x.png");
        when(mapper.selectPurgeRefsByConversationId(11L)).thenReturn(List.of(a1));
        when(mapper.physicalDeleteByConversationId(11L)).thenReturn(1);
        when(artifactReferencePort.listActiveObjectKeysIn(any()))
                .thenThrow(new RuntimeException("task db down"));

        // 不得抛：afterCommit 阶段抛异常会污染已提交事务的响应
        service.deleteByConversation(11L);

        verify(mapper).physicalDeleteByConversationId(11L);
        verify(storage, never()).removeObject(anyString(), anyString());
    }

    @Test
    @DisplayName("G-041 整片删除：外部 https 地址与非平台可读行不参与回收（不越权删别人的对象）")
    void deleteByConversationShouldSkipExternalStorageUrl() {
        ChatAttachment internal = purgeRef(61L, "k/ok.png");
        ChatAttachment external = new ChatAttachment();
        external.setId(62L);
        external.setBucketName("helloai");
        external.setObjectKey("k/ext.png");
        external.setStorageUrl("https://cdn.example.com/ext.png");
        when(mapper.selectPurgeRefsByConversationId(12L)).thenReturn(List.of(internal, external));
        when(mapper.physicalDeleteByConversationId(12L)).thenReturn(2);
        when(storage.supports("https://cdn.example.com/ext.png")).thenReturn(false);
        when(artifactReferencePort.listActiveObjectKeysIn(any())).thenReturn(Set.of());

        service.deleteByConversation(12L);

        verify(storage).removeObject("helloai", "k/ok.png");
        verify(storage, never()).removeObject(anyString(), org.mockito.ArgumentMatchers.eq("k/ext.png"));
    }

    /** 构造一行「回收所需字段齐全」的附件（模拟 selectPurgeRefsByConversationId 的投影结果）。 */
    private static ChatAttachment purgeRef(Long id, String objectKey) {
        ChatAttachment attachment = new ChatAttachment();
        attachment.setId(id);
        attachment.setBucketName("helloai");
        attachment.setObjectKey(objectKey);
        attachment.setStorageUrl("minio://helloai/" + objectKey);
        return attachment;
    }
}
