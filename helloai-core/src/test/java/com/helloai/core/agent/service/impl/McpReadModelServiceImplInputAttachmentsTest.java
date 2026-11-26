package com.helloai.core.agent.service.impl;

import com.helloai.common.constant.SubTaskStatus;
import com.helloai.core.agent.port.AttachmentPort;
import com.helloai.core.agent.port.ConversationMediaRefPort;
import com.helloai.core.agent.port.MediaRef;
import com.helloai.core.agent.port.SubTaskQueryPort;
import com.helloai.core.agent.port.SubTaskSnapshot;
import com.helloai.core.agent.port.TaskRunningSpecPort;
import com.helloai.core.agent.service.AgentExecutionRecordService;
import com.helloai.core.agent.service.McpToolService.SubTaskDetail;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link McpReadModelServiceImpl} 的输入附件清单下发（`G-051`）。
 *
 * <p><b>为什么要单独测这一段</b>：`G-051` 的缺陷不是「工具不认这个 id」，而是
 * <b>模型根本不知道该用哪个 id</b>——真机上它试了 {@code 4}（序号）、{@code taskId}、
 * {@code subTaskId} 共 5 个数字，全部被拒，最后 {@code BLOCKED}。修法的一半在「注入通道能不能读会话附件」，
 * 另一半在「清单有没有下发」；只做前者，模型仍然只能猜。</p>
 *
 * <p><b>判据</b>：① 内部入口下发 {@code inputAttachments}（含图片与文档）；
 * ② 外部入口（{@code getSubTaskDetail}）<b>不下发</b>——外部 Agent 取字节走
 * {@code GET /api/attachments/downloadById}，那条路读的是产物表，给它 chat 附件 id 只会引向死路；
 * ③ 读取失败降级为空列表，不阻断详情；④ {@code attachments}（产物）与 {@code inputAttachments}
 * （输入）<b>互不串味</b>，否则交付物闸会把输入当产出。</p>
 */
@DisplayName("McpReadModelServiceImpl 输入附件清单（G-051）")
class McpReadModelServiceImplInputAttachmentsTest {

    private static final long SUB_TASK_ID = 5005L;
    private static final long TASK_ID = 6006L;
    private static final long AGENT_ID = 7007L;

    private SubTaskQueryPort subTaskQueryPort;
    private TaskRunningSpecPort taskRunningSpecPort;
    private AttachmentPort attachmentPort;
    private ConversationMediaRefPort conversationMediaRefPort;
    private McpReadModelServiceImpl service;

    @BeforeEach
    void setUp() {
        subTaskQueryPort = mock(SubTaskQueryPort.class);
        taskRunningSpecPort = mock(TaskRunningSpecPort.class);
        attachmentPort = mock(AttachmentPort.class);
        conversationMediaRefPort = mock(ConversationMediaRefPort.class);
        AgentExecutionRecordService recordService = mock(AgentExecutionRecordService.class);
        service = new McpReadModelServiceImpl(subTaskQueryPort, taskRunningSpecPort, attachmentPort,
                recordService, conversationMediaRefPort);
        when(subTaskQueryPort.findById(SUB_TASK_ID)).thenReturn(snapshot());
        when(subTaskQueryPort.mergeSkills(SUB_TASK_ID)).thenReturn(List.of());
        when(attachmentPort.listActive(SUB_TASK_ID)).thenReturn(List.of());
    }

    private SubTaskSnapshot snapshot() {
        return new SubTaskSnapshot(SUB_TASK_ID, SubTaskStatus.IN_PROGRESS, TASK_ID, AGENT_ID,
                Map.<String, Object>of("mediaRefIds", List.of(70L, 71L)),
                "取主色", "内容", "交付物", "验收", "约束", "HIGH", 0, null, 0, 0, 1,
                List.of(), List.of());
    }

    @Test
    @DisplayName("内部入口：下发作用域内输入附件（图片 + 数据文件），id 属 chat_attachment")
    void shouldExposeInputAttachmentsForInternalChain() {
        when(conversationMediaRefPort.listScopedInputAttachments(eq(TASK_ID), eq(SUB_TASK_ID), any()))
                .thenReturn(List.of(
                        MediaRef.ofChatAttachment(70L, "vu-red-64.png", "image/png", 1024L),
                        MediaRef.ofChatAttachment(71L, "rows.csv", "text/csv", 2048L)));

        SubTaskDetail detail = service.getSubTaskDetailForInternal(AGENT_ID, SUB_TASK_ID);

        assertThat(detail.getInputAttachments()).hasSize(2);
        assertThat(detail.getInputAttachments().get(0).getAttachmentId()).isEqualTo(70L);
        assertThat(detail.getInputAttachments().get(0).getFileName()).isEqualTo("vu-red-64.png");
        assertThat(detail.getInputAttachments().get(1).getMimeType()).isEqualTo("text/csv");
        // 产物清单不受影响：两个集合语义不同，串味会让交付物闸恒判「有产物」
        assertThat(detail.getAttachments()).isEmpty();
    }

    @Test
    @DisplayName("外部入口：不下发输入附件（外部下载通道只服务产物表，给了也拿不到字节）")
    void shouldNotExposeInputAttachmentsForExternalChain() {
        SubTaskDetail detail = service.getSubTaskDetail(AGENT_ID, SUB_TASK_ID);

        assertThat(detail.getInputAttachments()).isEmpty();
        // 连查都不查：外部入口不该为一份用不上的清单付出一次查询
        verify(conversationMediaRefPort, never()).listScopedInputAttachments(any(), any(), any());
    }

    @Test
    @DisplayName("作用域为空（未声明）⇒ 空列表而非 null：{\\tt []} 与 null 对模型含义不同")
    void shouldReturnEmptyListWhenScopeEmpty() {
        when(conversationMediaRefPort.listScopedInputAttachments(eq(TASK_ID), eq(SUB_TASK_ID), any()))
                .thenReturn(List.of());

        SubTaskDetail detail = service.getSubTaskDetailForInternal(AGENT_ID, SUB_TASK_ID);

        assertThat(detail.getInputAttachments()).isNotNull().isEmpty();
    }

    @Test
    @DisplayName("端口抛异常 ⇒ 降级为空列表，详情其余字段照常下发（可发现性增强不阻断主链）")
    void shouldDegradeOnScopeQueryFailure() {
        when(conversationMediaRefPort.listScopedInputAttachments(eq(TASK_ID), eq(SUB_TASK_ID), any()))
                .thenThrow(new IllegalStateException("db down"));

        SubTaskDetail detail = service.getSubTaskDetailForInternal(AGENT_ID, SUB_TASK_ID);

        assertThat(detail.getInputAttachments()).isEmpty();
        assertThat(detail.getTitle()).isEqualTo("取主色");
        assertThat(detail.getAcceptance()).isEqualTo("验收");
    }

    @Test
    @DisplayName("授权口径与外部入口一致：非归属者不可查（同一段判定，不复制）")
    void shouldKeepSameAuthorization() {
        SubTaskSnapshot other = new SubTaskSnapshot(SUB_TASK_ID, SubTaskStatus.IN_PROGRESS, TASK_ID,
                9999L, Map.of(), "t", "c", "d", "a", "cn", "HIGH", 0, null, 0, 0, 1,
                List.of(), List.of());
        when(subTaskQueryPort.findById(SUB_TASK_ID)).thenReturn(other);

        assertThatThrownBy(() -> service.getSubTaskDetailForInternal(AGENT_ID, SUB_TASK_ID))
                .isInstanceOf(com.helloai.common.base.BizException.class)
                .hasMessageContaining("无权查看该子任务");
    }
}
