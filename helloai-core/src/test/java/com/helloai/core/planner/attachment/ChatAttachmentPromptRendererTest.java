package com.helloai.core.planner.attachment;

import com.helloai.common.constant.ChatAttachmentParseStatus;
import com.helloai.core.knowledge.KnowledgeBaseService;
import com.helloai.core.knowledge.KnowledgeSearchResult;
import com.helloai.core.planner.entity.ChatAttachment;
import com.helloai.core.planner.service.ChatAttachmentService;
import com.helloai.core.shared.util.AttachmentContentPolicy;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
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
    private final KnowledgeBaseService knowledgeBaseService = mock(KnowledgeBaseService.class);
    /**
     * 用 1M 窗口复现 ATT-7 改造前的 200k 总预算，使存量断言的数值口径保持不变。
     * 窗口化行为本身由 {@link #shouldScaleBudgetByContextWindow()} 单独覆盖。
     */
    private final ChatAttachmentPromptRenderer renderer =
            new ChatAttachmentPromptRenderer(chatAttachmentService, 1_000_000);

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
    @DisplayName("ATT-3 per-file 配额：5 份各 6.4 万字 → 每份均分 4 万字，不再有文件归零")
    void shouldSplitBudgetEvenlyAcrossFiles() {
        int perFile = AttachmentContentPolicy.ATTACHMENT_CONTENT_PER_FILE_LIMIT;
        int total = AttachmentContentPolicy.ATTACHMENT_CONTENT_TOTAL_LIMIT;
        // ATT-3 per-file 配额：5 份各 64000 字，均分额度 = 200000/5 = 40000。
        // 改造前是先到先得（前 3 份整份 192000，第 4 份 8000，第 5 份归零）；
        // 现在每份都拿到 40000，不再出现「预算已用尽」。
        String chunk = "字".repeat(perFile);
        when(chatAttachmentService.listByConversation(9L)).thenReturn(List.of(
                attachment("a.md", ChatAttachmentParseStatus.READY, chunk),
                attachment("b.md", ChatAttachmentParseStatus.READY, chunk),
                attachment("c.md", ChatAttachmentParseStatus.READY, chunk),
                attachment("d.md", ChatAttachmentParseStatus.READY, chunk),
                attachment("e.md", ChatAttachmentParseStatus.READY, chunk)));

        ChatAttachmentPromptRenderer.Rendered rendered = renderer.render(9L);
        String context = rendered.context();

        assertThat(total).isEqualTo(200_000);
        // 每份都注入 40000 并标截断
        assertThat(context).doesNotContain("本次未注入正文：上下文预算已用尽");
        assertThat(countOccurrences(context, "本次仅注入前 40000 字")).isEqualTo(5);
        assertThat(rendered.manifest()).contains("| 附件5 | e.md |");
    }

    @Test
    @DisplayName("ATT-3：小文件吃不掉的份额要回收给被截断的大文件（不浪费预算）")
    void shouldRecycleUnusedShareToTruncatedFile() {
        // 2 份：小文件 2000 字（需求 2000）、大文件 90000 字。均分 12800 → 小文件只用 2000，
        // 余 10800 应回收给大文件 ⇒ 大文件拿到 12800 + 10800 = 23600
        when(chatAttachmentService.listByConversation(9L)).thenReturn(List.of(
                attachment("小.md", ChatAttachmentParseStatus.READY, "字".repeat(2_000)),
                attachment("大.md", ChatAttachmentParseStatus.READY, "字".repeat(90_000))));

        ChatAttachmentPromptRenderer r = new ChatAttachmentPromptRenderer(chatAttachmentService, 128_000);
        String context = r.render(9L).context();

        // 确认小文件全量、大文件拿到 23600（不是被挤成 0，也不是均分后闲置 10800）
        assertThat(context).contains("字".repeat(2_000));
        assertThat(context).contains("本次仅注入前 23600 字");
    }

    private static int countOccurrences(String text, String needle) {
        int count = 0;
        int from = 0;
        while ((from = text.indexOf(needle, from)) >= 0) {
            count++;
            from += needle.length();
        }
        return count;
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

    // ══════════════════════════════════════════════════════════════
    //  ATT-7：预算按模型窗口折算
    // ══════════════════════════════════════════════════════════════

    @Test
    @DisplayName("ATT-7：128K 窗口 → 总预算 25,600 字，超限文件截断并提示拆分")
    void shouldScaleBudgetByContextWindow() {
        // 128000 token × 40% ÷ 2 token/字 = 25,600 字
        when(chatAttachmentService.listByConversation(9L)).thenReturn(List.of(
                attachment("超大手册.md", ChatAttachmentParseStatus.READY, "字".repeat(30_000))));

        ChatAttachmentPromptRenderer narrow =
                new ChatAttachmentPromptRenderer(chatAttachmentService, 128_000);
        String context = narrow.render(9L).context();

        assertThat(context).contains("本次仅注入前 25600 字");
        assertThat(context).contains("拆分为多个文件分批上传");
    }

    @Test
    @DisplayName("ATT-7：窗口未配置（≤0）时按 128K 保守默认，不返回空预算")
    void shouldFallBackToDefaultWindowWhenUnset() {
        when(chatAttachmentService.listByConversation(9L)).thenReturn(List.of(
                attachment("手册.md", ChatAttachmentParseStatus.READY, "正文")));

        ChatAttachmentPromptRenderer unset = new ChatAttachmentPromptRenderer(chatAttachmentService, 0);
        String context = unset.render(9L).context();

        assertThat(context).contains("正文");
    }

    @Test
    @DisplayName("ATT-7：极小窗口（8K）时预算下探到保底 4,000 字，不致归零")
    void shouldKeepMinimumBudgetForTinyWindow() {
        when(chatAttachmentService.listByConversation(9L)).thenReturn(List.of(
                attachment("手册.md", ChatAttachmentParseStatus.READY, "字".repeat(10_000))));

        ChatAttachmentPromptRenderer tiny = new ChatAttachmentPromptRenderer(chatAttachmentService, 8_000);
        String context = tiny.render(9L).context();

        assertThat(context).contains("本次仅注入前 4000 字");
    }

    // ══════════════════════════════════════════════════════════════
    //  ATT-3：检索层（第三层）
    // ══════════════════════════════════════════════════════════════

    @Test
    @DisplayName("ATT-3：有 kb_source 的附件会触发检索，命中片段带锚点与文件名")
    void shouldRenderRetrievalWhenAttachmentIngested() {
        ChatAttachment ingested = attachment("大手册.md", ChatAttachmentParseStatus.READY, "正文");
        ingested.setKbSource("chat:9:1");
        when(chatAttachmentService.listByConversation(9L)).thenReturn(List.of(ingested));
        when(knowledgeBaseService.search(eq("怎么装"), anyInt(), eq(9L), isNull(), anyInt()))
                .thenReturn(new KnowledgeSearchResult("怎么装",
                        List.of(new KnowledgeSearchResult.Hit("chat:9:1", 2, "第三步：复制到技能目录")),
                        10, false));

        ChatAttachmentPromptRenderer r = new ChatAttachmentPromptRenderer(
                chatAttachmentService, knowledgeBaseService, 128_000);
        String retrieval = r.render(9L, "怎么装").retrieval();

        assertThat(retrieval).contains("大手册.md 第 3 片");
        assertThat(retrieval).contains("[kb:chat:9:1#2]");
        assertThat(retrieval).contains("第三步：复制到技能目录");
    }

    @Test
    @DisplayName("ATT-3：无 kb_source 时不检索（避免每轮白花一次嵌入调用）")
    void shouldSkipRetrievalWhenNoIngest() {
        when(chatAttachmentService.listByConversation(9L)).thenReturn(List.of(
                attachment("小文件.md", ChatAttachmentParseStatus.READY, "正文")));

        ChatAttachmentPromptRenderer r = new ChatAttachmentPromptRenderer(
                chatAttachmentService, knowledgeBaseService, 128_000);
        r.render(9L, "问点什么");

        verifyNoInteractions(knowledgeBaseService);
    }

    @Test
    @DisplayName("ATT-3：检索用独立预算（128K 窗口 ⇒ 9,600 字），不沿用全局 4,000")
    void shouldUseDedicatedRetrievalBudget() {
        ChatAttachment ingested = attachment("大手册.md", ChatAttachmentParseStatus.READY, "正文");
        ingested.setKbSource("chat:9:1");
        when(chatAttachmentService.listByConversation(9L)).thenReturn(List.of(ingested));
        when(knowledgeBaseService.search(anyString(), anyInt(), anyLong(), isNull(), anyInt()))
                .thenReturn(KnowledgeSearchResult.empty("q"));

        ChatAttachmentPromptRenderer r = new ChatAttachmentPromptRenderer(
                chatAttachmentService, knowledgeBaseService, 128_000);
        r.render(9L, "问点什么");

        // 128000 × 15% ÷ 2 = 9,600；若错误地沿用 KbProperties.charBudget 会是 4,000
        verify(knowledgeBaseService).search(anyString(), anyInt(), anyLong(), isNull(),
                org.mockito.ArgumentMatchers.eq(9_600));
    }

    @Test
    @DisplayName("ATT-3：检索抛异常时降级为占位，正文层不受影响")
    void shouldDegradeRetrievalFailure() {
        ChatAttachment ingested = attachment("大手册.md", ChatAttachmentParseStatus.READY, "正文");
        ingested.setKbSource("chat:9:1");
        when(chatAttachmentService.listByConversation(9L)).thenReturn(List.of(ingested));
        when(knowledgeBaseService.search(anyString(), anyInt(), anyLong(), isNull(), anyInt()))
                .thenThrow(new IllegalStateException("embed 挂了"));

        ChatAttachmentPromptRenderer r = new ChatAttachmentPromptRenderer(
                chatAttachmentService, knowledgeBaseService, 128_000);
        ChatAttachmentPromptRenderer.Rendered rendered = r.render(9L, "怎么装");

        assertThat(rendered.retrieval()).contains("未检索附件片段");
        assertThat(rendered.context()).contains("正文");
    }
}
