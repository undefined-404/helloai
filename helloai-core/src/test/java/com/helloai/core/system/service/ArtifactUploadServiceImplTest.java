package com.helloai.core.system.service;

import com.helloai.common.base.BizException;
import com.helloai.common.constant.AgentStatus;
import com.helloai.core.agent.entity.Agent;
import com.helloai.core.agent.service.AgentService;
import com.helloai.core.system.entity.Attachment;
import com.helloai.core.system.service.impl.ArtifactUploadServiceImpl;
import com.helloai.core.system.storage.ArtifactStorage;
import com.helloai.core.system.storage.StoredArtifact;
import com.helloai.core.task.entity.SubTask;
import com.helloai.core.task.service.SubTaskService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ArtifactUploadServiceImplTest {

    private AgentService agentService;
    private SubTaskService subTaskService;
    private AttachmentService attachmentService;
    private ArtifactStorage artifactStorage;

    private ArtifactUploadServiceImpl service;

    @BeforeEach
    void setUp() {
        agentService = mock(AgentService.class);
        subTaskService = mock(SubTaskService.class);
        attachmentService = mock(AttachmentService.class);
        artifactStorage = mock(ArtifactStorage.class);
        service = new ArtifactUploadServiceImpl(agentService, subTaskService, attachmentService, artifactStorage);
    }

    private Agent agent(Long id, String name, AgentStatus status) {
        Agent agent = new Agent();
        agent.setId(id);
        agent.setName(name);
        agent.setStatus(status);
        return agent;
    }

    private SubTask subTask(Long id, Long taskId, Long assignedAgentId) {
        SubTask st = new SubTask();
        st.setId(id);
        st.setTaskId(taskId);
        st.setAssignedAgentId(assignedAgentId);
        return st;
    }

    private byte[] content() {
        return "hello artifact".getBytes(StandardCharsets.UTF_8);
    }

    @Test
    @DisplayName("正常上传：store + register 一步到位，返回 attachmentId/storageUrl/fileSize")
    void shouldStoreAndRegister() {
        when(agentService.getById(1L)).thenReturn(agent(1L, "traE", AgentStatus.ACTIVE));
        when(subTaskService.getById(100L)).thenReturn(subTask(100L, 10L, 1L));
        when(artifactStorage.store(eq("traE"), eq(10L), eq(100L), eq("a.md"), any()))
                .thenReturn(new StoredArtifact(
                        "minio://helloai-artifacts/traE/2026/08/17/10/100/uuid-a.md",
                        "helloai-artifacts", "traE/2026/08/17/10/100/uuid-a.md", 15));
        Attachment att = new Attachment();
        att.setId(999L);
        when(attachmentService.register(eq(1L), eq(100L), eq("a.md"), eq("text/markdown"), eq(15L),
                eq("minio://helloai-artifacts/traE/2026/08/17/10/100/uuid-a.md"))).thenReturn(att);

        ArtifactUploadService.ArtifactUploadResult result =
                service.upload(1L, 100L, "a.md", "text/markdown", content());

        assertThat(result.getAttachmentId()).isEqualTo(999L);
        assertThat(result.getStorageUrl()).startsWith("minio://helloai-artifacts/");
        assertThat(result.getFileSize()).isEqualTo(15);
        verify(artifactStorage).store(eq("traE"), eq(10L), eq(100L), eq("a.md"), any());
        verify(attachmentService).register(eq(1L), eq(100L), eq("a.md"), eq("text/markdown"), eq(15L), anyString());
    }

    @Test
    @DisplayName("fileName 做安全清洗：路径分隔符先替换为 _ 再剥点前缀（../报告.md → _报告.md）")
    void shouldSanitizeFileNameBeforeStore() {
        when(agentService.getById(1L)).thenReturn(agent(1L, "traE", AgentStatus.ACTIVE));
        when(subTaskService.getById(100L)).thenReturn(subTask(100L, 10L, 1L));
        when(artifactStorage.store(any(), any(), any(), any(), any()))
                .thenReturn(new StoredArtifact("minio://b/k", "b", "k", 15));
        when(attachmentService.register(any(), any(), any(), any(), any(), any())).thenReturn(new Attachment());

        service.upload(1L, 100L, "../报告.md", null, content());

        verify(artifactStorage).store(eq("traE"), eq(10L), eq(100L), eq("_报告.md"), any());
    }

    @Test
    @DisplayName("Agent 不存在：抛 BizException，不触碰子任务与存储")
    void shouldRejectUnknownAgent() {
        when(agentService.getById(1L)).thenReturn(null);

        assertThatThrownBy(() -> service.upload(1L, 100L, "a.md", null, content()))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("Agent 不存在");
        verify(subTaskService, never()).getById(any());
    }

    @Test
    @DisplayName("Agent 未激活：抛 BizException")
    void shouldRejectDisabledAgent() {
        when(agentService.getById(1L)).thenReturn(agent(1L, "traE", AgentStatus.DISABLED));

        assertThatThrownBy(() -> service.upload(1L, 100L, "a.md", null, content()))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("Agent 未激活");
    }

    @Test
    @DisplayName("子任务不存在：抛 BizException")
    void shouldRejectMissingSubTask() {
        when(agentService.getById(1L)).thenReturn(agent(1L, "traE", AgentStatus.ACTIVE));
        when(subTaskService.getById(100L)).thenReturn(null);

        assertThatThrownBy(() -> service.upload(1L, 100L, "a.md", null, content()))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("子任务不存在");
        verify(artifactStorage, never()).store(any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("非本人子任务：抛 BizException，不写存储")
    void shouldRejectNotOwnedSubTask() {
        when(agentService.getById(1L)).thenReturn(agent(1L, "traE", AgentStatus.ACTIVE));
        when(subTaskService.getById(100L)).thenReturn(subTask(100L, 10L, 2L));

        assertThatThrownBy(() -> service.upload(1L, 100L, "a.md", null, content()))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("无权为该子任务上传产物");
        verify(artifactStorage, never()).store(any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("fileName 为空：抛 BizException")
    void shouldRejectBlankFileName() {
        when(agentService.getById(1L)).thenReturn(agent(1L, "traE", AgentStatus.ACTIVE));

        assertThatThrownBy(() -> service.upload(1L, 100L, "  ", null, content()))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("fileName 不能为空");
    }

    @Test
    @DisplayName("文件内容为空：抛 BizException")
    void shouldRejectEmptyContent() {
        when(agentService.getById(1L)).thenReturn(agent(1L, "traE", AgentStatus.ACTIVE));

        assertThatThrownBy(() -> service.upload(1L, 100L, "a.md", null, new byte[0]))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("文件内容不能为空");
    }
}
