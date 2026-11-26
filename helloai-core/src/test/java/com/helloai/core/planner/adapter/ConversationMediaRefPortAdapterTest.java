package com.helloai.core.planner.adapter;

import com.helloai.common.constant.SubTaskContextKeys;
import com.helloai.core.agent.port.ConversationMediaRefPort.ScopedMediaRefs;
import com.helloai.core.agent.port.MediaRef;
import com.helloai.core.planner.entity.ChatAttachment;
import com.helloai.core.planner.policy.TaskContextKeys;
import com.helloai.core.planner.service.ChatAttachmentService;
import com.helloai.core.task.port.TaskView;
import com.helloai.core.task.service.TaskService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * {@link ConversationMediaRefPortAdapter} 单测（`VU-8` + `G-042`）。
 *
 * <p>`VU-8` 四条判据：① task → conversation 的解析只在提供方发生（消费方不认识 context 键）；
 * ② 只挑图片、不挑文档 / 音视频；③ 任何异常降级为空表而非抛出；④ 引用不带字节（不读存储）。</p>
 *
 * <p>`G-042` 三条判据：⑤ 本子任务声明了附件 ⇒ 只送那几张（`scoped=true`，不带回落说明）；
 * ⑥ 未声明 / 声明全部无效 ⇒ 回落会话全量**并给出回落原因**（模型必须知道拿到的是全量）；
 * ⑦ 声明解析对坏元素逐条防御（不因一个坏元素放弃整份声明）。</p>
 */
@DisplayName("ConversationMediaRefPortAdapter（会话图片引用 + G-042 作用域）")
class ConversationMediaRefPortAdapterTest {

    private TaskService taskService;
    private ChatAttachmentService chatAttachmentService;
    private ConversationMediaRefPortAdapter adapter;

    @BeforeEach
    void setUp() {
        taskService = mock(TaskService.class);
        chatAttachmentService = mock(ChatAttachmentService.class);
        adapter = new ConversationMediaRefPortAdapter(taskService, chatAttachmentService);
    }

    // ── `VU-8` 基础口径（作用域未声明时的原有行为） ────────────────────────

    @Test
    @DisplayName("task.context.conversationId 命中 ⇒ 按会话列出图片引用（只存引用，不读字节）")
    void shouldListImageRefsByConversation() {
        when(taskService.getView(7L)).thenReturn(taskView(Map.of(TaskContextKeys.CONVERSATION_ID, 42L)));
        when(chatAttachmentService.listByConversation(42L)).thenReturn(List.of(
                attachment(1L, "shot.png", "image/png"),
                attachment(2L, "spec.pdf", "application/pdf")));

        ScopedMediaRefs result = adapter.listScopedImages(7L, 11L, null);

        assertThat(result.refs()).hasSize(1);
        MediaRef ref = result.refs().get(0);
        assertThat(ref.attachmentId()).isEqualTo(1L);
        assertThat(ref.source()).isEqualTo(MediaRef.Source.CHAT_ATTACHMENT);
        assertThat(ref.fileName()).isEqualTo("shot.png");
        // 只存引用：不读存储（I-3「存储只存引用、字节后读」）
        verify(chatAttachmentService, never()).download(1L);
    }

    @Test
    @DisplayName("文档与音视频都不列：文档正文由拆解期快照承载，音视频进消息必被 provider 400")
    void shouldSkipNonImageAttachments() {
        when(taskService.getView(7L)).thenReturn(taskView(Map.of(TaskContextKeys.CONVERSATION_ID, 42L)));
        when(chatAttachmentService.listByConversation(42L)).thenReturn(List.of(
                attachment(1L, "a.pdf", "application/pdf"),
                attachment(2L, "b.docx", "application/vnd.openxmlformats-officedocument.wordprocessingml.document"),
                attachment(3L, "c.mp4", "video/mp4"),
                attachment(4L, "d.mp3", "audio/mpeg")));

        assertThat(adapter.listScopedImages(7L, 11L, null).refs()).isEmpty();
    }

    @Test
    @DisplayName("MIME 缺失 / octet-stream：按扩展名兜底判为图片（浏览器对 .png 常给 octet-stream）")
    void shouldFallbackToExtensionWhenMimeMissing() {
        when(taskService.getView(7L)).thenReturn(taskView(Map.of(TaskContextKeys.CONVERSATION_ID, 42L)));
        ChatAttachment octet = attachment(1L, "SHOT.PNG", "application/octet-stream");
        ChatAttachment noMime = attachment(2L, "photo.jpeg", null);
        when(chatAttachmentService.listByConversation(42L)).thenReturn(List.of(octet, noMime));

        assertThat(adapter.listScopedImages(7L, 11L, null).refs()).hasSize(2);
    }

    @Test
    @DisplayName("G-046：多图时必须「最新在前」——下游按序取前 5 就停，正序输出会送出最旧 5 张")
    void shouldReturnNewestFirstForDownstreamTruncation() {
        when(taskService.getView(7L)).thenReturn(taskView(Map.of(TaskContextKeys.CONVERSATION_ID, 42L)));
        // 服务端按上传顺序（ASC）返回：id 1 最老、id 8 最新
        List<ChatAttachment> ascending = new ArrayList<>();
        for (long id = 1; id <= 8; id++) {
            ascending.add(attachment(id, "p" + id + ".png", "image/png"));
        }
        when(chatAttachmentService.listByConversation(42L)).thenReturn(ascending);

        List<MediaRef> refs = adapter.listScopedImages(7L, 11L, null).refs();

        assertThat(refs).hasSize(8);
        // 下游 AttachmentMediaAssembler 取前 5 ⇒ 前 5 条必须是最新的 5 张（id 8..4）
        assertThat(refs.subList(0, 5)).extracting(MediaRef::attachmentId)
                .containsExactly(8L, 7L, 6L, 5L, 4L);
        // 最老的两张排在末尾（会被丢弃的那一端）
        assertThat(refs.get(7).attachmentId()).isEqualTo(1L);
    }

    @Test
    @DisplayName("任务无 conversationId 键（历史任务）：返回空表，不查附件、不报错")
    void shouldReturnEmptyWhenNoConversationId() {
        when(taskService.getView(7L)).thenReturn(taskView(Map.of("other", "x")));

        assertThat(adapter.listScopedImages(7L, 11L, null).refs()).isEmpty();
        verifyNoInteractions(chatAttachmentService);
    }

    @Test
    @DisplayName("conversationId 是字符串数字（JSONB 反序列化常见形态）：同样解析成功")
    void shouldParseStringConversationId() {
        when(taskService.getView(7L)).thenReturn(taskView(Map.of(TaskContextKeys.CONVERSATION_ID, "42")));
        when(chatAttachmentService.listByConversation(42L)).thenReturn(List.of(
                attachment(1L, "shot.png", "image/png")));

        assertThat(adapter.listScopedImages(7L, 11L, null).refs()).hasSize(1);
    }

    @Test
    @DisplayName("conversationId 是非法字符串：按缺失处理（返回空表），不抛 NumberFormatException")
    void shouldTolerateIllegalConversationId() {
        when(taskService.getView(7L)).thenReturn(taskView(Map.of(TaskContextKeys.CONVERSATION_ID, "abc")));

        assertThat(adapter.listScopedImages(7L, 11L, null).refs()).isEmpty();
        verifyNoInteractions(chatAttachmentService);
    }

    @Test
    @DisplayName("taskId 为 null / 任务不存在 / context 为 null：一律空表（媒体是增强不是必要条件）")
    void shouldReturnEmptyForMissingTask() {
        assertThat(adapter.listScopedImages(null, 11L, null).refs()).isEmpty();
        verifyNoInteractions(taskService);

        when(taskService.getView(8L)).thenReturn(null);
        assertThat(adapter.listScopedImages(8L, 11L, null).refs()).isEmpty();

        when(taskService.getView(9L)).thenReturn(taskView(null));
        assertThat(adapter.listScopedImages(9L, 11L, null).refs()).isEmpty();
    }

    @Test
    @DisplayName("查询异常（存储/DB 故障）：吞掉并返回空表，绝不让执行链因此失败")
    void shouldDegradeOnException() {
        when(taskService.getView(7L)).thenReturn(taskView(Map.of(TaskContextKeys.CONVERSATION_ID, 42L)));
        when(chatAttachmentService.listByConversation(42L)).thenThrow(new IllegalStateException("DB down"));

        ScopedMediaRefs result = adapter.listScopedImages(7L, 11L, null);

        assertThat(result.refs()).isEmpty();
        // 异常路径不产生回落说明：此时连「会话里有几张图」都不知道，编一句数字就是假信息
        assertThat(result.fallbackNote()).isNull();
    }

    // ── `G-042` 作用域：有声明只送声明的那几张 ────────────────────────────

    @Test
    @DisplayName("G-042①：本子任务声明了附件 ⇒ 只送那几张，scoped=true 且无回落说明")
    void shouldReturnOnlyDeclaredImages() {
        when(taskService.getView(7L)).thenReturn(taskView(Map.of(TaskContextKeys.CONVERSATION_ID, 42L)));
        when(chatAttachmentService.listByConversation(42L)).thenReturn(List.of(
                attachment(1L, "a.png", "image/png"),
                attachment(2L, "b.png", "image/png"),
                attachment(3L, "c.png", "image/png")));

        Map<String, Object> ctx = contextWithRefs(1L, 3L);
        ScopedMediaRefs result = adapter.listScopedImages(7L, 11L, ctx);

        assertThat(result.scoped()).isTrue();
        assertThat(result.fallbackNote()).isNull();
        // 只送声明的 1 与 3；未声明的 2 不得出现（输出按上传时间倒序 ⇒ 3 在 1 前）
        assertThat(result.refs()).extracting(MediaRef::attachmentId).containsExactly(3L, 1L);
    }

    @Test
    @DisplayName("G-042②：声明里混入非图片（PDF）⇒ 只命中其中的图片，仍算 scoped")
    void shouldIgnoreNonImageWhenDeclared() {
        when(taskService.getView(7L)).thenReturn(taskView(Map.of(TaskContextKeys.CONVERSATION_ID, 42L)));
        when(chatAttachmentService.listByConversation(42L)).thenReturn(List.of(
                attachment(1L, "a.pdf", "application/pdf"),
                attachment(2L, "b.png", "image/png")));

        ScopedMediaRefs result = adapter.listScopedImages(7L, 11L, contextWithRefs(1L, 2L));

        assertThat(result.scoped()).isTrue();
        assertThat(result.refs()).extracting(MediaRef::attachmentId).containsExactly(2L);
    }

    // ── `G-042` 作用域：无声明 / 无效声明 ⇒ 回落 + 必须给出原因 ──────────────

    @Test
    @DisplayName("G-042③：子任务未声明附件 ⇒ 回落会话全量，且必须带回落说明（scoped=false 且有 note）")
    void shouldFallbackWithNoteWhenNoDeclaration() {
        when(taskService.getView(7L)).thenReturn(taskView(Map.of(TaskContextKeys.CONVERSATION_ID, 42L)));
        when(chatAttachmentService.listByConversation(42L)).thenReturn(List.of(
                attachment(1L, "a.png", "image/png"),
                attachment(2L, "b.png", "image/png")));

        ScopedMediaRefs result = adapter.listScopedImages(7L, 11L, Map.of("plannerAgentId", 1L));

        assertThat(result.scoped()).isFalse();
        assertThat(result.refs()).hasSize(2);
        // ★说明不能为 null：模型必须知道拿到的是「全量」而不是「本子任务相关的几张」
        assertThat(result.fallbackNote()).isNotNull();
        assertThat(result.fallbackNote()).contains("未声明");
    }

    @Test
    @DisplayName("G-042④：声明了但一张都匹配不到（附件已删）⇒ 回落 + 说明要点明「原定的已不可读」")
    void shouldFallbackWithDistinctNoteWhenDeclaredAllMissing() {
        when(taskService.getView(7L)).thenReturn(taskView(Map.of(TaskContextKeys.CONVERSATION_ID, 42L)));
        when(chatAttachmentService.listByConversation(42L)).thenReturn(List.of(
                attachment(1L, "a.png", "image/png")));

        ScopedMediaRefs result = adapter.listScopedImages(7L, 11L, contextWithRefs(99L));

        assertThat(result.scoped()).isFalse();
        assertThat(result.refs()).hasSize(1);
        assertThat(result.fallbackNote()).isNotNull();
        // 两种回落原因必须可区分：这里不是「没声明」，而是「声明了但都读不到」
        assertThat(result.fallbackNote()).contains("已不可读");
        assertThat(result.fallbackNote()).doesNotContain("未声明");
    }

    @Test
    @DisplayName("G-042⑤：声明解析对坏元素逐条防御——字符串数字可用、非法元素跳过、不整体放弃")
    void shouldTolerateBadElementsInDeclaration() {
        when(taskService.getView(7L)).thenReturn(taskView(Map.of(TaskContextKeys.CONVERSATION_ID, 42L)));
        when(chatAttachmentService.listByConversation(42L)).thenReturn(List.of(
                attachment(1L, "a.png", "image/png"),
                attachment(2L, "b.png", "image/png")));

        // 混合形态：字符串数字 "2" 应解析成功；"x" / null 应被跳过；1 也可用
        Map<String, Object> ctx = new HashMap<>();
        ctx.put(SubTaskContextKeys.MEDIA_REF_IDS, Arrays.asList("2", "x", null, 1L));

        ScopedMediaRefs result = adapter.listScopedImages(7L, 11L, ctx);

        assertThat(result.scoped()).isTrue();
        // 输出恒为「最新在前」：上传顺序 1,2 ⇒ 翻转成 2,1
        assertThat(result.refs()).extracting(MediaRef::attachmentId).containsExactly(2L, 1L);
    }

    /**
     * ★这条钉死的是「声明只决定<b>选哪些</b>，不决定<b>排序</b>」。
     *
     * <p>若实现按声明顺序输出再整体翻转，声明 {@code [1,3]} 会得到 {@code [1,3]}（翻转后仍别扭），
     * 而 {@code G-046} 要求的「最新优先」彻底失效——超限时被丢掉的可能是最新的那张。
     * 这类缺陷不会报错，只在「声明顺序恰好与上传顺序相反」时静默送错，故必须单独钉。</p>
     */
    @Test
    @DisplayName("G-042⑨：声明顺序不影响输出顺序——输出恒按上传时间「最新在前」")
    void shouldIgnoreDeclarationOrderAndKeepNewestFirst() {
        when(taskService.getView(7L)).thenReturn(taskView(Map.of(TaskContextKeys.CONVERSATION_ID, 42L)));
        when(chatAttachmentService.listByConversation(42L)).thenReturn(List.of(
                attachment(1L, "a.png", "image/png"),
                attachment(2L, "b.png", "image/png"),
                attachment(3L, "c.png", "image/png"),
                attachment(4L, "d.png", "image/png")));

        // 声明故意写成「老 → 新」；输出仍必须是「新 → 老」
        ScopedMediaRefs result = adapter.listScopedImages(7L, 11L, contextWithRefs(1L, 3L));

        assertThat(result.refs()).extracting(MediaRef::attachmentId).containsExactly(3L, 1L);
        // 未被声明的 2、4 一张都不许出现
        assertThat(result.refs()).extracting(MediaRef::attachmentId).doesNotContain(2L, 4L);
    }

    @Test
    @DisplayName("G-042⑥：声明写成标量（历史/异常数据）也能解析，不抛 ClassCastException")
    void shouldTolerateScalarDeclaration() {
        when(taskService.getView(7L)).thenReturn(taskView(Map.of(TaskContextKeys.CONVERSATION_ID, 42L)));
        when(chatAttachmentService.listByConversation(42L)).thenReturn(List.of(
                attachment(1L, "a.png", "image/png"),
                attachment(2L, "b.png", "image/png")));

        Map<String, Object> ctx = new HashMap<>();
        ctx.put(SubTaskContextKeys.MEDIA_REF_IDS, 2L);

        ScopedMediaRefs result = adapter.listScopedImages(7L, 11L, ctx);

        assertThat(result.scoped()).isTrue();
        assertThat(result.refs()).extracting(MediaRef::attachmentId).containsExactly(2L);
    }

    @Test
    @DisplayName("G-042⑦：声明键存在但值为空数组 ⇒ 按「未声明」回落（并说明「无法解析」而非「未声明」）")
    void shouldFallbackWhenDeclarationIsEmpty() {
        when(taskService.getView(7L)).thenReturn(taskView(Map.of(TaskContextKeys.CONVERSATION_ID, 42L)));
        when(chatAttachmentService.listByConversation(42L)).thenReturn(List.of(
                attachment(1L, "a.png", "image/png")));

        Map<String, Object> ctx = new HashMap<>();
        ctx.put(SubTaskContextKeys.MEDIA_REF_IDS, List.of());

        ScopedMediaRefs result = adapter.listScopedImages(7L, 11L, ctx);

        assertThat(result.scoped()).isFalse();
        assertThat(result.refs()).hasSize(1);
        assertThat(result.fallbackNote()).isNotNull();
        // 键在但解析不出 ID ⇒ 属于「无法解析」，不应说成「拆解时未声明」
        assertThat(result.fallbackNote()).contains("无法解析");
    }

    @Test
    @DisplayName("G-042⑧：会话本身无图 ⇒ 空表且无说明（没有图就没有「回落」可言，不制造噪声）")
    void shouldReturnEmptyWithoutNoteWhenConversationHasNoImage() {
        when(taskService.getView(7L)).thenReturn(taskView(Map.of(TaskContextKeys.CONVERSATION_ID, 42L)));
        when(chatAttachmentService.listByConversation(42L)).thenReturn(List.of(
                attachment(1L, "a.pdf", "application/pdf")));

        ScopedMediaRefs result = adapter.listScopedImages(7L, 11L, null);

        assertThat(result.refs()).isEmpty();
        assertThat(result.fallbackNote()).isNull();
    }

    // ── `VU-8` 判据③：会话删除后该通道不再命中 ─────────────────────────────

    /**
     * 判据③「会话删除后该通道不再命中（不产生悬空引用）」。
     *
     * <p><b>缺口历史</b>：本条最初写的是「断言当前行为」（会话已软删但其附件行仍是
     * `deleted=0` ⇒ 本通道照旧命中），因为当时的 `RequirementClarifyServiceImpl.delete`
     * 只删会话与消息、不碰 `chat_attachment`。缺口立账为 `G-041`。</p>
     *
     * <p><b>2026-11-25 真机闭合</b>：`G-041` 落地后，删除会话（conversation=2122155243474427905，
     * 含 1 条活附件）实测——`chat_attachment` 该会话行被<b>物理删除</b>（`deleteByConversation`
     * 走自定义 `@Delete`，绕开 `@TableLogic`），MinIO 对象同步回收（`vu8-test-red` 命中 0）。
     * 断言随之从「仍命中」改为「空集合」。本用例现在钉的是最终语义：<b>只要删除链路执行了
     * 附件清理，执行面取图通道就自然返回空</b>，不会再出现「会话没了、图还在送」。</p>
     *
     * <p><b>注意</b>：本用例的桩把「会话侧已无该附件行」模拟为服务层返回空列表——
     * 这是删除后的<b>真实库状态</b>（行已物理删，查不到），而不是绕过删除逻辑直接断言空。
     * 若未来删除链路回归为「只软删行」，那属于 `ChatAttachmentServiceImplTest` 的
     * `deleteMustBePhysicalNotLogical` 等用例的职责，本用例只管通道侧行为。</p>
     */
    @Test
    @DisplayName("★判据③已闭合（G-041 真机验收）：会话删除后附件行被物理清理 ⇒ 本通道返回空集合")
    void shouldNotHitWhenConversationDeleted() {
        // 删除后的真实库状态：会话已删，其附件行已被 deleteByConversation 物理清除，
        // 服务层按 conversationId 查不到任何行 ⇒ 适配器得空表
        when(taskService.getView(7L)).thenReturn(taskView(Map.of(TaskContextKeys.CONVERSATION_ID, 42L)));
        when(chatAttachmentService.listByConversation(42L)).thenReturn(List.of());

        ScopedMediaRefs result = adapter.listScopedImages(7L, 11L, null);

        assertThat(result.refs()).isEmpty();
        assertThat(result.fallbackNote()).isNull();
    }

    @Test
    @DisplayName("附件自身被软删（deleted=1）⇒ 服务层不返回 ⇒ 本通道不命中（这一层是好的）")
    void shouldNotHitWhenAttachmentItselfDeleted() {
        when(taskService.getView(7L)).thenReturn(taskView(Map.of(TaskContextKeys.CONVERSATION_ID, 42L)));
        // 软删语义由 Mapper 的 `AND deleted = 0` 实现，服务层对已删行返回空表
        when(chatAttachmentService.listByConversation(42L)).thenReturn(List.of());

        assertThat(adapter.listScopedImages(7L, 11L, null).refs()).isEmpty();
    }

    // ---------- `G-051` 输入附件作用域（进沙箱通道） ----------

    @Test
    @DisplayName("G-051：声明命中 ⇒ 返回全部类型（图片 + 文档 + 数据文件），保持上传正序")
    void shouldListScopedInputAttachmentsOfAnyType() {
        when(taskService.getView(7L)).thenReturn(taskView(Map.of(TaskContextKeys.CONVERSATION_ID, 42L)));
        when(chatAttachmentService.listByConversation(42L)).thenReturn(List.of(
                attachment(1L, "notes.txt", "text/plain"),
                attachment(2L, "vu-red-64.png", "image/png"),
                attachment(3L, "rows.csv", "text/csv")));

        List<MediaRef> refs = adapter.listScopedInputAttachments(7L, 11L, contextWithRefs(1L, 3L));

        // 与视觉通道相反：这里不过滤类型（沙箱要执行的是任意文件）
        assertThat(refs).extracting(MediaRef::attachmentId).containsExactly(1L, 3L);
        assertThat(refs).extracting(MediaRef::fileName).containsExactly("notes.txt", "rows.csv");
        assertThat(refs).allSatisfy(ref ->
                assertThat(ref.source()).isEqualTo(MediaRef.Source.CHAT_ATTACHMENT));
        // 只存引用不读字节（I-3）
        verify(chatAttachmentService, never()).download(1L);
    }

    @Test
    @DisplayName("G-051：未声明作用域 ⇒ 空表且**一次库都不查**（fail-close，绝不回落会话全量）")
    void shouldReturnEmptyWithoutQueryWhenScopeNotDeclared() {
        when(taskService.getView(7L)).thenReturn(taskView(Map.of(TaskContextKeys.CONVERSATION_ID, 42L)));

        assertThat(adapter.listScopedInputAttachments(7L, 11L, null)).isEmpty();
        assertThat(adapter.listScopedInputAttachments(7L, 11L, Map.of())).isEmpty();
        assertThat(adapter.listScopedInputAttachments(7L, 11L, contextWithRefs())).isEmpty();
        // ★断言「未声明」不等于「回落全量」：会话里明明有文件，也不能被注入
        verify(chatAttachmentService, never()).listByConversation(42L);
    }

    @Test
    @DisplayName("G-051：声明全部失效（附件已删）⇒ 空表，不回落全量")
    void shouldReturnEmptyWhenDeclaredScopeAllStale() {
        when(taskService.getView(7L)).thenReturn(taskView(Map.of(TaskContextKeys.CONVERSATION_ID, 42L)));
        when(chatAttachmentService.listByConversation(42L)).thenReturn(List.of(
                attachment(1L, "alive.txt", "text/plain")));

        assertThat(adapter.listScopedInputAttachments(7L, 11L, contextWithRefs(999L))).isEmpty();
    }

    @Test
    @DisplayName("G-051：任务未关联会话 ⇒ 空表（历史任务常态，不抛异常）")
    void shouldReturnEmptyWhenTaskHasNoConversation() {
        when(taskService.getView(7L)).thenReturn(taskView(Map.of()));

        assertThat(adapter.listScopedInputAttachments(7L, 11L, contextWithRefs(1L))).isEmpty();
        verifyNoInteractions(chatAttachmentService);
    }

    @Test
    @DisplayName("G-051：查询抛异常 ⇒ 降级为空表（对注入侧等价于拒绝，不误放行）")
    void shouldDegradeToEmptyOnQueryFailure() {
        when(taskService.getView(7L)).thenReturn(taskView(Map.of(TaskContextKeys.CONVERSATION_ID, 42L)));
        when(chatAttachmentService.listByConversation(42L)).thenThrow(new IllegalStateException("db down"));

        assertThat(adapter.listScopedInputAttachments(7L, 11L, contextWithRefs(1L))).isEmpty();
    }

    // ---------- 辅助 ----------

    private static Map<String, Object> contextWithRefs(Long... ids) {
        Map<String, Object> ctx = new HashMap<>();
        ctx.put(SubTaskContextKeys.MEDIA_REF_IDS, Arrays.asList(ids));
        return ctx;
    }

    private static TaskView taskView(Map<String, Object> context) {
        return new TaskView(7L, "t", "d", null, null, null, null, null,
                null, null, null, context, null, null, null, null, null, null);
    }

    private static ChatAttachment attachment(Long id, String fileName, String mimeType) {
        ChatAttachment attachment = new ChatAttachment();
        attachment.setId(id);
        attachment.setFileName(fileName);
        attachment.setMimeType(mimeType);
        attachment.setFileSize(100L);
        return attachment;
    }
}