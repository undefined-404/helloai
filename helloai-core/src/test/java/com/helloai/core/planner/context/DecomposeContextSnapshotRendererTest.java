package com.helloai.core.planner.context;

import com.helloai.common.constant.ChatAttachmentParseStatus;
import com.helloai.core.knowledge.KnowledgeBaseService;
import com.helloai.core.knowledge.KnowledgeSearchResult;
import com.helloai.core.planner.entity.ChatAttachment;
import com.helloai.core.planner.policy.TaskContextKeys;
import com.helloai.core.planner.service.ChatAttachmentService;
import com.helloai.core.task.port.SubTaskView;
import com.helloai.core.task.port.TaskView;
import com.helloai.core.task.service.SubTaskService;
import com.helloai.core.task.service.TaskService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 拆解期上下文快照渲染器（`TD-6`）。
 *
 * <p>守三条验收口径：① 整段字数不超预算；② 三类空态都不报错且给占位文案；
 * ③ **快照里绝不出现附件正文与知识库全文** —— 这条是本设计最容易被「顺手优化」掉的性质：
 * 一旦有人觉得「摘要不如原文准」而把 `extractedText` 拼进去，提示词会在长附件上被撑爆，
 * 且 Planner 会开始抄历史内容。所以用「把正文塞进桩数据、断言渲染结果不含它」的方式钉死。</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("拆解期上下文快照（TD-6）")
class DecomposeContextSnapshotRendererTest {

    private static final Long TASK_ID = 900L;
    private static final Long CONV_ID = 700L;

    /** 附件正文里的独特标记：只要它出现在渲染结果里，就说明注入了正文。 */
    private static final String BODY_MARKER = "ATTACHMENT-BODY-MUST-NOT-LEAK";

    @Mock
    private ChatAttachmentService chatAttachmentService;

    @Mock
    private KnowledgeBaseService knowledgeBaseService;

    @Mock
    private TaskService taskService;

    @Mock
    private SubTaskService subTaskService;

    private DecomposeContextSnapshotRenderer renderer() {
        return new DecomposeContextSnapshotRenderer(
                chatAttachmentService, knowledgeBaseService, taskService, subTaskService);
    }

    private TaskView task(Long id, String title, String description, Map<String, Object> context) {
        return new TaskView(id, title, description, null, null, null, null, null,
                null, null, null, context, null, null, null, null);
    }

    private SubTaskView subTask(Long id, Long taskId, String title) {
        return new SubTaskView(id, taskId, null, null, null, title, null, null, null, null,
                null, null, List.of(), null, null, null, null, null, null, null, null, null,
                null, null, null);
    }

    private ChatAttachment attachment(String fileName, ChatAttachmentParseStatus status) {
        ChatAttachment a = new ChatAttachment();
        a.setFileName(fileName);
        a.setFileType("xlsx");
        a.setParseStatus(status);
        // 正文存在但绝不能被渲染（断言 ③）
        a.setExtractedText(BODY_MARKER);
        return a;
    }

    private void stubKnowledge(String source, String content) {
        when(knowledgeBaseService.search(anyString(), anyInt(), isNull(), isNull()))
                .thenReturn(new KnowledgeSearchResult("q",
                        List.of(new KnowledgeSearchResult.Hit(source, 0, content)), content.length(), false));
    }

    // ══════════════════════════════════════════════════════════════
    //  ① 预算
    // ══════════════════════════════════════════════════════════════

    @Test
    @DisplayName("预算：三类内容全给满也不超过 MAX_TOTAL_CHARS，且截断有显式标注")
    void snapshotNeverExceedsBudget() {
        TaskView current = task(TASK_ID, "Q3 竞品分析", "分析三家竞品",
                Map.of(TaskContextKeys.CONVERSATION_ID, CONV_ID));
        // 20 件附件、每条文件名 100 字 ⇒ 附件清单本身已逼近预算
        List<ChatAttachment> attachments = new ArrayList<>();
        for (int i = 0; i < 25; i++) {
            attachments.add(attachment("竞品清单" + i + "号".repeat(45) + ".xlsx",
                    ChatAttachmentParseStatus.READY));
        }
        when(chatAttachmentService.listByConversation(CONV_ID)).thenReturn(attachments);
        stubKnowledge("竞品报告2025", "结".repeat(400));
        // 历史任务：标题 200 字 + 每条 6 个子任务标题
        List<TaskView> recent = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            recent.add(task(800L + i, "历史任务" + i + "题".repeat(95), "描述", null));
        }
        when(taskService.listRecentViews(anyInt())).thenReturn(recent);
        when(subTaskService.listViewsByTaskId(anyLong())).thenAnswer(inv -> {
            List<SubTaskView> list = new ArrayList<>();
            for (int i = 0; i < 8; i++) {
                list.add(subTask((long) i, inv.getArgument(0), "子任务标题" + "标".repeat(28) + i));
            }
            return list;
        });

        String rendered = renderer().render(current);

        assertThat(rendered.length())
                .as("整段快照必须落在预算内（含截断提示的少量余量）")
                .isLessThanOrEqualTo(DecomposeContextSnapshotRenderer.MAX_TOTAL_CHARS + 120);
        assertThat(rendered).contains("快照已按预算截断");
    }

    // ══════════════════════════════════════════════════════════════
    //  ② 空态与降级
    // ══════════════════════════════════════════════════════════════

    @Test
    @DisplayName("空态：无会话 / KB 无命中 / 无历史任务 —— 全部给占位文案，且无会话时不去查附件")
    void emptyStatesDegradeToPlaceholders() {
        TaskView current = task(TASK_ID, "整理季度汇报", "把三份周报合成一份", Map.of());
        when(knowledgeBaseService.search(anyString(), anyInt(), isNull(), isNull()))
                .thenReturn(KnowledgeSearchResult.empty("q"));
        when(taskService.listRecentViews(anyInt())).thenReturn(List.of());

        String rendered = renderer().render(current);

        assertThat(rendered)
                .contains("无关联会话")
                .contains("知识库无命中")
                .contains("暂无可参考的历史任务")
                .contains("需求包见上文");
        verify(chatAttachmentService, never()).listByConversation(anyLong());
    }

    @Test
    @DisplayName("降级：单个取数抛异常只让该小节降级，其余小节与三段标题仍在")
    void singleSectionFailureDoesNotBreakOthers() {
        TaskView current = task(TASK_ID, "搭建日报模块", "日报统计",
                Map.of(TaskContextKeys.CONVERSATION_ID, CONV_ID));
        when(chatAttachmentService.listByConversation(CONV_ID)).thenThrow(new IllegalStateException("db down"));
        when(knowledgeBaseService.search(anyString(), anyInt(), isNull(), isNull()))
                .thenThrow(new IllegalStateException("kb down"));
        when(taskService.listRecentViews(anyInt())).thenThrow(new IllegalStateException("task down"));

        String rendered = renderer().render(current);

        assertThat(rendered)
                .contains("附件清单取数失败")
                .contains("知识库检索失败")
                .contains("历史任务取数失败");
        assertThat(rendered).contains("### 附件清单").contains("### 平台知识库命中").contains("### 历史任务的子任务骨架");
    }

    // ══════════════════════════════════════════════════════════════
    //  ③ 只给「有什么」，不给「是什么」
    // ══════════════════════════════════════════════════════════════

    @Test
    @DisplayName("不注入正文：附件只给名称/类型/可读性，KB 只给来源 + ≤100 字摘要")
    void neverLeaksAttachmentBodyOrFullKnowledgeText() {
        TaskView current = task(TASK_ID, "Q3 竞品分析", "分析三家竞品",
                Map.of(TaskContextKeys.CONVERSATION_ID, CONV_ID));
        when(chatAttachmentService.listByConversation(CONV_ID)).thenReturn(
                List.of(attachment("竞品清单.xlsx", ChatAttachmentParseStatus.READY)));
        stubKnowledge("竞品报告2025", "甲".repeat(300));
        when(taskService.listRecentViews(anyInt())).thenReturn(List.of());

        String rendered = renderer().render(current);

        assertThat(rendered).as("附件正文绝不能进快照").doesNotContain(BODY_MARKER);
        assertThat(rendered).as("附件元信息必须进快照")
                .contains("竞品清单.xlsx").contains("xlsx").contains("可读（正文可解析）");
        assertThat(rendered).as("知识库只给来源").contains("竞品报告2025");
        assertThat(rendered).as("KB 摘要必须截断到 ≤100 字")
                .doesNotContain("甲".repeat(DecomposeContextSnapshotRenderer.MAX_KB_HIT_CHARS + 1));
        assertThat(rendered).as("截断要有省略号标记").contains("…");
    }

    @Test
    @DisplayName("历史任务排除自己，只展示更早的任务骨架")
    void historyExcludesCurrentTask() {
        TaskView current = task(TASK_ID, "当前任务标题", "描述", Map.of());
        TaskView earlier = task(800L, "更早的任务", "描述", null);
        when(knowledgeBaseService.search(anyString(), anyInt(), isNull(), isNull()))
                .thenReturn(KnowledgeSearchResult.empty("q"));
        when(taskService.listRecentViews(anyInt())).thenReturn(List.of(current, earlier));
        when(subTaskService.listViewsByTaskId(800L)).thenReturn(List.of(subTask(1L, 800L, "更早任务的子任务")));

        String rendered = renderer().render(current);

        assertThat(rendered).contains("更早的任务").contains("更早任务的子任务");
        assertThat(rendered)
                .as("当前任务不得作为「历史」出现")
                .doesNotContain("当前任务标题");
    }
}
