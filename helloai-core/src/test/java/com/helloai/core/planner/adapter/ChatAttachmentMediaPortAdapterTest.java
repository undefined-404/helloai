package com.helloai.core.planner.adapter;

import com.helloai.common.base.BizException;
import com.helloai.core.planner.entity.ChatAttachment;
import com.helloai.core.planner.service.ChatAttachmentService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 会话附件字节读取端口（`VU-9`）。
 *
 * <p><b>为什么要锁「走 download 而不是 preview」</b>：{@code preview} 带浏览器内联预览的
 * 5MB / 白名单闸门——那是「给 &lt;img&gt; 标签看」的口径，与「送给模型看」不是同一判据
 * （计划 §3 `VU-9` 的「限额是处理参数不是安全边界」）。若哪天有人把这里改成 {@code preview}，
 * 表现会是「大一点的截图在对话里就看不见了」，而报错没有任何提示。</p>
 */
@DisplayName("ChatAttachmentMediaPortAdapter")
class ChatAttachmentMediaPortAdapterTest {

    private final ChatAttachmentService chatAttachmentService = mock(ChatAttachmentService.class);
    private final ChatAttachmentMediaPortAdapter adapter =
            new ChatAttachmentMediaPortAdapter(chatAttachmentService);

    @Test
    @DisplayName("正常读取：走 download（裸字节语义），原样返回内容")
    void shouldLoadRawBytesViaDownload() {
        byte[] bytes = {1, 2, 3, 4};
        when(chatAttachmentService.download(7L))
                .thenReturn(payload("shot.png", "image/png", bytes));

        assertThat(adapter.loadChatAttachmentContent(7L)).isEqualTo(bytes);
        // ★必须是 download 而不是 preview：preview 会因 5MB / 白名单拒绝，
        // 让「大截图送不进模型」变成一个没有提示的静默失败
        verify(chatAttachmentService).download(7L);
    }

    @Test
    @DisplayName("附件不存在 / 已删除：提供方的 BizException 原样上抛，由装载器逐条降级")
    void shouldPropagateBizException() {
        when(chatAttachmentService.download(7L)).thenThrow(new BizException("附件不存在"));

        assertThatThrownBy(() -> adapter.loadChatAttachmentContent(7L))
                .isInstanceOf(BizException.class);
    }

    @Test
    @DisplayName("空内容：显式报错而不是返回空数组（空数组会被装载器当成「无图」静默吞掉）")
    void shouldRejectEmptyContent() {
        when(chatAttachmentService.download(7L))
                .thenReturn(payload("empty.png", "image/png", new byte[0]));

        assertThatThrownBy(() -> adapter.loadChatAttachmentContent(7L))
                .isInstanceOf(BizException.class);
    }

    @Test
    @DisplayName("null 载荷：同样显式报错")
    void shouldRejectNullPayload() {
        when(chatAttachmentService.download(7L)).thenReturn(null);

        assertThatThrownBy(() -> adapter.loadChatAttachmentContent(7L))
                .isInstanceOf(BizException.class);
    }

    @Test
    @DisplayName("id 为空：拒绝而不是拿 null 去查库（null 主键在 MyBatis-Plus 里可能退化成全表扫）")
    void shouldRejectNullId() {
        assertThatThrownBy(() -> adapter.loadChatAttachmentContent(null))
                .isInstanceOf(BizException.class);
    }

    private static ChatAttachmentService.PreviewPayload payload(String name, String mime, byte[] content) {
        // 组件顺序是 (content, contentType, fileName) —— 按位置构造，别按名字直觉填
        return new ChatAttachmentService.PreviewPayload(content, mime, name);
    }

    /** 仅为让 IDE 认出 ChatAttachment 依赖存在（本类不直接构造实体）。 */
    @SuppressWarnings("unused")
    private static final Class<ChatAttachment> ENTITY_MARKER = ChatAttachment.class;
}