package com.helloai.core.planner.service.impl;

import com.helloai.core.agent.port.MediaRef;
import com.helloai.core.planner.assistant.service.ConversationContextStateService;
import com.helloai.core.planner.attachment.ChatAttachmentPromptRenderer;
import com.helloai.core.planner.clarify.ClarifyReplyParser;
import com.helloai.core.planner.clarify.SystemTimeContextBuilder;
import com.helloai.core.planner.entity.ChatAttachment;
import com.helloai.core.planner.entity.RequirementMessage;
import com.helloai.core.planner.memory.service.LongTermMemoryService;
import com.helloai.core.planner.service.ChatAttachmentService;
import com.helloai.core.planner.service.ClarifyCommandSearchService;
import com.helloai.core.planner.service.ClarifyPromptRenderService.RenderedPrompt;
import com.helloai.core.planner.service.RequirementConversationService;
import com.helloai.core.planner.service.RequirementMessageService;
import com.helloai.core.agent.service.PlatformAgentExecutionService;
import com.helloai.core.planner.picker.PlannerAgentPicker;
import com.helloai.core.task.service.TaskTimelineService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link ClarifyPromptRenderServiceImpl} 的「Prompt + 图片引用同批产出」单测（`VU-8` / `VU-9`）。
 *
 * <p><b>这条链此前零覆盖</b>：三个 {@code renderXxxWithMedia} 方法在澄清链上是「模型能不能看到图」
 * 的唯一出口（`ClarifyRoundOrchestratorServiceImpl` 3 处 + `ClarifyStreamServiceImpl` 2 处调用），
 * 但测试里 grep 不到任何命中。缺覆盖的后果不是「少一个用例」，而是这条链**任何一次回归都不会报警**——
 * 图悄悄不送了，用户看到的是「模型说看不到图」，与「模型本来就不支持」在表象上无法区分。</p>
 *
 * <p>覆盖四条判据：①有图时 mediaRefs 带到；②{@code includeAttachment=false} 时<b>不送图</b>
 * （联合决策已判定「本轮不涉及附件」，文本通道不注入却把图送出去等于用另一条通道推翻同一判定）；
 * ③无附件时 mediaRefs 为空表而非 null；④三模板走同一份作用域判定（不各算一套）。</p>
 */
@DisplayName("ClarifyPromptRenderServiceImpl 图片引用（VU-8 澄清链）")
class ClarifyPromptRenderServiceImplMediaTest {

    private static final Long CONVERSATION_ID = 42L;

    private RequirementMessageService messageService;
    private ChatAttachmentService chatAttachmentService;
    private ClarifyPromptRenderServiceImpl renderService;

    @BeforeEach
    void setUp() {
        messageService = mock(RequirementMessageService.class);
        chatAttachmentService = mock(ChatAttachmentService.class);
        // 附件渲染器用真实实现（它是本判据的一半：scope 判定与文本注入必须同源，mock 掉就测不到「同源」）
        ChatAttachmentPromptRenderer attachmentPromptRenderer =
                new ChatAttachmentPromptRenderer(chatAttachmentService);

        renderService = new ClarifyPromptRenderServiceImpl(
                messageService,
                mock(RequirementConversationService.class),
                mock(LongTermMemoryService.class),
                new SystemTimeContextBuilder(),
                mock(TaskTimelineService.class),
                mock(PlatformAgentExecutionService.class),
                mock(PlannerAgentPicker.class),
                new ClarifyReplyParser(new ObjectMapper()),
                mock(ClarifyCommandSearchService.class),
                mock(ConversationContextStateService.class),
                attachmentPromptRenderer);
    }

    @Test
    @DisplayName("① 会话里有图 ⇒ renderClarifyPromptWithMedia 返回该图的引用（只引用不读字节）")
    void shouldCarryImageRefOnClarifyPrompt() {
        when(messageService.listByConversation(CONVERSATION_ID)).thenReturn(userMessages("这张截图是什么？"));
        when(chatAttachmentService.listByConversation(CONVERSATION_ID)).thenReturn(List.of(
                attachment(1L, "shot.png", "image/png"),
                attachment(2L, "spec.pdf", "application/pdf")));

        RenderedPrompt rendered = renderService.renderClarifyPromptWithMedia(CONVERSATION_ID, null, null);

        assertThat(rendered.mediaRefs()).hasSize(1);
        MediaRef ref = rendered.mediaRefs().get(0);
        assertThat(ref.attachmentId()).isEqualTo(1L);
        assertThat(ref.source()).isEqualTo(MediaRef.Source.CHAT_ATTACHMENT);
        // 不变量 I-3：引用不带字节 ⇒ 渲染期绝不读存储
        verify(chatAttachmentService, never()).download(any());
    }

    @Test
    @DisplayName("② includeAttachment=false ⇒ 正文不注入且一张图也不送（同一判定不得只在文本通道生效）")
    void shouldNotCarryMediaWhenAttachmentExcluded() {
        when(messageService.listByConversation(CONVERSATION_ID)).thenReturn(userMessages("今天天气如何？"));
        when(chatAttachmentService.listByConversation(CONVERSATION_ID)).thenReturn(List.of(
                attachment(1L, "shot.png", "image/png")));

        RenderedPrompt rendered = renderService.renderChatPromptWithMedia(
                CONVERSATION_ID, null, false, null, null);

        assertThat(rendered.mediaRefs()).isEmpty();
        // 正文层已渲染为「本轮未注入附件正文」占位 ⇒ 文本与媒体两通道口径一致
        assertThat(rendered.prompt()).contains("本轮未注入附件正文");
    }

    @Test
    @DisplayName("③ includeAttachment=true ⇒ 正文注入且图一并送出（正例，防②写成恒空）")
    void shouldCarryMediaWhenAttachmentIncluded() {
        when(messageService.listByConversation(CONVERSATION_ID)).thenReturn(userMessages("看下这张图"));
        when(chatAttachmentService.listByConversation(CONVERSATION_ID)).thenReturn(List.of(
                attachment(1L, "shot.png", "image/png")));

        RenderedPrompt rendered = renderService.renderChatPromptWithMedia(
                CONVERSATION_ID, null, true, null, null);

        assertThat(rendered.mediaRefs()).hasSize(1);
        assertThat(rendered.prompt()).doesNotContain("本轮未注入附件正文");
    }

    @Test
    @DisplayName("④ 会话无附件 ⇒ mediaRefs 为空表而非 null（调用方无需判空）")
    void shouldReturnEmptyListNotNullWhenNoAttachment() {
        when(messageService.listByConversation(CONVERSATION_ID)).thenReturn(userMessages("你好"));
        when(chatAttachmentService.listByConversation(CONVERSATION_ID)).thenReturn(List.of());

        for (RenderedPrompt rendered : List.of(
                renderService.renderClarifyPromptWithMedia(CONVERSATION_ID, null, null),
                renderService.renderFinalizePromptWithMedia(CONVERSATION_ID, null, null),
                renderService.renderChatPromptWithMedia(CONVERSATION_ID, null, true, null, null))) {
            assertThat(rendered.mediaRefs()).isNotNull().isEmpty();
        }
    }

    @Test
    @DisplayName("⑤ G-044：三个模板吃同一份勾选 ⇒ 勾了第 2 张，三个模板都只送第 2 张（澄清轮不许再丢勾选）")
    void shouldShareScopeAcrossThreeTemplates() {
        when(messageService.listByConversation(CONVERSATION_ID)).thenReturn(userMessages("只看第二张"));
        when(chatAttachmentService.listByConversation(CONVERSATION_ID)).thenReturn(List.of(
                attachment(11L, "a.png", "image/png"),
                attachment(12L, "b.png", "image/png")));

        List<Long> selected = List.of(12L);
        RenderedPrompt chat = renderService.renderChatPromptWithMedia(
                CONVERSATION_ID, null, true, null, selected);
        RenderedPrompt clarify = renderService.renderClarifyPromptWithMedia(CONVERSATION_ID, null, selected);
        RenderedPrompt finalize = renderService.renderFinalizePromptWithMedia(CONVERSATION_ID, null, selected);

        // 三模板必须同结果。修复前 clarify/finalize 走的是 renderPrompt(..., null, null)，
        // 硬编码 null ⇒ 回落全量 ⇒ 这里会拿到 [11, 12]，用户勾选形同虚设。
        assertThat(chat.mediaRefs()).extracting(MediaRef::attachmentId).containsExactly(12L);
        assertThat(clarify.mediaRefs()).extracting(MediaRef::attachmentId).containsExactly(12L);
        assertThat(finalize.mediaRefs()).extracting(MediaRef::attachmentId).containsExactly(12L);
    }

    @Test
    @DisplayName("⑤b G-044：无勾选信号（null）时三模板仍一致回落全量（补参数不能变成恒空）")
    void shouldFallBackToAllWhenNoSelectionOnAnyTemplate() {
        when(messageService.listByConversation(CONVERSATION_ID)).thenReturn(userMessages("随便聊聊"));
        when(chatAttachmentService.listByConversation(CONVERSATION_ID)).thenReturn(List.of(
                attachment(11L, "a.png", "image/png"),
                attachment(12L, "b.png", "image/png")));

        RenderedPrompt chat = renderService.renderChatPromptWithMedia(
                CONVERSATION_ID, null, true, null, null);
        RenderedPrompt clarify = renderService.renderClarifyPromptWithMedia(CONVERSATION_ID, null, null);
        RenderedPrompt finalize = renderService.renderFinalizePromptWithMedia(CONVERSATION_ID, null, null);

        // 本用例验的是「null 不变成恒空」（③ 三模板同口径），与媒体送出顺序无关：
        // `G-046` 起 mediaRefs 按「最新在前」输出（下游按序截断到自己的上限才保新），
        // 所以这里只能断言集合，钉死顺序会把两个正交契约耦合起来。
        assertThat(chat.mediaRefs()).extracting(MediaRef::attachmentId).containsExactlyInAnyOrder(11L, 12L);
        assertThat(clarify.mediaRefs()).extracting(MediaRef::attachmentId).containsExactlyInAnyOrder(11L, 12L);
        assertThat(finalize.mediaRefs()).extracting(MediaRef::attachmentId).containsExactlyInAnyOrder(11L, 12L);
    }

    @Test
    @DisplayName("⑤c G-044/045：澄清与终稿轮传空集 ⇒ 一张图不送（明确排除在全链生效，不只 CHAT）")
    void shouldSendNoMediaOnEmptySelectionForClarifyAndFinalize() {
        when(messageService.listByConversation(CONVERSATION_ID)).thenReturn(userMessages("这轮别管附件"));
        when(chatAttachmentService.listByConversation(CONVERSATION_ID)).thenReturn(List.of(
                attachment(11L, "a.png", "image/png")));

        RenderedPrompt clarify = renderService.renderClarifyPromptWithMedia(CONVERSATION_ID, null, List.of());
        RenderedPrompt finalize = renderService.renderFinalizePromptWithMedia(CONVERSATION_ID, null, List.of());

        assertThat(clarify.mediaRefs()).isEmpty();
        assertThat(finalize.mediaRefs()).isEmpty();
    }

    @Test
    @DisplayName("⑥ 音视频与文档一律不进 mediaRefs（进消息必被只声明 text/image 的模型 400）")
    void shouldExcludeNonImageAttachments() {
        when(messageService.listByConversation(CONVERSATION_ID)).thenReturn(userMessages("看下附件"));
        when(chatAttachmentService.listByConversation(CONVERSATION_ID)).thenReturn(List.of(
                attachment(1L, "a.pdf", "application/pdf"),
                attachment(2L, "b.mp4", "video/mp4"),
                attachment(3L, "c.mp3", "audio/mpeg"),
                attachment(4L, "d.png", "image/png")));

        RenderedPrompt rendered = renderService.renderClarifyPromptWithMedia(CONVERSATION_ID, null, null);

        assertThat(rendered.mediaRefs()).extracting(MediaRef::attachmentId).containsExactly(4L);
    }

    // ---------- 辅助 ----------

    /** 构造「一条用户消息」的会话历史；当前用户输入层会把它抽离出来。 */
    private static List<RequirementMessage> userMessages(String content) {
        RequirementMessage message = new RequirementMessage();
        message.setConversationId(CONVERSATION_ID);
        message.setRole("user");
        message.setContent(content);
        message.setSeq(1);
        List<RequirementMessage> rows = new ArrayList<>();
        rows.add(message);
        return rows;
    }

    private static ChatAttachment attachment(Long id, String fileName, String mimeType) {
        ChatAttachment attachment = new ChatAttachment();
        attachment.setId(id);
        attachment.setConversationId(CONVERSATION_ID);
        attachment.setFileName(fileName);
        attachment.setMimeType(mimeType);
        attachment.setFileSize(100L);
        return attachment;
    }
}