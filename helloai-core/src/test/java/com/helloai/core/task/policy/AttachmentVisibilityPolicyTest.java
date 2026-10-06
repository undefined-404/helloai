package com.helloai.core.task.policy;

import com.helloai.common.constant.AttachmentVisibility;
import com.helloai.core.task.entity.Attachment;
import com.helloai.core.task.entity.SubTask;
import com.helloai.core.task.service.SubTaskService;
import com.helloai.core.task.service.TaskAgentMemberService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * {@link AttachmentVisibilityPolicy} 单元测试（§47.1 明确列 Policy 为单测对象）。
 *
 * <p><b>为什么必须独立于 Controller 测试</b>：本类是"agent 能读哪些附件"的<b>唯一判据入口</b>，
 * 其分支（上传者恒可读 / PERSONAL / PUBLIC fail-closed / TASK 成员关系 / derive-on-miss）
 * 是安全边界本身。此前仅由 REST 端点的 Controller 测试间接覆盖，一旦端点链路调整，
 * 判据就可能"无直测裸奔"。本测试直接对 {@code canRead} 断言，与 HTTP 层解耦。</p>
 *
 * <p><b>安全底线（不得回退）</b>：G-014 T04b 之前的缺陷是"任意有效 Agent API Key 可凭
 * attachmentId 读取任意子任务附件正文"。本测试以
 * {@link #outsiderShouldBeRejectedOnTaskScopedAttachment} /
 * {@link #nullAgentIdShouldNeverRead} 等用例把"不放行全局读"钉死。</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("AttachmentVisibilityPolicy 可见性判据")
class AttachmentVisibilityPolicyTest {

    private static final long UPLOADER = 9L;
    private static final long TEAMMATE = 7L;
    private static final long OUTSIDER = 8L;
    private static final long SUB_TASK_ID = 500L;
    private static final long TASK_ID = 900L;

    @Mock
    private TaskAgentMemberService taskAgentMemberService;
    @Mock
    private SubTaskService subTaskService;

    private AttachmentVisibilityPolicy policy;

    @BeforeEach
    void setUp() {
        policy = new AttachmentVisibilityPolicy(taskAgentMemberService, subTaskService);
    }

    // ==================== 入参与空值 ====================

    @Test
    @DisplayName("agentId 为 null：一律不可读，且不触达任何依赖（无主体即无权）")
    void nullAgentIdShouldNeverRead() {
        Attachment attachment = attachment(AttachmentVisibility.TASK, UPLOADER);

        assertThat(policy.canRead(null, attachment)).isFalse();
        assertThat(policy.canRead(null, SUB_TASK_ID, UPLOADER, AttachmentVisibility.TASK)).isFalse();
        verifyNoInteractions(taskAgentMemberService, subTaskService);
    }

    @Test
    @DisplayName("附件实体为 null：不可读（防御空引用，不抛异常）")
    void nullAttachmentShouldBeUnreadable() {
        assertThat(policy.canRead(TEAMMATE, (Attachment) null)).isFalse();
    }

    // ==================== ① 上传者恒可读自传 ====================

    @Test
    @DisplayName("上传者读自己的 PERSONAL 附件：放行（覆盖 PERSONAL 语义）")
    void uploaderShouldReadOwnPersonalAttachment() {
        assertThat(policy.canRead(UPLOADER, attachment(AttachmentVisibility.PERSONAL, UPLOADER))).isTrue();
        verifyNoInteractions(taskAgentMemberService, subTaskService);
    }

    @Test
    @DisplayName("上传者读自己的 TASK 附件：放行（被改派换下后仍能读自己旧产出）")
    void uploaderShouldReadOwnTaskScopedAttachmentWithoutMembership() {
        assertThat(policy.canRead(UPLOADER, attachment(AttachmentVisibility.TASK, UPLOADER))).isTrue();
        verifyNoInteractions(taskAgentMemberService, subTaskService);
    }

    // ==================== ② PERSONAL：仅上传者 ====================

    @Test
    @DisplayName("PERSONAL 附件非上传者：拒绝，且不触达成员查询（团队身份不得越权）")
    void teammateShouldBeRejectedForPersonalAttachmentWithoutMembershipLookup() {
        Attachment attachment = attachment(AttachmentVisibility.PERSONAL, UPLOADER);

        assertThat(policy.canRead(TEAMMATE, attachment)).isFalse();
        verifyNoInteractions(taskAgentMemberService, subTaskService);
    }

    @Test
    @DisplayName("uploaderAgentId 为 null（平台/人工上传）的 PERSONAL 附件：对任何 agent 均不可读")
    void personalAttachmentWithoutUploaderShouldBeUnreadableByAgents() {
        assertThat(policy.canRead(TEAMMATE, attachment(AttachmentVisibility.PERSONAL, null))).isFalse();
        verifyNoInteractions(taskAgentMemberService, subTaskService);
    }

    // ==================== ③ PUBLIC：预留未启用，fail-closed ====================

    @Test
    @DisplayName("PUBLIC 附件：fail-closed 拒绝（不得因 DB 被手工改成 PUBLIC 而全局可读）")
    void publicVisibilityShouldFailClosed() {
        Attachment attachment = attachment(AttachmentVisibility.PUBLIC, UPLOADER);

        assertThat(policy.canRead(TEAMMATE, attachment)).isFalse();
        // 即使非上传者也拒绝，且不做任何成员关系查询（不把判据扩散到成员表）
        verifyNoInteractions(taskAgentMemberService, subTaskService);
    }

    // ==================== ④ TASK：根任务成员关系 ====================

    @Test
    @DisplayName("visibility 为 null：按 TASK 处理（与 DB 列 NOT NULL DEFAULT 'TASK' 对齐）")
    void nullVisibilityShouldBeTreatedAsTaskScoped() {
        stubRootTask(TASK_ID);
        when(taskAgentMemberService.isMember(TASK_ID, TEAMMATE)).thenReturn(true);

        assertThat(policy.canRead(TEAMMATE, attachment(null, UPLOADER))).isTrue();
    }

    @Test
    @DisplayName("TASK 附件 + 成员表命中：放行（团队产出互通，本次改造的核心目的）")
    void memberShouldReadTaskScopedAttachment() {
        stubRootTask(TASK_ID);
        when(taskAgentMemberService.isMember(TASK_ID, TEAMMATE)).thenReturn(true);

        assertThat(policy.canRead(TEAMMATE, attachment(AttachmentVisibility.TASK, UPLOADER))).isTrue();
        verify(taskAgentMemberService, never()).isCurrentExecutorOfTask(TASK_ID, TEAMMATE);
    }

    @Test
    @DisplayName("TASK 附件 + 成员表未命中 + 权威源命中：derive-on-miss 兜底放行")
    void currentExecutorShouldPassViaAuthoritativeFallback() {
        stubRootTask(TASK_ID);
        when(taskAgentMemberService.isMember(TASK_ID, TEAMMATE)).thenReturn(false);
        when(taskAgentMemberService.isCurrentExecutorOfTask(TASK_ID, TEAMMATE)).thenReturn(true);

        assertThat(policy.canRead(TEAMMATE, attachment(AttachmentVisibility.TASK, UPLOADER))).isTrue();
    }

    @Test
    @DisplayName("TASK 附件 + 成员表与权威源均未命中：拒绝（兜底不放宽边界）")
    void outsiderShouldBeRejectedOnTaskScopedAttachment() {
        stubRootTask(TASK_ID);
        when(taskAgentMemberService.isMember(TASK_ID, OUTSIDER)).thenReturn(false);
        when(taskAgentMemberService.isCurrentExecutorOfTask(TASK_ID, OUTSIDER)).thenReturn(false);

        assertThat(policy.canRead(OUTSIDER, attachment(AttachmentVisibility.TASK, UPLOADER))).isFalse();
    }

    @Test
    @DisplayName("TASK 附件但子任务不存在（根任务解析为 null）：拒绝，且不查成员表")
    void unknownSubTaskShouldBeRejectedBeforeMembershipLookup() {
        when(subTaskService.getById(SUB_TASK_ID)).thenReturn(null);

        assertThat(policy.canRead(TEAMMATE, attachment(AttachmentVisibility.TASK, UPLOADER))).isFalse();
        verifyNoInteractions(taskAgentMemberService);
    }

    // ==================== ⑤ 删除判据 canDelete（P2 删除通道，2026-10-07）====================
    //  删除权限严格于读取：只认「上传者本人」，不复用 TASK 成员关系（团队可读≠可删）。

    @Test
    @DisplayName("canDelete：上传者本人删自己的附件 → 放行（唯一放行分支）")
    void canDelete_uploaderShouldPass() {
        assertThat(policy.canDelete(UPLOADER, attachment(AttachmentVisibility.TASK, UPLOADER))).isTrue();
        assertThat(policy.canDelete(UPLOADER, attachment(AttachmentVisibility.PERSONAL, UPLOADER))).isTrue();
        verifyNoInteractions(taskAgentMemberService, subTaskService);
    }

    @Test
    @DisplayName("canDelete：非上传者（即使同任务成员）→ 拒绝，且不触达成员查询")
    void canDelete_nonUploaderShouldBeRejectedWithoutMembershipLookup() {
        assertThat(policy.canDelete(TEAMMATE, attachment(AttachmentVisibility.TASK, UPLOADER))).isFalse();
        verifyNoInteractions(taskAgentMemberService, subTaskService);
    }

    @Test
    @DisplayName("canDelete：agentId 为 null（平台/无主体）→ 拒绝，不触达任何依赖")
    void canDelete_nullAgentIdShouldBeRejected() {
        assertThat(policy.canDelete(null, attachment(AttachmentVisibility.TASK, UPLOADER))).isFalse();
        verifyNoInteractions(taskAgentMemberService, subTaskService);
    }

    @Test
    @DisplayName("canDelete：附件为 null 或 uploaderAgentId 为 null（平台上传）→ 拒绝（防御空引用）")
    void canDelete_nullAttachmentOrUploaderShouldBeRejected() {
        assertThat(policy.canDelete(UPLOADER, (Attachment) null)).isFalse();
        assertThat(policy.canDelete(UPLOADER, attachment(AttachmentVisibility.TASK, null))).isFalse();
        verifyNoInteractions(taskAgentMemberService, subTaskService);
    }

    // ==================== 根任务解析（唯一层级相关逻辑） ====================

    @Test
    @DisplayName("rootTaskIdOf：解析 subTask.taskId；子任务不存在或入参为 null 返回 null")
    void rootTaskIdOfShouldResolveTaskIdAndTolerateNull() {
        SubTask subTask = new SubTask();
        subTask.setId(SUB_TASK_ID);
        subTask.setTaskId(TASK_ID);
        when(subTaskService.getById(SUB_TASK_ID)).thenReturn(subTask);

        assertThat(policy.rootTaskIdOf(SUB_TASK_ID)).isEqualTo(TASK_ID);
        assertThat(policy.rootTaskIdOf(attachment(AttachmentVisibility.TASK, UPLOADER))).isEqualTo(TASK_ID);
        assertThat(policy.rootTaskIdOf((Long) null)).isNull();
        assertThat(policy.rootTaskIdOf((Attachment) null)).isNull();
    }

    // ==================== helpers ====================

    private void stubRootTask(Long taskId) {
        SubTask subTask = new SubTask();
        subTask.setId(SUB_TASK_ID);
        subTask.setTaskId(taskId);
        lenient().when(subTaskService.getById(SUB_TASK_ID)).thenReturn(subTask);
    }

    private static Attachment attachment(AttachmentVisibility visibility, Long uploaderAgentId) {
        Attachment attachment = new Attachment();
        attachment.setId(100L);
        attachment.setSubTaskId(SUB_TASK_ID);
        attachment.setVisibility(visibility);
        attachment.setUploaderAgentId(uploaderAgentId);
        return attachment;
    }
}
