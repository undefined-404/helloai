package com.helloai.core.agent.output;

import com.helloai.common.config.ArtifactStorageProperties;
import com.helloai.common.constant.AgentRole;
import com.helloai.core.agent.entity.Agent;
import com.helloai.core.agent.service.AgentService;
import com.helloai.core.agent.service.ExecutionArtifactService;
import com.helloai.core.agent.service.impl.ExecutionArtifactServiceImpl;
import com.helloai.core.system.entity.Attachment;
import com.helloai.core.system.service.AttachmentService;
import com.helloai.core.system.storage.ArtifactStorage;
import com.helloai.core.system.storage.StoredArtifact;
import com.helloai.core.task.entity.SubTask;
import com.helloai.core.task.service.TaskTimelineService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * ExecutionArtifactService 单元测试：物化编排的 best-effort 语义
 * （成功物化+时间线、空产出跳过、开关关闭跳过、异常吞掉、超限跳过）。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ExecutionArtifactService 执行产出物化编排")
class ExecutionArtifactServiceTest {

    @Mock
    private ArtifactStorage artifactStorage;
    @Mock
    private AttachmentService attachmentService;
    @Mock
    private TaskTimelineService taskTimelineService;
    @Mock
    private AgentService agentService;

    private ArtifactStorageProperties properties;
    private ExecutionArtifactService service;

    @BeforeEach
    void setUp() {
        properties = new ArtifactStorageProperties();
        service = new ExecutionArtifactServiceImpl(properties, new ExecutionOutputParser(),
                artifactStorage, attachmentService, taskTimelineService, agentService);
    }

    private SubTask subTask() {
        SubTask st = new SubTask();
        st.setId(100L);
        st.setTaskId(10L);
        st.setTitle("调度分析");
        st.setAssignedAgentId(7L);
        return st;
    }

    @Test
    @DisplayName("正常产出：落盘（归属目录用 Agent 注册名）+ register（归属传 assignedAgentId）+ 时间线（记上报 agentId）")
    void shouldMaterializeAndRecordTimeline() {
        StoredArtifact stored = new StoredArtifact("local://b/tester/2026/08/10/100/x-调度分析.md", "b", "tester/2026/08/10/100/x-调度分析.md", 12L);
        Agent agent = new Agent();
        agent.setId(7L);
        agent.setName("tester");
        when(agentService.getById(7L)).thenReturn(agent);
        when(artifactStorage.store(eq("tester"), eq(10L), eq(100L), eq("调度分析.md"), any())).thenReturn(stored);
        Attachment attachment = new Attachment();
        attachment.setId(555L);
        when(attachmentService.register(eq(7L), eq(100L), eq("调度分析.md"),
                eq("text/markdown"), eq(12L), eq(stored.storageUrl()))).thenReturn(attachment);

        service.materialize(subTask(), 99L, "# 报告内容");

        verify(taskTimelineService).recordEvent(eq(10L), eq(100L),
                eq("sub_task_artifact_materialized"), eq(AgentRole.EXECUTOR), eq(99L), any());
    }

    @Test
    @DisplayName("空产出跳过物化，不触发任何存储/注册/时间线")
    void shouldSkipWhenOutputBlank() {
        service.materialize(subTask(), 99L, "   ");

        verifyNoInteractions(artifactStorage, attachmentService, taskTimelineService);
    }

    @Test
    @DisplayName("enabled=false 时整体跳过")
    void shouldSkipWhenDisabled() {
        properties.setEnabled(false);

        service.materialize(subTask(), 99L, "# 报告内容");

        verifyNoInteractions(artifactStorage, attachmentService, taskTimelineService);
    }

    @Test
    @DisplayName("存储异常被吞掉（best-effort），不抛出、不注册附件")
    void shouldSwallowStorageException() {
        doThrow(new RuntimeException("disk full"))
                .when(artifactStorage).store(anyString(), any(), any(), anyString(), any());

        service.materialize(subTask(), 99L, "# 报告内容");

        verifyNoInteractions(attachmentService, taskTimelineService);
    }

    @Test
    @DisplayName("单文件超过 maxFileSize 跳过，不落盘不记时间线")
    void shouldSkipOversizedFile() {
        properties.setMaxFileSize(4L);

        service.materialize(subTask(), 99L, "超过四字节的产出内容");

        verifyNoInteractions(artifactStorage, attachmentService, taskTimelineService);
    }
}
