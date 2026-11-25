package com.helloai.core.agent.support;

import com.helloai.core.agent.port.AttachmentPort;
import com.helloai.core.agent.port.ChatAttachmentMediaPort;
import com.helloai.core.agent.port.MediaRef;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.content.Media;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 媒体装载判据（`VU-9` / 不变量 I-3）。
 *
 * <p><b>为什么这些判据必须锁住</b>：对话面与业务面（`VU-2`）共用本装载器，
 * 「哪些算图片」「多大算超限」「读失败了怎么办」一旦在某一面被改坏，
 * 另一面会静默失去图片能力，而症状只是「模型好像没看到图」——极难定位。</p>
 */
@DisplayName("AttachmentMediaAssembler 媒体装载")
class AttachmentMediaAssemblerTest {

    private final AttachmentPort attachmentPort = mock(AttachmentPort.class);
    private final ChatAttachmentMediaPort chatAttachmentMediaPort = mock(ChatAttachmentMediaPort.class);
    private final AttachmentMediaAssembler assembler =
            new AttachmentMediaAssembler(attachmentPort, chatAttachmentMediaPort);

    // ---------- 归属分派（MediaRef.Source 的意义） ----------

    @Test
    @DisplayName("会话附件引用：字节只从 chatAttachmentMediaPort 读，绝不试探 task 域端口")
    void shouldLoadChatAttachmentFromChatPortOnly() {
        when(chatAttachmentMediaPort.loadChatAttachmentContent(7L)).thenReturn(new byte[]{1, 2, 3});

        List<Media> media = assembler.loadInlineImages(List.of(
                MediaRef.ofChatAttachment(7L, "shot.png", "image/png", 3L)));

        assertThat(media).hasSize(1);
        assertThat(media.get(0).getMimeType().toString()).isEqualTo("image/png");
        assertThat(media.get(0).getName()).isEqualTo("shot.png");
        // ★两张表主键空间独立：同一 id 在两边可能都存在但指向不同文件
        // ⇒ 试探式加载会静默送错图，所以必须验证「没碰另一个端口」
        verify(attachmentPort, never()).loadContent(7L);
    }

    @Test
    @DisplayName("子任务附件引用：字节只从 attachmentPort 读，绝不试探会话附件端口")
    void shouldLoadTaskAttachmentFromTaskPortOnly() {
        when(attachmentPort.loadContent(7L)).thenReturn(new byte[]{1, 2, 3});

        List<Media> media = assembler.loadInlineImages(List.of(
                MediaRef.ofTaskAttachment(7L, "shot.png", "image/png", 3L)));

        assertThat(media).hasSize(1);
        verify(chatAttachmentMediaPort, never()).loadChatAttachmentContent(7L);
    }

    // ---------- 类型判定 ----------

    @Test
    @DisplayName("非图片（PDF / 文本）：不装载、不读字节（I-1：图片字节永不进文本通道的另一面）")
    void shouldSkipNonImageWithoutReadingBytes() {
        List<Media> media = assembler.loadInlineImages(List.of(
                MediaRef.ofChatAttachment(1L, "spec.pdf", "application/pdf", 1024L),
                MediaRef.ofChatAttachment(2L, "readme.md", "text/markdown", 128L)));

        assertThat(media).isEmpty();
        verify(chatAttachmentMediaPort, never()).loadChatAttachmentContent(1L);
        verify(chatAttachmentMediaPort, never()).loadChatAttachmentContent(2L);
    }

    @Test
    @DisplayName("音视频虽有 isMedia 族资格，但不可进消息（provider 的 input 只声明 text/image）")
    void shouldSkipAudioVideo() {
        List<Media> media = assembler.loadInlineImages(List.of(
                MediaRef.ofChatAttachment(1L, "demo.mp4", "video/mp4", 1024L),
                MediaRef.ofChatAttachment(2L, "voice.mp3", "audio/mpeg", 1024L)));

        assertThat(media).isEmpty();
        verify(chatAttachmentMediaPort, never()).loadChatAttachmentContent(1L);
        verify(chatAttachmentMediaPort, never()).loadChatAttachmentContent(2L);
    }

    @Test
    @DisplayName("MIME 缺失 / octet-stream：按扩展名兜底判为图片（浏览器对 .png 常给 octet-stream）")
    void shouldFallbackToExtensionWhenMimeMissing() {
        when(chatAttachmentMediaPort.loadChatAttachmentContent(1L)).thenReturn(new byte[]{1});

        List<Media> media = assembler.loadInlineImages(List.of(
                MediaRef.ofChatAttachment(1L, "shot.PNG", "application/octet-stream", 1L)));

        assertThat(media).hasSize(1);
        assertThat(media.get(0).getMimeType().toString()).isEqualTo("image/png");
    }

    @Test
    @DisplayName("MIME 与扩展名都判不出：放弃装载而不猜 MIME（猜错时 provider 报错与真实原因无关）")
    void shouldSkipWhenMimeUnresolvable() {
        List<Media> media = assembler.loadInlineImages(List.of(
                MediaRef.ofChatAttachment(1L, "blob", null, 1L)));

        assertThat(media).isEmpty();
        verify(chatAttachmentMediaPort, never()).loadChatAttachmentContent(1L);
    }

    // ---------- 体积上限 ----------

    @Test
    @DisplayName("声明体积超限：一次字节都不读（先判后读只省内存，但必须真的省下这次读）")
    void shouldSkipOversizeByDeclaredSizeWithoutReading() {
        List<Media> media = assembler.loadInlineImages(List.of(
                MediaRef.ofChatAttachment(1L, "huge.png", "image/png",
                        AttachmentMediaAssembler.MAX_INLINE_BYTES + 1)));

        assertThat(media).isEmpty();
        verify(chatAttachmentMediaPort, never()).loadChatAttachmentContent(1L);
    }

    @Test
    @DisplayName("声明体积失真（声明小、实际大）：读完再判一次仍拦下")
    void shouldSkipOversizeByActualBytes() {
        long oversize = AttachmentMediaAssembler.MAX_INLINE_BYTES + 1;
        when(chatAttachmentMediaPort.loadChatAttachmentContent(1L))
                .thenReturn(new byte[(int) oversize]);

        List<Media> media = assembler.loadInlineImages(List.of(
                MediaRef.ofChatAttachment(1L, "lied.png", "image/png", 10L)));

        assertThat(media).isEmpty();
    }

    // ---------- 数量上限与降级 ----------

    @Test
    @DisplayName("数量上限：超出部分不装载（上限是防失控，不是成本阈值）")
    void shouldCapInlineCount() {
        List<MediaRef> refs = new java.util.ArrayList<>();
        for (int i = 0; i < AttachmentMediaAssembler.MAX_INLINE_COUNT + 3; i++) {
            long id = i + 1;
            when(chatAttachmentMediaPort.loadChatAttachmentContent(id)).thenReturn(new byte[]{1});
            refs.add(MediaRef.ofChatAttachment(id, "s" + id + ".png", "image/png", 1L));
        }

        List<Media> media = assembler.loadInlineImages(refs);

        assertThat(media).hasSize(AttachmentMediaAssembler.MAX_INLINE_COUNT);
    }

    @Test
    @DisplayName("单张读取异常：跳过该张、返回其余（媒体是增强，不能让整轮失败）")
    void shouldSkipFailingEntryAndKeepOthers() {
        when(chatAttachmentMediaPort.loadChatAttachmentContent(1L))
                .thenThrow(new IllegalStateException("对象存储不可用"));
        when(chatAttachmentMediaPort.loadChatAttachmentContent(2L)).thenReturn(new byte[]{1});

        List<Media> media = assembler.loadInlineImages(List.of(
                MediaRef.ofChatAttachment(1L, "bad.png", "image/png", 1L),
                MediaRef.ofChatAttachment(2L, "ok.png", "image/png", 1L)));

        assertThat(media).hasSize(1);
        assertThat(media.get(0).getName()).isEqualTo("ok.png");
    }

    @Test
    @DisplayName("读取结果为空字节 / null：跳过，不产出一个空的 Media 去顶 provider 400")
    void shouldSkipEmptyBytes() {
        when(chatAttachmentMediaPort.loadChatAttachmentContent(1L)).thenReturn(new byte[0]);
        when(chatAttachmentMediaPort.loadChatAttachmentContent(2L)).thenReturn(null);

        List<Media> media = assembler.loadInlineImages(List.of(
                MediaRef.ofChatAttachment(1L, "empty.png", "image/png", 0L),
                MediaRef.ofChatAttachment(2L, "null.png", "image/png", 1L)));

        assertThat(media).isEmpty();
    }

    @Test
    @DisplayName("null / 空入参：返回空表而不是 null（调用方无需判空，避免 NPE 变成业务故障）")
    void shouldReturnEmptyListForNullOrEmptyInput() {
        assertThat(assembler.loadInlineImages(null)).isEmpty();
        assertThat(assembler.loadInlineImages(List.of())).isEmpty();
    }

    @Test
    @DisplayName("列表里的 null 引用：跳过而不是 NPE")
    void shouldSkipNullElement() {
        List<MediaRef> refs = new java.util.ArrayList<>();
        refs.add(null);
        when(chatAttachmentMediaPort.loadChatAttachmentContent(2L)).thenReturn(new byte[]{1});
        refs.add(MediaRef.ofChatAttachment(2L, "ok.png", "image/png", 1L));

        assertThat(assembler.loadInlineImages(refs)).hasSize(1);
    }

    // ---------- `VU-8`：合并装载（用户上传 + 上游产出）----------

    @Test
    @DisplayName("合并装载：两个来源的图都装进来，且各自走自己的字节端口")
    void shouldLoadBothSources() {
        when(attachmentPort.loadContent(100L)).thenReturn(new byte[]{1});
        when(chatAttachmentMediaPort.loadChatAttachmentContent(200L)).thenReturn(new byte[]{2});

        List<Media> media = assembler.loadMerged(
                List.of(taskRef(100L, "upstream.png")),
                List.of(MediaRef.ofChatAttachment(200L, "user.png", "image/png", 1L)));

        assertThat(media).hasSize(2);
        // 顺序：用户上传在前、上游产出在后（截断时先保用户那张）
        assertThat(media.get(0).getName()).isEqualTo("user.png");
        assertThat(media.get(1).getName()).isEqualTo("upstream.png");
    }

    @Test
    @DisplayName("合并装载只有一份数量预算：5 张上限不因来源翻倍（这是本方法存在的唯一理由）")
    void shouldShareSingleCountBudgetAcrossSources() {
        int total = AttachmentMediaAssembler.MAX_INLINE_COUNT + 3;
        java.util.List<MediaRef> refs = new java.util.ArrayList<>();
        java.util.List<com.helloai.core.agent.port.AttachmentRef> refs2 = new java.util.ArrayList<>();
        for (int i = 0; i < total; i++) {
            long id = i + 1;
            when(chatAttachmentMediaPort.loadChatAttachmentContent(id)).thenReturn(new byte[]{1});
            when(attachmentPort.loadContent(id)).thenReturn(new byte[]{2});
            refs.add(MediaRef.ofChatAttachment(id, "u" + id + ".png", "image/png", 1L));
            refs2.add(taskRef(id, "t" + id + ".png"));
        }

        List<Media> media = assembler.loadMerged(refs2, refs);

        assertThat(media)
                .as("分两次装载会得到 10 张；合并装载必须仍然只有 %s 张",
                        AttachmentMediaAssembler.MAX_INLINE_COUNT)
                .hasSize(AttachmentMediaAssembler.MAX_INLINE_COUNT);
    }

    @Test
    @DisplayName("合并装载：会话图占满上限时，上游图一张都不读（上限截断发生在读字节之前）")
    void shouldNotReadUpstreamWhenBudgetFilledByUserImages() {
        java.util.List<MediaRef> refs = new java.util.ArrayList<>();
        for (int i = 0; i < AttachmentMediaAssembler.MAX_INLINE_COUNT; i++) {
            long id = i + 1;
            when(chatAttachmentMediaPort.loadChatAttachmentContent(id)).thenReturn(new byte[]{1});
            refs.add(MediaRef.ofChatAttachment(id, "u" + id + ".png", "image/png", 1L));
        }

        List<Media> media = assembler.loadMerged(List.of(taskRef(999L, "upstream.png")), refs);

        assertThat(media).hasSize(AttachmentMediaAssembler.MAX_INLINE_COUNT);
        org.mockito.Mockito.verify(attachmentPort, org.mockito.Mockito.never()).loadContent(999L);
    }

    @Test
    @DisplayName("合并装载：任一来源为 null / 空都等价于「那个来源没有图」，不抛异常")
    void shouldTolerateNullOrEmptySource() {
        when(chatAttachmentMediaPort.loadChatAttachmentContent(200L)).thenReturn(new byte[]{2});
        assertThat(assembler.loadMerged(null, List.of(
                MediaRef.ofChatAttachment(200L, "user.png", "image/png", 1L)))).hasSize(1);

        when(attachmentPort.loadContent(100L)).thenReturn(new byte[]{1});
        assertThat(assembler.loadMerged(List.of(taskRef(100L, "up.png")), null)).hasSize(1);

        assertThat(assembler.loadMerged(null, null)).isEmpty();
        assertThat(assembler.loadMerged(List.of(), List.of())).isEmpty();
    }

    /** task 域附件快照（可读、图片）。 */
    private static com.helloai.core.agent.port.AttachmentRef taskRef(Long id, String name) {
        return new com.helloai.core.agent.port.AttachmentRef(id, name, "png", "image/png", 1L,
                com.helloai.common.constant.AttachmentStatus.ACTIVE, true);
    }
}