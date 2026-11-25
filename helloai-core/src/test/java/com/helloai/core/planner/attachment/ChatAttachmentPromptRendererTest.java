package com.helloai.core.planner.attachment;

import com.helloai.common.constant.ChatAttachmentParseStatus;
import com.helloai.core.agent.port.MediaRef;
import com.helloai.core.knowledge.KnowledgeBaseService;
import com.helloai.core.knowledge.KnowledgeSearchResult;
import com.helloai.core.planner.entity.ChatAttachment;
import com.helloai.core.planner.search.BochaRerankService;
import com.helloai.core.planner.service.ChatAttachmentService;
import com.helloai.core.shared.util.AttachmentContentPolicy;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
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
    private final BochaRerankService bochaRerankService = mock(BochaRerankService.class);
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
    @DisplayName("SKIPPED（图片）：清单与正文层都<b>只陈述事实</b>，不写「需识别（无正文）」这种会误导模型的断言")
    void shouldMarkSkippedImageWithoutContent() {
        ChatAttachment image = attachment("shot.png", ChatAttachmentParseStatus.SKIPPED, null);
        image.setParseError("图片/音视频暂不支持正文提取（P0 阶段），仅登记元信息");
        when(chatAttachmentService.listByConversation(9L)).thenReturn(List.of(image));

        ChatAttachmentPromptRenderer.Rendered rendered = renderer.render(9L);

        // ★VU-9：旧文案「需识别（无正文）」会让模型判定「这张图读不出、当它不存在」，
        // 与另经图像通道送出的图像消息自相矛盾。现改为中性陈述：只说「图片，另经图像通道送入」。
        assertThat(rendered.manifest()).contains("图片（另经图像通道送入）");
        assertThat(rendered.context())
                .contains("图片无文本正文")
                .contains("另经图像通道送入")
                // 不得把 parseError 那句「暂不支持正文提取」带给模型——它对图片是噪声，
                // 且会诱导模型得出「平台看不了图」的错误结论
                .doesNotContain("暂不支持正文提取");
    }

    @Test
    @DisplayName("SKIPPED（音视频）：仍写明「正文不可提取」——这类文件确实没有可用通道，必须让模型知道")
    void shouldMarkSkippedMediaWithoutContent() {
        ChatAttachment video = attachment("demo.mp4", ChatAttachmentParseStatus.SKIPPED, null);
        video.setParseError("图片/音视频暂不支持正文提取（P0 阶段），仅登记元信息");
        when(chatAttachmentService.listByConversation(9L)).thenReturn(List.of(video));

        ChatAttachmentPromptRenderer.Rendered rendered = renderer.render(9L);

        assertThat(rendered.manifest()).contains("音视频（正文不可提取）");
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
        when(knowledgeBaseService.search(eq("怎么装"), anyInt(), eq(9L), isNull(), anyInt(), anyList()))
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
        when(knowledgeBaseService.search(anyString(), anyInt(), anyLong(), isNull(), anyInt(), anyList()))
                .thenReturn(KnowledgeSearchResult.empty("q"));

        ChatAttachmentPromptRenderer r = new ChatAttachmentPromptRenderer(
                chatAttachmentService, knowledgeBaseService, 128_000);
        r.render(9L, "问点什么");

        // 128000 × 15% ÷ 2 = 9,600；若错误地沿用 KbProperties.charBudget 会是 4,000
        verify(knowledgeBaseService).search(anyString(), anyInt(), anyLong(), isNull(),
                org.mockito.ArgumentMatchers.eq(9_600), anyList());
    }

    @Test
    @DisplayName("ATT-3：指定目标附件时只检索该附件的分片（问第2份不召回第1份）")
    @SuppressWarnings("unchecked")
    void shouldRestrictRetrievalToTargetAttachments() {
        ChatAttachment first = attachment("附件一.md", ChatAttachmentParseStatus.READY, "正文一");
        first.setKbSource("chat:9:1");
        ChatAttachment second = attachment("附件二.md", ChatAttachmentParseStatus.READY, "正文二");
        second.setKbSource("chat:9:2");
        when(chatAttachmentService.listByConversation(9L)).thenReturn(List.of(first, second));
        when(knowledgeBaseService.search(anyString(), anyInt(), anyLong(), isNull(), anyInt(), anyList()))
                .thenReturn(KnowledgeSearchResult.empty("q"));

        ChatAttachmentPromptRenderer r = new ChatAttachmentPromptRenderer(
                chatAttachmentService, knowledgeBaseService, 128_000);
        r.render(9L, "只问第二份", List.of(2));

        org.mockito.ArgumentCaptor<List<String>> captor =
                org.mockito.ArgumentCaptor.forClass(List.class);
        verify(knowledgeBaseService).search(anyString(), anyInt(), anyLong(), isNull(), anyInt(),
                captor.capture());
        assertThat(captor.getValue()).containsExactly("chat:9:2");
    }

    @Test
    @DisplayName("ATT-3：目标序号越界时回退为全附件检索（不因模型报错序号导致检索不到）")
    @SuppressWarnings("unchecked")
    void shouldFallBackToAllWhenTargetsOutOfRange() {
        ChatAttachment only = attachment("唯一.md", ChatAttachmentParseStatus.READY, "正文");
        only.setKbSource("chat:9:1");
        when(chatAttachmentService.listByConversation(9L)).thenReturn(List.of(only));
        when(knowledgeBaseService.search(anyString(), anyInt(), anyLong(), isNull(), anyInt(), anyList()))
                .thenReturn(KnowledgeSearchResult.empty("q"));

        ChatAttachmentPromptRenderer r = new ChatAttachmentPromptRenderer(
                chatAttachmentService, knowledgeBaseService, 128_000);
        r.render(9L, "问点什么", List.of(9)); // 只有 1 份附件，序号 9 越界

        org.mockito.ArgumentCaptor<List<String>> captor =
                org.mockito.ArgumentCaptor.forClass(List.class);
        verify(knowledgeBaseService).search(anyString(), anyInt(), anyLong(), isNull(), anyInt(),
                captor.capture());
        assertThat(captor.getValue()).containsExactly("chat:9:1");
    }

    @Test
    @DisplayName("ATT-3：正文层也只注入目标附件（问第2份时第1份只留标题）")
    void shouldScopeContextBodyToTargets() {
        when(chatAttachmentService.listByConversation(9L)).thenReturn(List.of(
                attachment("附件一.md", ChatAttachmentParseStatus.READY, "第一份的正文内容"),
                attachment("附件二.md", ChatAttachmentParseStatus.READY, "第二份的正文内容")));

        ChatAttachmentPromptRenderer r = new ChatAttachmentPromptRenderer(chatAttachmentService, 128_000);
        String context = r.render(9L, "只问第二份", List.of(2)).context();

        assertThat(context).contains("第二份的正文内容");
        assertThat(context).doesNotContain("第一份的正文内容");
        assertThat(context).contains("本轮未涉及该附件");
        // 序号仍按清单全量顺序
        assertThat(context).contains("[附件2] 附件二.md");
    }

    @Test
    @DisplayName("ATT-3：未指定目标附件时正文全量注入（保持旧行为）")
    void shouldInjectAllBodiesWhenNoTargets() {
        when(chatAttachmentService.listByConversation(9L)).thenReturn(List.of(
                attachment("附件一.md", ChatAttachmentParseStatus.READY, "第一份的正文内容"),
                attachment("附件二.md", ChatAttachmentParseStatus.READY, "第二份的正文内容")));

        ChatAttachmentPromptRenderer r = new ChatAttachmentPromptRenderer(chatAttachmentService, 128_000);
        String context = r.render(9L, "都说说").context();

        assertThat(context).contains("第一份的正文内容").contains("第二份的正文内容");
        assertThat(context).doesNotContain("本轮未涉及该附件");
    }

    @Test
    @DisplayName("ATT-3：检索抛异常时降级为占位，正文层不受影响")
    void shouldDegradeRetrievalFailure() {
        ChatAttachment ingested = attachment("大手册.md", ChatAttachmentParseStatus.READY, "正文");
        ingested.setKbSource("chat:9:1");
        when(chatAttachmentService.listByConversation(9L)).thenReturn(List.of(ingested));
        when(knowledgeBaseService.search(anyString(), anyInt(), anyLong(), isNull(), anyInt(), anyList()))
                .thenThrow(new IllegalStateException("embed 挂了"));

        ChatAttachmentPromptRenderer r = new ChatAttachmentPromptRenderer(
                chatAttachmentService, knowledgeBaseService, 128_000);
        ChatAttachmentPromptRenderer.Rendered rendered = r.render(9L, "怎么装");

        assertThat(rendered.retrieval()).contains("未检索附件片段");
        assertThat(rendered.context()).contains("正文");
    }

    // ══════════════════════════════════════════════════════════════
    //  ATT-3：片段重排
    // ══════════════════════════════════════════════════════════════

    private KnowledgeSearchResult threeHitResult() {
        return new KnowledgeSearchResult("怎么装", List.of(
                new KnowledgeSearchResult.Hit("chat:9:1", 0, "第一片：前置准备"),
                new KnowledgeSearchResult.Hit("chat:9:1", 1, "第二片：复制文件"),
                new KnowledgeSearchResult.Hit("chat:9:1", 2, "第三片：重载配置")), 30, false);
    }

    private ChatAttachment ingestedAttachment() {
        ChatAttachment ingested = attachment("大手册.md", ChatAttachmentParseStatus.READY, "正文");
        ingested.setKbSource("chat:9:1");
        return ingested;
    }

    @Test
    @DisplayName("ATT-3：开关开启时按重排下标注入（第3片排到最前）")
    void shouldRerankRetrievalHitsWhenEnabled() {
        when(chatAttachmentService.listByConversation(9L)).thenReturn(List.of(ingestedAttachment()));
        when(knowledgeBaseService.search(anyString(), anyInt(), anyLong(), isNull(), anyInt(), anyList()))
                .thenReturn(threeHitResult());
        when(bochaRerankService.rerankOrder(eq("怎么装"), anyList())).thenReturn(List.of(2, 0, 1));

        ChatAttachmentPromptRenderer r = new ChatAttachmentPromptRenderer(
                chatAttachmentService, knowledgeBaseService, bochaRerankService, 128_000, true);
        String retrieval = r.render(9L, "怎么装").retrieval();

        assertThat(retrieval.indexOf("第三片")).isLessThan(retrieval.indexOf("第一片"));
        assertThat(retrieval.indexOf("第一片")).isLessThan(retrieval.indexOf("第二片"));
    }

    @Test
    @DisplayName("ATT-3：开关关闭时不调用重排，保持向量序")
    void shouldKeepVectorOrderWhenRerankDisabled() {
        when(chatAttachmentService.listByConversation(9L)).thenReturn(List.of(ingestedAttachment()));
        when(knowledgeBaseService.search(anyString(), anyInt(), anyLong(), isNull(), anyInt(), anyList()))
                .thenReturn(threeHitResult());

        ChatAttachmentPromptRenderer r = new ChatAttachmentPromptRenderer(
                chatAttachmentService, knowledgeBaseService, bochaRerankService, 128_000, false);
        String retrieval = r.render(9L, "怎么装").retrieval();

        assertThat(retrieval.indexOf("第一片")).isLessThan(retrieval.indexOf("第二片"));
        assertThat(retrieval.indexOf("第二片")).isLessThan(retrieval.indexOf("第三片"));
        verifyNoInteractions(bochaRerankService);
    }

    @Test
    @DisplayName("ATT-3：重排失败（返回 null）时降级为向量序，片段照样注入")
    void shouldKeepVectorOrderWhenRerankFails() {
        when(chatAttachmentService.listByConversation(9L)).thenReturn(List.of(ingestedAttachment()));
        when(knowledgeBaseService.search(anyString(), anyInt(), anyLong(), isNull(), anyInt(), anyList()))
                .thenReturn(threeHitResult());
        when(bochaRerankService.rerankOrder(eq("怎么装"), anyList())).thenReturn(null);

        ChatAttachmentPromptRenderer r = new ChatAttachmentPromptRenderer(
                chatAttachmentService, knowledgeBaseService, bochaRerankService, 128_000, true);
        String retrieval = r.render(9L, "怎么装").retrieval();

        assertThat(retrieval.indexOf("第一片")).isLessThan(retrieval.indexOf("第二片"));
        assertThat(retrieval).contains("第三片");
    }

    // ------------------------------------------------------------------
    // G-045：null（未指定）与 []（明确一个都不要）必须区分
    // ------------------------------------------------------------------

    /** 三份可读附件，id 分别 101/102/103，正文各带唯一标记，便于断言「谁的正文被注入了」。 */
    private List<ChatAttachment> threeReadableAttachments() {
        ChatAttachment a = attachment("a.md", ChatAttachmentParseStatus.READY, "正文甲 MARK-A");
        a.setId(101L);
        ChatAttachment b = attachment("b.md", ChatAttachmentParseStatus.READY, "正文乙 MARK-B");
        b.setId(102L);
        ChatAttachment c = attachment("c.md", ChatAttachmentParseStatus.READY, "正文丙 MARK-C");
        c.setId(103L);
        return List.of(a, b, c);
    }

    @Test
    @DisplayName("G-045 真值表①：selectedIds=null（未指定）⇒ 回落全量，三份正文全注入")
    void nullSelectionShouldFallBackToAll() {
        when(chatAttachmentService.listByConversation(1L)).thenReturn(threeReadableAttachments());

        String context = renderer.render(1L, "看看这些文件", null, null).context();

        assertThat(context).contains("MARK-A").contains("MARK-B").contains("MARK-C");
    }

    @Test
    @DisplayName("G-045 真值表②：selectedIds=[]（明确全排除）⇒ 一份都不注入，且显式告知用户已排除")
    void emptySelectionShouldInjectNothing() {
        when(chatAttachmentService.listByConversation(1L)).thenReturn(threeReadableAttachments());

        String context = renderer.render(1L, "看看这些文件", List.of(), null).context();

        // 关键：绝不能回落成「全量注入」——这正是修复前的反转行为
        assertThat(context).doesNotContain("MARK-A");
        assertThat(context).doesNotContain("MARK-B");
        assertThat(context).doesNotContain("MARK-C");
        // 必须显式声明，而不是渲染成「本轮未涉及该附件：提问指向的是其他附件」
        // （空集场景下没有「其他附件」，那句话会误导模型以为清单不完整）
        assertThat(context).contains("已排除全部").contains("3 份附件");
    }

    @Test
    @DisplayName("G-045 真值表③：selectedIds 非空 ⇒ 只注入命中的那一份（显式信号优先于全量）")
    void explicitSelectionShouldInjectOnlyPicked() {
        when(chatAttachmentService.listByConversation(1L)).thenReturn(threeReadableAttachments());

        String context = renderer.render(1L, "只看第二份", List.of(102L), null).context();

        assertThat(context).contains("MARK-B");
        assertThat(context).doesNotContain("MARK-A");
        assertThat(context).doesNotContain("MARK-C");
    }

    @Test
    @DisplayName("G-045 真值表④：空集优先于模型序号（显式信号不被推断信号覆盖）")
    void emptySelectionShouldWinOverTargets() {
        when(chatAttachmentService.listByConversation(1L)).thenReturn(threeReadableAttachments());

        // 模型说「第 1 张」，但用户明确全排除 ⇒ 尊重用户
        String context = renderer.render(1L, "第 1 张讲了什么", List.of(), List.of(1)).context();

        assertThat(context).doesNotContain("MARK-A");
        assertThat(context).contains("已排除全部");
    }

    @Test
    @DisplayName("G-045：空集时送模型的图片引用也为空（文本通道排除，图像通道必须同步排除）")
    void emptySelectionShouldSendNoMedia() {
        ChatAttachment img = attachment("spec.png", ChatAttachmentParseStatus.SKIPPED, null);
        img.setId(201L);
        img.setMimeType("image/png");
        ChatAttachment doc = attachment("spec.md", ChatAttachmentParseStatus.READY, "正文 MARK-D");
        doc.setId(202L);
        when(chatAttachmentService.listByConversation(1L)).thenReturn(List.of(img, doc));

        var rendered = renderer.renderWithMedia(1L, "看图", List.of(), null);

        // 同一个判定必须同时作用于两条通道（判据 32：作用域与产出一次算完）
        assertThat(rendered.mediaRefs()).isEmpty();
        assertThat(rendered.rendered().context()).doesNotContain("MARK-D");
    }

    // ------------------------------------------------------------------
    // G-046：注入侧超限时「保新」而非「保旧」
    // ------------------------------------------------------------------

    /**
     * 造 n 份附件，文件名带序号以便断言「留下的是哪几份」。
     *
     * <p>{@code listByConversation} 的返回是<b>上传正序</b>（ASC），所以第 1 份是最老的；
     * {@code listLatestByConversation} 返回的是<b>上传倒序</b>（DESC，最新在前）。
     * 两个桩严格按各自真实语义给数据，测试才验得住「倒序取 N 再正序排」。</p>
     */
    private static List<ChatAttachment> attachmentsFrom(int n) {
        List<ChatAttachment> list = new ArrayList<>();
        for (int i = 1; i <= n; i++) {
            ChatAttachment a = attachment("f" + i + ".md", ChatAttachmentParseStatus.READY, "正文 MARK-" + i);
            a.setId((long) i);
            list.add(a);
        }
        return list;
    }

    @Test
    @DisplayName("G-046：25 份超过上限 20 ⇒ 保留<b>最新</b> 20 份（丢最早 5 份），且仍按上传正序编号")
    void overLimitShouldKeepNewestAndRestoreAscendingOrder() {
        int total = ChatAttachmentPromptRenderer.MAX_INJECTED_ATTACHMENTS + 5;
        List<ChatAttachment> ascending = attachmentsFrom(total);
        List<ChatAttachment> descending = new ArrayList<>(ascending);
        Collections.reverse(descending);
        when(chatAttachmentService.listByConversation(9L)).thenReturn(ascending);
        when(chatAttachmentService.listLatestByConversation(9L,
                ChatAttachmentPromptRenderer.MAX_INJECTED_ATTACHMENTS))
                .thenReturn(descending.subList(0, ChatAttachmentPromptRenderer.MAX_INJECTED_ATTACHMENTS));

        ChatAttachmentPromptRenderer.Rendered rendered = renderer.render(9L);

        // ★核心断言：被丢弃的是「最早那 5 份」，不是「最新那 5 份」（原缺陷恰好相反）
        assertThat(rendered.manifest())
                .doesNotContain("f1.md").doesNotContain("f5.md")
                .contains("f6.md").contains("f25.md");
        assertThat(rendered.context())
                // 带换行匹配：`MARK-1` 是 `MARK-10`..`MARK-19` 的前缀，裸串匹配会误报
                .doesNotContain("MARK-1\n").doesNotContain("MARK-5\n")
                .contains("MARK-6\n").contains("MARK-25\n");
        // 倒序取回后必须翻回正序：清单序号 附件1 应是最早被保留的那份（f6），而非最新的 f25
        assertThat(rendered.manifest()).contains("| 附件1 | f6.md |");
        assertThat(rendered.manifest()).contains("| 附件20 | f25.md |");
    }

    @Test
    @DisplayName("G-046：截断必须显式声明「共 M 份、只含最新 N 份」——静默截断不可接受")
    void overLimitShouldDeclareTruncationInBothLayers() {
        int total = ChatAttachmentPromptRenderer.MAX_INJECTED_ATTACHMENTS + 3;
        List<ChatAttachment> ascending = attachmentsFrom(total);
        List<ChatAttachment> descending = new ArrayList<>(ascending);
        Collections.reverse(descending);
        when(chatAttachmentService.listByConversation(9L)).thenReturn(ascending);
        when(chatAttachmentService.listLatestByConversation(9L,
                ChatAttachmentPromptRenderer.MAX_INJECTED_ATTACHMENTS))
                .thenReturn(descending.subList(0, ChatAttachmentPromptRenderer.MAX_INJECTED_ATTACHMENTS));

        ChatAttachmentPromptRenderer.Rendered rendered = renderer.render(9L);

        String expected = "本会话共 " + total + " 份附件，此处只含最新的 "
                + ChatAttachmentPromptRenderer.MAX_INJECTED_ATTACHMENTS + " 份";
        // 清单层与正文层各有一份声明：模型无论读哪一段都知道清单不完整
        assertThat(rendered.manifest()).contains(expected).contains("更早的 3 份未注入");
        assertThat(rendered.context()).contains(expected);
    }

    @Test
    @DisplayName("G-046：恰好等于上限时不算截断，不声明、不触发窄查询（行为与改造前一致）")
    void exactlyAtLimitShouldNotDeclareTruncation() {
        int limit = ChatAttachmentPromptRenderer.MAX_INJECTED_ATTACHMENTS;
        when(chatAttachmentService.listByConversation(9L)).thenReturn(attachmentsFrom(limit));

        ChatAttachmentPromptRenderer.Rendered rendered = renderer.render(9L);

        assertThat(rendered.manifest()).doesNotContain("本会话共");
        assertThat(rendered.context()).doesNotContain("本会话共");
        // 未超限不该多花一次查询（绝大多数会话走的就是这条路径）
        verify(chatAttachmentService, never()).listLatestByConversation(anyLong(), anyInt());
    }

    @Test
    @DisplayName("G-046：媒体引用也只从「最新 N 份」里挑（保新必须同时作用于图像通道）")
    void overLimitMediaRefsShouldAlsoComeFromNewest() {
        int total = ChatAttachmentPromptRenderer.MAX_INJECTED_ATTACHMENTS + 2;
        List<ChatAttachment> ascending = new ArrayList<>();
        for (int i = 1; i <= total; i++) {
            ChatAttachment a = attachment("p" + i + ".png", ChatAttachmentParseStatus.SKIPPED, null);
            a.setId((long) i);
            a.setMimeType("image/png");
            ascending.add(a);
        }
        List<ChatAttachment> descending = new ArrayList<>(ascending);
        Collections.reverse(descending);
        when(chatAttachmentService.listByConversation(9L)).thenReturn(ascending);
        when(chatAttachmentService.listLatestByConversation(9L,
                ChatAttachmentPromptRenderer.MAX_INJECTED_ATTACHMENTS))
                .thenReturn(descending.subList(0, ChatAttachmentPromptRenderer.MAX_INJECTED_ATTACHMENTS));

        var rendered = renderer.renderWithMedia(9L, "看这几张图", null, null);

        // 全部都是图片，所以 mediaRefs 的数量就是本轮注入份数
        assertThat(rendered.mediaRefs()).hasSize(ChatAttachmentPromptRenderer.MAX_INJECTED_ATTACHMENTS);
        // 最老的两张（id 1/2）不该出现——那正是原实现会保留的
        assertThat(rendered.mediaRefs())
                .extracting(MediaRef::attachmentId)
                .doesNotContain(1L, 2L)
                .contains(3L, (long) total);
        // ★顺序也是契约：下游按序取前 N 就停，故必须「最新在前」——否则截断落回最旧的
        assertThat(rendered.mediaRefs().get(0).attachmentId()).isEqualTo((long) total);
    }
}
