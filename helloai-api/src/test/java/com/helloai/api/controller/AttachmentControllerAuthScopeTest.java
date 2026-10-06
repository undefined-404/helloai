package com.helloai.api.controller;

import com.helloai.common.base.BizException;
import com.helloai.common.constant.AttachmentStatus;
import com.helloai.core.task.entity.Attachment;
import com.helloai.core.task.service.AttachmentService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;

/**
 * {@link AttachmentController} 的<b>通道边界 + 响应投影</b>测试。
 *
 * <p><b>职责边界（2026-10-03 重构后）</b>：可见性判据的<b>分支语义</b>
 * （上传者恒可读 / PERSONAL / PUBLIC fail-closed / TASK 成员关系 / derive-on-miss）
 * 由 {@code helloai-core} 的 {@code AttachmentVisibilityPolicyTest} 直接覆盖（§47.1 明确列
 * Policy 为单测对象）。本类只验证 Controller 自身的两件事：</p>
 * <ol>
 *     <li><b>通道语义</b>：平台账号（{@code _authType=admin}）不做可见性判定直接放行；
 *         Agent 通道（{@code _authType=agent}）必须调用
 *         {@link AttachmentService#assertReadable} / {@code listReadable} —— 判据收口在
 *         Service，Controller 不再直接依赖 {@code task.policy}；</li>
 *     <li><b>响应投影（§11.3）</b>：响应体是 {@code AttachmentVO}，<b>不泄露</b>实体内部字段
 *         （{@code deleted}/{@code createBy}/{@code visibility}/{@code uploaderAgentId}）。</li>
 * </ol>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("AttachmentController 通道边界与响应投影")
class AttachmentControllerAuthScopeTest {

    private static final long ADMIN_SESSION_ID = 1L;
    private static final long AGENT_ID = 7L;
    private static final long ATTACHMENT_ID = 100L;
    private static final long SUB_TASK_ID = 500L;

    @Mock
    private AttachmentService attachmentService;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders
                .standaloneSetup(new AttachmentController(attachmentService))
                .build();
    }

    @Test
    @DisplayName("平台账号（_authType=admin）：不做可见性判定，直接返回附件详情")
    void adminShouldBypassVisibilityCheck() throws Exception {
        when(attachmentService.getByIdRequired(ATTACHMENT_ID)).thenReturn(sampleAttachment());

        mockMvc.perform(get("/api/attachments/getById/" + ATTACHMENT_ID)
                        .requestAttr("_authType", "admin")
                        .requestAttr("_authId", ADMIN_SESSION_ID))
                .andExpect(jsonPath("$.data.fileName").value("design.md"));

        verify(attachmentService, never()).assertReadable(any(), anyLong());
    }

    @Test
    @DisplayName("Agent 通道：必须经 Service 的 assertReadable 收口判定（Controller 不自行判可见性）")
    void agentChannelShouldDelegateVisibilityToService() throws Exception {
        Attachment attachment = sampleAttachment();
        when(attachmentService.getByIdRequired(ATTACHMENT_ID)).thenReturn(attachment);

        mockMvc.perform(get("/api/attachments/getById/" + ATTACHMENT_ID)
                        .requestAttr("_authType", "agent")
                        .requestAttr("_authId", AGENT_ID))
                .andExpect(jsonPath("$.data.fileName").value("design.md"));

        verify(attachmentService).assertReadable(attachment, AGENT_ID);
    }

    @Test
    @DisplayName("Agent 通道不可读：Service 抛 403 原样透出（边界仍在任务级）")
    void agentShouldBeRejectedWhenServiceDenies() {
        when(attachmentService.getByIdRequired(ATTACHMENT_ID)).thenReturn(sampleAttachment());
        doThrow(new BizException(403, "无权访问该附件（不在可见范围内）"))
                .when(attachmentService).assertReadable(any(), anyLong());

        Throwable root = unwrap(assertThrows(Exception.class, () -> mockMvc.perform(
                get("/api/attachments/getById/" + ATTACHMENT_ID)
                        .requestAttr("_authType", "agent")
                        .requestAttr("_authId", AGENT_ID))));
        assertInstanceOf(BizException.class, root);
        assertTrue(root.getMessage().contains("无权访问"), "实际异常：" + root.getMessage());
    }

    @Test
    @DisplayName("下载 / 预览端点同受 Agent 通道判定约束（不得绕过 getById）")
    void downloadAndPreviewAlsoEnforceAgentChannel() {
        when(attachmentService.getByIdRequired(ATTACHMENT_ID)).thenReturn(sampleAttachment());
        doThrow(new BizException(403, "无权访问该附件（不在可见范围内）"))
                .when(attachmentService).assertReadable(any(), anyLong());

        for (String path : List.of("/api/attachments/downloadById/", "/api/attachments/previewById/")) {
            Throwable root = unwrap(assertThrows(Exception.class, () -> mockMvc.perform(
                    get(path + ATTACHMENT_ID)
                            .requestAttr("_authType", "agent")
                            .requestAttr("_authId", AGENT_ID))));
            assertInstanceOf(BizException.class, root, "端点未受判定约束：" + path);
        }
    }

    @Test
    @DisplayName("列表（Agent 通道）：走 listReadable 行级过滤，不走全量 list")
    void listForAgentChannelShouldUseReadableVariant() throws Exception {
        when(attachmentService.listReadable(nullable(Long.class), anyLong()))
                .thenReturn(List.of(sampleAttachment()));

        mockMvc.perform(get("/api/attachments")
                        .requestAttr("_authType", "agent")
                        .requestAttr("_authId", AGENT_ID))
                .andExpect(jsonPath("$.data[0].fileName").value("design.md"));

        verify(attachmentService, never()).list(nullable(Long.class));
    }

    @Test
    @DisplayName("列表（平台账号）：走全量 list，不做行级过滤")
    void listForAdminChannelShouldUseFullList() throws Exception {
        when(attachmentService.list(nullable(Long.class)))
                .thenReturn(List.of(sampleAttachment()));

        mockMvc.perform(get("/api/attachments")
                        .requestAttr("_authType", "admin")
                        .requestAttr("_authId", ADMIN_SESSION_ID))
                .andExpect(jsonPath("$.data[0].fileName").value("design.md"));

        verify(attachmentService, never()).listReadable(nullable(Long.class), anyLong());
    }

    @Test
    @DisplayName("响应体为 VO 投影：含业务字段、不含实体内部字段（§11.3）")
    void responseShouldNotLeakEntityInternalFields() throws Exception {
        when(attachmentService.getByIdRequired(ATTACHMENT_ID)).thenReturn(sampleAttachment());

        mockMvc.perform(get("/api/attachments/getById/" + ATTACHMENT_ID)
                        .requestAttr("_authType", "admin")
                        .requestAttr("_authId", ADMIN_SESSION_ID))
                .andExpect(jsonPath("$.data.fileName").value("design.md"))
                .andExpect(jsonPath("$.data.status").value("ACTIVE"))
                .andExpect(jsonPath("$.data.deleted").doesNotExist())
                .andExpect(jsonPath("$.data.createBy").doesNotExist())
                .andExpect(jsonPath("$.data.visibility").doesNotExist())
                .andExpect(jsonPath("$.data.uploaderAgentId").doesNotExist());
    }

    @Test
    @DisplayName("无主体请求（无 _authType）：放行，交由管理侧鉴权覆盖")
    void anonymousRequestShouldPassThrough() {
        when(attachmentService.getByIdRequired(ATTACHMENT_ID)).thenReturn(sampleAttachment());

        assertDoesNotThrow(() -> mockMvc.perform(get("/api/attachments/getById/" + ATTACHMENT_ID)));

        verify(attachmentService, never()).assertReadable(any(), anyLong());
    }

    // ==================== 删除通道（P2，2026-10-07）====================

    @Test
    @DisplayName("删除：Agent 通道把 agentId 透传给 Service（判据收口在 Service，Controller 不自行判定）")
    void deleteForAgentChannelShouldPassAgentId() throws Exception {
        mockMvc.perform(post("/api/attachments/deleteById/" + ATTACHMENT_ID)
                        .requestAttr("_authType", "agent")
                        .requestAttr("_authId", AGENT_ID))
                .andExpect(jsonPath("$.code").value(200));

        verify(attachmentService).deleteAttachment(ATTACHMENT_ID, AGENT_ID);
    }

    @Test
    @DisplayName("删除：平台账号通道（_authType=admin）传 null agentId，交由管理侧鉴权覆盖")
    void deleteForAdminChannelShouldPassNullAgentId() throws Exception {
        mockMvc.perform(post("/api/attachments/deleteById/" + ATTACHMENT_ID)
                        .requestAttr("_authType", "admin")
                        .requestAttr("_authId", ADMIN_SESSION_ID))
                .andExpect(jsonPath("$.code").value(200));

        verify(attachmentService).deleteAttachment(ATTACHMENT_ID, null);
    }

    @Test
    @DisplayName("删除：无主体请求（无 _authType）传 null agentId，不触发越权判定")
    void deleteWithoutSubjectShouldPassNullAgentId() throws Exception {
        mockMvc.perform(post("/api/attachments/deleteById/" + ATTACHMENT_ID))
                .andExpect(jsonPath("$.code").value(200));

        verify(attachmentService).deleteAttachment(ATTACHMENT_ID, null);
    }

    // ==================== helpers ====================

    private static Attachment sampleAttachment() {
        Attachment attachment = new Attachment();
        attachment.setId(ATTACHMENT_ID);
        attachment.setSubTaskId(SUB_TASK_ID);
        attachment.setFileName("design.md");
        attachment.setFileType("markdown");
        attachment.setFileSize(2048L);
        attachment.setStorageUrl("local://helloai/500/design.md");
        attachment.setStatus(AttachmentStatus.ACTIVE);
        return attachment;
    }

    private static Throwable unwrap(Throwable thrown) {
        Throwable current = thrown;
        while (current.getCause() != null && current.getCause() != current) {
            current = current.getCause();
        }
        return current;
    }
}
