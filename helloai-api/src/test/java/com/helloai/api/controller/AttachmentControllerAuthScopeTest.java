package com.helloai.api.controller;

import com.helloai.common.base.BizException;
import com.helloai.core.task.entity.Attachment;
import com.helloai.core.task.entity.SubTask;
import com.helloai.core.task.service.AttachmentService;
import com.helloai.core.task.service.SubTaskService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

/**
 * 附件读端归属校验的**通道边界**测试（G-014 T04b）。
 *
 * <p>背景：{@code AuthInterceptor} 对两条通道都会注入 {@code _authId}——
 * 平台账号写 {@code session.id()}（{@code _authType=admin}），Agent 通道写
 * {@code agent.getId()}（{@code _authType=agent}）。因此「{@code _authId == null 即平台账号}」
 * 的判定不成立：平台账号的 {@code _authId} 非空，会被归属校验误伤成 403。</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("AttachmentController 附件读端归属校验（通道边界）")
class AttachmentControllerAuthScopeTest {

    private static final long ADMIN_SESSION_ID = 1L;
    private static final long OWNER_AGENT_ID = 9L;
    private static final long OTHER_AGENT_ID = 7L;
    private static final long ATTACHMENT_ID = 100L;
    private static final long SUB_TASK_ID = 500L;

    @Mock
    private AttachmentService attachmentService;
    @Mock
    private SubTaskService subTaskService;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(new AttachmentController(attachmentService, subTaskService)).build();
    }

    @Test
    @DisplayName("平台账号（_authType=admin，_authId=sessionId）读取任意子任务附件：放行")
    void adminShouldReadAnyAttachment() {
        stubAttachmentAssignedTo(OWNER_AGENT_ID);

        assertDoesNotThrow(() -> mockMvc.perform(
                get("/api/attachments/getById/" + ATTACHMENT_ID)
                        .requestAttr("_authType", "admin")
                        .requestAttr("_authId", ADMIN_SESSION_ID)));
    }

    @Test
    @DisplayName("Agent 读取自己名下子任务附件：放行")
    void ownerAgentShouldReadOwnAttachment() {
        stubAttachmentAssignedTo(OWNER_AGENT_ID);

        assertDoesNotThrow(() -> mockMvc.perform(
                get("/api/attachments/getById/" + ATTACHMENT_ID)
                        .requestAttr("_authType", "agent")
                        .requestAttr("_authId", OWNER_AGENT_ID)));
    }

    @Test
    @DisplayName("Agent 读取他人子任务附件：403 拒绝")
    void nonOwnerAgentShouldBeRejected() {
        stubAttachmentAssignedTo(OWNER_AGENT_ID);

        Throwable root = unwrap(assertThrows(Exception.class, () -> mockMvc.perform(
                get("/api/attachments/getById/" + ATTACHMENT_ID)
                        .requestAttr("_authType", "agent")
                        .requestAttr("_authId", OTHER_AGENT_ID))));
        assertInstanceOf(BizException.class, root);
        assertTrue(root.getMessage().contains("无权访问"), "实际异常：" + root.getMessage());
    }

    private void stubAttachmentAssignedTo(Long assignedAgentId) {
        Attachment attachment = new Attachment();
        attachment.setId(ATTACHMENT_ID);
        attachment.setSubTaskId(SUB_TASK_ID);
        SubTask subTask = new SubTask();
        subTask.setId(SUB_TASK_ID);
        subTask.setAssignedAgentId(assignedAgentId);
        when(attachmentService.getByIdRequired(anyLong())).thenReturn(attachment);
        // lenient：平台账号用例在归属校验前即放行，不会触达 subTaskService；
        // 严格模式下该 stub 会被判 UnnecessaryStubbing（非缺陷，仅用例共用 helper 所致）
        lenient().when(subTaskService.getById(anyLong())).thenReturn(subTask);
    }

    private static Throwable unwrap(Throwable thrown) {
        Throwable current = thrown;
        while (current.getCause() != null && current.getCause() != current) {
            current = current.getCause();
        }
        return current;
    }
}
