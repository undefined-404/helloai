package com.helloai.core.planner.attachment;

import com.helloai.common.constant.ChatAttachmentParseStatus;
import com.helloai.core.planner.entity.ChatAttachment;
import com.helloai.core.planner.service.ChatAttachmentService;
import com.helloai.core.shared.util.AttachmentContentPolicy;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 会话附件 Prompt 渲染（方案 §3.4）。
 *
 * <p>锁定的语义：清单<b>恒</b>注入（哪怕正文读不出也要让模型知道有文件）；正文只注入可读项；
 * 不可读项必须写明原因（否则模型会把「没有正文」当成「没提附件」而瞎猜）。</p>
 */
@DisplayName("ChatAttachmentPromptRenderer 附件注入")
class ChatAttachmentPromptRendererTest {

    private final ChatAttachmentService chatAttachmentService = mock(ChatAttachmentService.class);
    private final ChatAttachmentPromptRenderer renderer = new ChatAttachmentPromptRenderer(chatAttachmentService);

    private static ChatAttachment attachment(String name, ChatAttachmentParseStatus status, String text) {
        ChatAttachment a = new ChatAttachment();
        a.setId(1L);
        a.setFileName(name);
        a.setFileType("md");
        a.setFileSize(2048L);
        a.setParseStatus(status);
        a.setExtractedText(text);
        a.setExtractedChars(text == null ? null : text.length());
        return a;
    }

    @Test
    @DisplayName("无附件：两层都给占位文本（保持 Prompt 语义节稳定）")
    void shouldRenderPlaceholderWhenNoAttachment() {
        when(chatAttachmentService.listByConversation(9L)).thenReturn(List.of());

        ChatAttachmentPromptRenderer.Rendered rendered = renderer.render(9L);

        assertThat(rendered.manifest()).isEqualTo("（本轮无附件）");
        assertThat(rendered.context()).isEqualTo("（本轮无附件）");
    }

    @Test
    @DisplayName("READY 文件：清单含文件名与「可读」，正文进入 CONTEXT")
    void shouldInjectReadableContent() {
        when(chatAttachmentService.listByConversation(9L)).thenReturn(List.of(
                attachment("接口规格.md", ChatAttachmentParseStatus.READY, "字段 taskId 为 Long 类型。")));

        ChatAttachmentPromptRenderer.Rendered rendered = renderer.render(9L);

        assertThat(rendered.manifest())
                .contains("| 附件1 | 接口规格.md |")
                .contains("可读")
                .contains("2KB");
        assertThat(rendered.context())
                .contains("[附件1] 接口规格.md")
                .contains("taskId 为 Long 类型");
    }

    @Test
    @DisplayName("SKIPPED（图片）：清单标「需识别（无正文）」，正文层写明无可用正文而不是留空")
    void shouldMarkSkippedWithoutContent() {
        ChatAttachment image = attachment("shot.png", ChatAttachmentParseStatus.SKIPPED, null);
        image.setParseError("图片/音视频暂不支持正文提取（P0 阶段），仅登记元信息");
        when(chatAttachmentService.listByConversation(9L)).thenReturn(List.of(image));

        ChatAttachmentPromptRenderer.Rendered rendered = renderer.render(9L);

        assertThat(rendered.manifest()).contains("需识别（无正文）");
        assertThat(rendered.context())
                .contains("无可用正文")
                .contains("图片/音视频暂不支持正文提取");
    }

    @Test
    @DisplayName("FAILED（损坏文件）：清单标「解析失败」，正文层带失败原因")
    void shouldMarkFailedWithReason() {
        ChatAttachment broken = attachment("broken.pdf", ChatAttachmentParseStatus.FAILED, null);
        broken.setParseError("Tika 解析失败：broken.pdf：Invalid PDF header");
        when(chatAttachmentService.listByConversation(9L)).thenReturn(List.of(broken));

        ChatAttachmentPromptRenderer.Rendered rendered = renderer.render(9L);

        assertThat(rendered.manifest()).contains("解析失败");
        assertThat(rendered.context()).contains("Invalid PDF header");
    }

    @Test
    @DisplayName("超单文件预算：正文截断并显式标注原文长度（不让模型以为只给了这么多）")
    void shouldTruncateOverPerFileLimit() {
        int limit = AttachmentContentPolicy.ATTACHMENT_CONTENT_PER_FILE_LIMIT;
        String big = "字".repeat(limit + 1000);
        when(chatAttachmentService.listByConversation(9L)).thenReturn(List.of(
                attachment("big.md", ChatAttachmentParseStatus.READY, big)));

        ChatAttachmentPromptRenderer.Rendered rendered = renderer.render(9L);

        assertThat(rendered.context())
                .contains("该文件正文已截断")
                .contains("原文 " + big.length() + " 字")
                .contains("本次仅注入前 " + limit + " 字");
    }

    @Test
    @DisplayName("总计预算用尽后的后续文件：不注入正文但保留清单项（信息不丢，只降级）")
    void shouldSkipContentButKeepManifestWhenBudgetExhausted() {
        int perFile = AttachmentContentPolicy.ATTACHMENT_CONTENT_PER_FILE_LIMIT;
        int total = AttachmentContentPolicy.ATTACHMENT_CONTENT_TOTAL_LIMIT;
        // 5 份各 64000 字：前 3 份整份注入（192000），第 4 份只注入剩余 8000（并标截断），
        // 第 5 份预算归零 → 不注入正文但保留清单项
        String chunk = "字".repeat(perFile);
        when(chatAttachmentService.listByConversation(9L)).thenReturn(List.of(
                attachment("a.md", ChatAttachmentParseStatus.READY, chunk),
                attachment("b.md", ChatAttachmentParseStatus.READY, chunk),
                attachment("c.md", ChatAttachmentParseStatus.READY, chunk),
                attachment("d.md", ChatAttachmentParseStatus.READY, chunk),
                attachment("e.md", ChatAttachmentParseStatus.READY, chunk)));

        ChatAttachmentPromptRenderer.Rendered rendered = renderer.render(9L);

        assertThat(total).isEqualTo(200_000);
        assertThat(rendered.context()).contains("本次未注入正文：上下文预算已用尽");
        assertThat(rendered.manifest()).contains("| 附件5 | e.md |");
    }

    // ══════════════════════════════════════════════════════════════
    //  决策阶段视图（renderForDecision）
    //
    //  锁定的语义：决策调用发生在正文渲染之前，不注入附件则模型只能拿用户那句问话
    //  当检索依据，把「分析我上传的手册」改写成「手册 方案 正确性」这类无实体空壳词。
    //  deer-flow #1647 根因 2/4 即此缺陷；本组用例防回归。
    // ══════════════════════════════════════════════════════════════

    @Test
    @DisplayName("决策视图·无附件：给占位文本，不返回空串（保持 Prompt 语义节稳定）")
    void decisionView_shouldRenderPlaceholderWhenNoAttachment() {
        when(chatAttachmentService.listByConversation(9L)).thenReturn(List.of());

        assertThat(renderer.renderForDecision(9L)).isEqualTo("（本轮无附件）");
    }

    @Test
    @DisplayName("决策视图·conversationId 为空（新建会话首轮）：直接占位，不查库")
    void decisionView_nullConversation_shouldNotQueryStore() {
        assertThat(renderer.renderForDecision(null)).isEqualTo("（本轮无附件）");
    }

    @Test
    @DisplayName("决策视图·有附件：清单 + 预览 + 三类锚点（法规名/标准号/技术专名）都出现")
    void decisionView_shouldExposeManifestPreviewAndAnchors() {
        String text = "《运输机场使用许可规定》要求手册采用活页格式。执行 GB/T 12345 标准，"
                + "工具用 PowerShell 与 Windows。";
        when(chatAttachmentService.listByConversation(9L)).thenReturn(List.of(
                attachment("手册.md", ChatAttachmentParseStatus.READY, text)));

        String view = renderer.renderForDecision(9L);

        assertThat(view)
                .contains("【附件清单】")
                .contains("| 附件1 | 手册.md |")
                .contains("【内容预览】")
                .contains("【可外部核验的锚点】")
                .contains("《运输机场使用许可规定》")
                .contains("GB/T 12345")
                .contains("PowerShell");
    }

    @Test
    @DisplayName("决策视图·预览只给前 500 字并标注省略（决策是轻量调用，不灌全文）")
    void decisionView_shouldTruncatePreview() {
        String longText = "字".repeat(1000);
        when(chatAttachmentService.listByConversation(9L)).thenReturn(List.of(
                attachment("长文.md", ChatAttachmentParseStatus.READY, longText)));

        String view = renderer.renderForDecision(9L);

        assertThat(view).contains("字".repeat(500) + "…");
    }

    @Test
    @DisplayName("决策视图·无可外部核验锚点：必须写明「未发现」（模板据此判 need_search=false）")
    void decisionView_shouldStateNoAnchorExplicitly() {
        when(chatAttachmentService.listByConversation(9L)).thenReturn(List.of(
                attachment("内部流程.md", ChatAttachmentParseStatus.READY,
                        "这是一份内部手册，说明日常工作流程与交接要求。")));

        String view = renderer.renderForDecision(9L);

        assertThat(view).contains("【可外部核验的锚点】").contains("未发现");
    }

    @Test
    @DisplayName("决策视图·解析中的附件：清单可见但标「解析中」，不编造正文")
    void decisionView_pendingAttachment_shouldMarkParsing() {
        when(chatAttachmentService.listByConversation(9L)).thenReturn(List.of(
                attachment("待解析.pdf", ChatAttachmentParseStatus.PARSING, null)));

        String view = renderer.renderForDecision(9L);

        assertThat(view).contains("待解析.pdf").contains("解析中").contains("（无可用正文）");
    }
}
