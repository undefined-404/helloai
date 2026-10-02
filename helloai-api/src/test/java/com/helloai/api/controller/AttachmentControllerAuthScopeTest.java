package com.helloai.api.controller;

import com.helloai.common.base.BizException;
import com.helloai.common.constant.AttachmentVisibility;
import com.helloai.core.task.entity.Attachment;
import com.helloai.core.task.entity.SubTask;
import com.helloai.core.task.policy.AttachmentVisibilityPolicy;
import com.helloai.core.task.service.AttachmentService;
import com.helloai.core.task.service.SubTaskService;
import com.helloai.core.task.service.TaskAgentMemberService;
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
 * 附件读端可见性判定的**通道边界 + 范围语义**测试。
 *
 * <p><b>演进史</b>：G-014 T04b 的安全修复是"仅可读自己名下子任务"（sub_task 级硬隔离），
 * 其代价是<b>同任务团队内无法互通产出物</b>。2026-10-02 起放宽为「声明范围（visibility）×
 * 任务成员关系」，判定收口到 {@link AttachmentVisibilityPolicy}。</p>
 *
 * <p>本测试用<b>真实策略 + Mock 依赖</b>（而非 Mock 策略），使策略分支本身也被覆盖：
 * 平台账号放行 / 上传者恒可读自传 / TASK 团队成员可读 / PERSONAL 仅上传者 /
 * PUBLIC 预留未启用（fail-closed）/ 非成员拒绝。</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("AttachmentController 附件读端可见性判定")
class AttachmentControllerAuthScopeTest {

    private static final long ADMIN_SESSION_ID = 1L;
    private static final long UPLOADER_AGENT_ID = 9L;
    private static final long TEAMMATE_AGENT_ID = 7L;
    private static final long OUTSIDER_AGENT_ID = 8L;
    private static final long ATTACHMENT_ID = 100L;
    private static final long SUB_TASK_ID = 500L;
    private static final long TASK_ID = 900L;

    @Mock
    private AttachmentService attachmentService;
    @Mock
    private SubTaskService subTaskService;
    @Mock
    private TaskAgentMemberService taskAgentMemberService;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        AttachmentVisibilityPolicy policy =
                new AttachmentVisibilityPolicy(taskAgentMemberService, subTaskService);
        mockMvc = MockMvcBuilders
                .standaloneSetup(new AttachmentController(attachmentService, policy))
                .build();
    }

    @Test
    @DisplayName("平台账号（_authType=admin，_authId=sessionId）读取任意附件：放行（不走可见性判据）")
    void adminShouldReadAnyAttachment() {
        stubAttachment(AttachmentVisibility.PERSONAL, UPLOADER_AGENT_ID);

        assertDoesNotThrow(() -> mockMvc.perform(
                get("/api/attachments/getById/" + ATTACHMENT_ID)
                        .requestAttr("_authType", "admin")
                        .requestAttr("_authId", ADMIN_SESSION_ID)));
    }

    @Test
    @DisplayName("上传者读取自己上传的 PERSONAL 附件：放行（上传者恒可读自传）")
    void uploaderShouldReadOwnPersonalAttachment() {
        stubAttachment(AttachmentVisibility.PERSONAL, UPLOADER_AGENT_ID);

        assertDoesNotThrow(() -> mockMvc.perform(
                get("/api/attachments/getById/" + ATTACHMENT_ID)
                        .requestAttr("_authType", "agent")
                        .requestAttr("_authId", UPLOADER_AGENT_ID)));
    }

    @Test
    @DisplayName("同任务的团队成员读取 TASK 附件：放行（本次改造的核心目的——团队产出互通）")
    void teammateShouldReadTaskScopedAttachment() {
        stubAttachment(AttachmentVisibility.TASK, UPLOADER_AGENT_ID);
        stubMembership(TEAMMATE_AGENT_ID, true);

        assertDoesNotThrow(() -> mockMvc.perform(
                get("/api/attachments/getById/" + ATTACHMENT_ID)
                        .requestAttr("_authType", "agent")
                        .requestAttr("_authId", TEAMMATE_AGENT_ID)));
    }

    @Test
    @DisplayName("非团队成员读取 TASK 附件：403 拒绝（边界仍是任务级，不得跨任务读）")
    void outsiderShouldBeRejectedForTaskScopedAttachment() {
        stubAttachment(AttachmentVisibility.TASK, UPLOADER_AGENT_ID);
        stubMembership(OUTSIDER_AGENT_ID, false);

        Throwable root = unwrap(assertThrows(Exception.class, () -> mockMvc.perform(
                get("/api/attachments/getById/" + ATTACHMENT_ID)
                        .requestAttr("_authType", "agent")
                        .requestAttr("_authId", OUTSIDER_AGENT_ID))));
        assertInstanceOf(BizException.class, root);
        assertTrue(root.getMessage().contains("无权访问"), "实际异常：" + root.getMessage());
    }

    @Test
    @DisplayName("同任务团队成员读取 PERSONAL 附件：403 拒绝（仅上传者，团队身份不能越权）")
    void teammateShouldBeRejectedForPersonalAttachment() {
        // 即便团队判定为真，PERSONAL 分支也应在上传者判定之后直接拒绝（不触达成员查询）
        stubAttachment(AttachmentVisibility.PERSONAL, UPLOADER_AGENT_ID);
        lenient().when(taskAgentMemberService.isMember(TASK_ID, TEAMMATE_AGENT_ID)).thenReturn(true);
        lenient().when(subTaskService.getById(SUB_TASK_ID)).thenReturn(taskIdBackedSubTask());

        Throwable root = unwrap(assertThrows(Exception.class, () -> mockMvc.perform(
                get("/api/attachments/getById/" + ATTACHMENT_ID)
                        .requestAttr("_authType", "agent")
                        .requestAttr("_authId", TEAMMATE_AGENT_ID))));
        assertInstanceOf(BizException.class, root);
    }

    @Test
    @DisplayName("PUBLIC 附件：fail-closed 拒绝（枚举仅预留，未开放设置入口，不得隐式全局可读）")
    void publicVisibilityShouldFailClosed() {
        stubAttachment(AttachmentVisibility.PUBLIC, UPLOADER_AGENT_ID);

        Throwable root = unwrap(assertThrows(Exception.class, () -> mockMvc.perform(
                get("/api/attachments/getById/" + ATTACHMENT_ID)
                        .requestAttr("_authType", "agent")
                        .requestAttr("_authId", TEAMMATE_AGENT_ID))));
        assertInstanceOf(BizException.class, root);
    }

    @Test
    @DisplayName("成员表未命中但权威源命中（运行期新分配尚未入队）：derive-on-miss 兜底放行")
    void currentExecutorShouldPassViaAuthoritativeFallback() {
        stubAttachment(AttachmentVisibility.TASK, UPLOADER_AGENT_ID);
        lenient().when(subTaskService.getById(SUB_TASK_ID)).thenReturn(taskIdBackedSubTask());
        // 成员表未命中（事件驱动派生可能漏路径 / 尚未入队）
        when(taskAgentMemberService.isMember(TASK_ID, TEAMMATE_AGENT_ID)).thenReturn(false);
        // 权威源命中：该 agent 当前正是本任务某子任务的执行者
        when(taskAgentMemberService.isCurrentExecutorOfTask(TASK_ID, TEAMMATE_AGENT_ID)).thenReturn(true);

        assertDoesNotThrow(() -> mockMvc.perform(
                get("/api/attachments/getById/" + ATTACHMENT_ID)
                        .requestAttr("_authType", "agent")
                        .requestAttr("_authId", TEAMMATE_AGENT_ID)));
    }

    @Test
    @DisplayName("成员表未命中且权威源亦未命中：403 拒绝（兜底不放宽边界）")
    void outsiderShouldBeRejectedWhenNeitherSourceHits() {
        stubAttachment(AttachmentVisibility.TASK, UPLOADER_AGENT_ID);
        stubMembership(OUTSIDER_AGENT_ID, false);
        // 显式声明兜底也未命中，避免依赖 Mockito 默认返回值而掩盖真实判定路径
        when(taskAgentMemberService.isCurrentExecutorOfTask(TASK_ID, OUTSIDER_AGENT_ID)).thenReturn(false);

        Throwable root = unwrap(assertThrows(Exception.class, () -> mockMvc.perform(
                get("/api/attachments/getById/" + ATTACHMENT_ID)
                        .requestAttr("_authType", "agent")
                        .requestAttr("_authId", OUTSIDER_AGENT_ID))));
        assertInstanceOf(BizException.class, root);
    }

    // ==================== helpers ====================

    private void stubAttachment(AttachmentVisibility visibility, Long uploaderAgentId) {
        Attachment attachment = new Attachment();
        attachment.setId(ATTACHMENT_ID);
        attachment.setSubTaskId(SUB_TASK_ID);
        attachment.setVisibility(visibility);
        attachment.setUploaderAgentId(uploaderAgentId);
        when(attachmentService.getByIdRequired(anyLong())).thenReturn(attachment);
    }

    private void stubMembership(Long agentId, boolean member) {
        lenient().when(subTaskService.getById(SUB_TASK_ID)).thenReturn(taskIdBackedSubTask());
        when(taskAgentMemberService.isMember(TASK_ID, agentId)).thenReturn(member);
    }

    private static SubTask taskIdBackedSubTask() {
        SubTask subTask = new SubTask();
        subTask.setId(SUB_TASK_ID);
        subTask.setTaskId(TASK_ID);
        return subTask;
    }

    private static Throwable unwrap(Throwable thrown) {
        Throwable current = thrown;
        while (current.getCause() != null && current.getCause() != current) {
            current = current.getCause();
        }
        return current;
    }
}
