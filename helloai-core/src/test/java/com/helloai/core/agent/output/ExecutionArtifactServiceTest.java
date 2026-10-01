package com.helloai.core.agent.output;

import com.helloai.common.config.ArtifactStorageProperties;
import com.helloai.common.constant.AgentRole;
import com.helloai.core.agent.entity.Agent;
import com.helloai.core.agent.port.AttachmentPort;
import com.helloai.core.agent.port.SubTaskQueryPort;
import com.helloai.core.agent.port.SubTaskSnapshot;
import com.helloai.core.agent.port.TaskTimelinePort;
import com.helloai.core.agent.service.AgentService;
import com.helloai.core.agent.service.ExecutionArtifactService;
import com.helloai.core.agent.service.impl.ExecutionArtifactServiceImpl;
import com.helloai.core.system.storage.ArtifactStorage;
import com.helloai.core.system.storage.StoredArtifact;
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
 * （成功物化+时间线、空产出跳过、开关关闭跳过、异常吞掉、超限跳过、子任务不存在跳过）。
 *
 * <p>2026-10-01 W6：契约改为「ID 契约」（不再传 {@code task.entity.SubTask}），
 * 子任务数据经 {@link SubTaskQueryPort} 读取，附件登记经 {@link AttachmentPort}
 * 不透明命令端口——本测试同步改为装配这两个端口，并补「子任务不存在静默跳过」用例。</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ExecutionArtifactService 执行产出物化编排")
class ExecutionArtifactServiceTest {

    private static final Long SUB_TASK_ID = 100L;
    private static final Long TASK_ID = 10L;
    private static final Long OWNER_AGENT_ID = 7L;
    private static final Long REPORTING_AGENT_ID = 99L;

    @Mock
    private ArtifactStorage artifactStorage;
    @Mock
    private SubTaskQueryPort subTaskQueryPort;
    @Mock
    private AttachmentPort attachmentPort;
    @Mock
    private TaskTimelinePort taskTimelinePort;
    @Mock
    private AgentService agentService;

    private ArtifactStorageProperties properties;
    private ExecutionArtifactService service;

    @BeforeEach
    void setUp() {
        properties = new ArtifactStorageProperties();
        service = new ExecutionArtifactServiceImpl(properties, new ExecutionOutputParser(),
                artifactStorage, subTaskQueryPort, attachmentPort, taskTimelinePort, agentService);
    }

    private void stubSubTaskFound() {
        when(subTaskQueryPort.findById(SUB_TASK_ID)).thenReturn(
                SubTaskSnapshot.builder()
                        .id(SUB_TASK_ID).taskId(TASK_ID).assignedAgentId(OWNER_AGENT_ID)
                        .title("调度分析").build());
    }

    @Test
    @DisplayName("正常产出：落盘（归属目录用 Agent 注册名）+ register（归属传 assignedAgentId）+ 时间线（记上报 agentId）")
    void shouldMaterializeAndRecordTimeline() {
        stubSubTaskFound();
        StoredArtifact stored = new StoredArtifact("local://b/tester/2026/08/10/100/x-调度分析.md", "b", "tester/2026/08/10/100/x-调度分析.md", 12L);
        Agent agent = new Agent();
        agent.setId(OWNER_AGENT_ID);
        agent.setName("tester");
        when(agentService.getById(OWNER_AGENT_ID)).thenReturn(agent);
        when(artifactStorage.store(eq("tester"), eq(TASK_ID), eq(SUB_TASK_ID), eq("调度分析.md"), any())).thenReturn(stored);
        when(attachmentPort.register(eq(OWNER_AGENT_ID), eq(SUB_TASK_ID), eq("调度分析.md"),
                eq("text/markdown"), eq(12L), eq(stored.storageUrl()))).thenReturn(555L);

        service.materialize(SUB_TASK_ID, REPORTING_AGENT_ID, "# 报告内容");

        verify(taskTimelinePort).recordEvent(eq(TASK_ID), eq(SUB_TASK_ID),
                eq("sub_task_artifact_materialized"), eq(AgentRole.EXECUTOR), eq(REPORTING_AGENT_ID), any());
    }

    @Test
    @DisplayName("空产出跳过物化，不触发任何存储/注册/时间线")
    void shouldSkipWhenOutputBlank() {
        stubSubTaskFound();

        service.materialize(SUB_TASK_ID, REPORTING_AGENT_ID, "   ");

        verifyNoInteractions(artifactStorage, attachmentPort, taskTimelinePort);
    }

    @Test
    @DisplayName("enabled=false 时整体跳过（连子任务快照都不读）")
    void shouldSkipWhenDisabled() {
        properties.setEnabled(false);

        service.materialize(SUB_TASK_ID, REPORTING_AGENT_ID, "# 报告内容");

        verifyNoInteractions(subTaskQueryPort, artifactStorage, attachmentPort, taskTimelinePort);
    }

    @Test
    @DisplayName("存储异常被吞掉（best-effort），不抛出、不注册附件")
    void shouldSwallowStorageException() {
        stubSubTaskFound();
        doThrow(new RuntimeException("disk full"))
                .when(artifactStorage).store(anyString(), any(), any(), anyString(), any());

        service.materialize(SUB_TASK_ID, REPORTING_AGENT_ID, "# 报告内容");

        verifyNoInteractions(attachmentPort, taskTimelinePort);
    }

    @Test
    @DisplayName("单文件超过 maxFileSize 跳过，不落盘不记时间线")
    void shouldSkipOversizedFile() {
        stubSubTaskFound();
        properties.setMaxFileSize(4L);

        service.materialize(SUB_TASK_ID, REPORTING_AGENT_ID, "超过四字节的产出内容");

        verifyNoInteractions(artifactStorage, attachmentPort, taskTimelinePort);
    }

    @Test
    @DisplayName("子任务不存在（快照为 null）时静默跳过，不抛异常")
    void shouldSkipWhenSubTaskMissing() {
        when(subTaskQueryPort.findById(SUB_TASK_ID)).thenReturn(null);

        service.materialize(SUB_TASK_ID, REPORTING_AGENT_ID, "# 报告内容");

        verifyNoInteractions(artifactStorage, attachmentPort, taskTimelinePort);
    }

    @Test
    @DisplayName("subTaskId 为 null 时整体跳过")
    void shouldSkipWhenSubTaskIdNull() {
        service.materialize(null, REPORTING_AGENT_ID, "# 报告内容");

        verifyNoInteractions(subTaskQueryPort, artifactStorage, attachmentPort, taskTimelinePort);
    }
}
