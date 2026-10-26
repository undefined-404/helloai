package com.helloai.core.agent.service.impl;

import com.helloai.common.base.BizException;
import com.helloai.common.config.ArtifactStorageProperties;
import com.helloai.core.agent.entity.Agent;
import com.helloai.core.agent.port.AttachmentPort;
import com.helloai.core.agent.port.SubTaskQueryPort;
import com.helloai.core.agent.port.SubTaskSnapshot;
import com.helloai.core.agent.runtime.sandbox.SandboxArtifact;
import com.helloai.core.agent.runtime.sandbox.SandboxExecutionService;
import com.helloai.core.agent.service.AgentService;
import com.helloai.core.agent.service.SkillArtifactExportService.ExportResult;
import com.helloai.core.system.storage.ArtifactStorage;
import com.helloai.core.system.storage.StoredArtifact;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * {@link SkillArtifactExportServiceImpl} 单测（`SKX-11`，2026-10-26）。
 *
 * <p>验的是四条 fail-close 判定与「两段接力参数正确」：字节从
 * {@link SandboxExecutionService#readArtifact} 出来，必须<b>原样</b>进
 * {@link ArtifactStorage#store}，再由 {@link AttachmentPort#register} 用<b>同一个</b>文件名登记
 * ——任一处换了名字就会出现「库里叫 A、桶里叫 B」的分叉。</p>
 */
@DisplayName("SkillArtifactExportServiceImpl 沙箱产物导出（SKX-11）")
class SkillArtifactExportServiceImplTest {

    private static final long SUB_TASK_ID = 22L;
    private static final long TASK_ID = 100L;
    private static final long AGENT_ID = 11L;
    private static final String PATH = "/workspace/out/archify.html";
    private static final String SHA = "a".repeat(64);

    private final ArtifactStorageProperties properties = new ArtifactStorageProperties();
    private final ArtifactStorage artifactStorage = mock(ArtifactStorage.class);
    private final SubTaskQueryPort subTaskQueryPort = mock(SubTaskQueryPort.class);
    private final AttachmentPort attachmentPort = mock(AttachmentPort.class);
    private final AgentService agentService = mock(AgentService.class);
    private final SandboxExecutionService sandboxExecutionService = mock(SandboxExecutionService.class);

    private final SkillArtifactExportServiceImpl service = new SkillArtifactExportServiceImpl(
            properties, artifactStorage, subTaskQueryPort, attachmentPort, agentService, sandboxExecutionService);

    @Test
    @DisplayName("正常导出：store 拿到归属目录 / 任务号，register 拿到同一文件名与字节数")
    void shouldStoreThenRegisterWithSameFileName() {
        givenSubTask();
        byte[] bytes = "<html>x</html>".getBytes(StandardCharsets.UTF_8);
        when(sandboxExecutionService.readArtifact(eq(SUB_TASK_ID), eq(PATH), anyInt()))
                .thenReturn(new SandboxArtifact(PATH, bytes, "text/html", SHA, false));
        when(artifactStorage.store("executor-1", TASK_ID, SUB_TASK_ID, "archify.html", bytes))
                .thenReturn(new StoredArtifact("minio://helloai-artifacts/executor-1/2026/10/100/22/ab-archify.html",
                        "helloai-artifacts", "executor-1/2026/10/100/22/ab-archify.html", bytes.length));
        when(attachmentPort.register(AGENT_ID, SUB_TASK_ID, "archify.html", "text/html",
                (long) bytes.length, "minio://helloai-artifacts/executor-1/2026/10/100/22/ab-archify.html"))
                .thenReturn(9001L);

        ExportResult result = service.export(AGENT_ID, SUB_TASK_ID, PATH, null);

        assertThat(result.attachmentId()).isEqualTo(9001L);
        // 文件名缺省取路径最后一段（不从 MIME 反猜扩展名）
        assertThat(result.fileName()).isEqualTo("archify.html");
        assertThat(result.mimeType()).isEqualTo("text/html");
        assertThat(result.size()).isEqualTo(bytes.length);
        assertThat(result.sha256()).isEqualTo(SHA);
        verify(artifactStorage).store("executor-1", TASK_ID, SUB_TASK_ID, "archify.html", bytes);
        verify(attachmentPort).register(AGENT_ID, SUB_TASK_ID, "archify.html", "text/html",
                (long) bytes.length, "minio://helloai-artifacts/executor-1/2026/10/100/22/ab-archify.html");
    }

    @Test
    @DisplayName("文件名入参被清洗：附一条脏名 ⇒ 落库与对象键用同一份清洗结果")
    void shouldSanitizeFileNameBeforeBothSteps() {
        givenSubTask();
        byte[] bytes = "x".getBytes(StandardCharsets.UTF_8);
        when(sandboxExecutionService.readArtifact(eq(SUB_TASK_ID), eq(PATH), anyInt()))
                .thenReturn(new SandboxArtifact(PATH, bytes, "text/html", SHA, false));
        when(artifactStorage.store(anyString(), anyLong(), anyLong(), anyString(), any()))
                .thenReturn(new StoredArtifact("minio://b/k", "b", "k", bytes.length));
        when(attachmentPort.register(anyLong(), anyLong(), anyString(), anyString(), anyLong(), anyString()))
                .thenReturn(1L);

        ExportResult result = service.export(AGENT_ID, SUB_TASK_ID, PATH, "../a/b.html");

        // 分隔符 → 下划线，前导点被剥（剥完残留的前导 "_" 无害）；两处共用这一个名字
        assertThat(result.fileName()).isEqualTo("_a_b.html");
        verify(artifactStorage).store("executor-1", TASK_ID, SUB_TASK_ID, "_a_b.html", bytes);
        verify(attachmentPort).register(AGENT_ID, SUB_TASK_ID, "_a_b.html", "text/html",
                (long) bytes.length, "minio://b/k");
    }

    @Test
    @DisplayName("产物超上限（truncated）⇒ 拒绝，且不写存储、不登记附件")
    void shouldRejectTruncatedArtifact() {
        givenSubTask();
        when(sandboxExecutionService.readArtifact(eq(SUB_TASK_ID), eq(PATH), anyInt()))
                .thenReturn(new SandboxArtifact(PATH, new byte[100], "text/html", SHA, true));

        assertThatThrownBy(() -> service.export(AGENT_ID, SUB_TASK_ID, PATH, null))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("单文件上限");

        // 关键：不能先写一半再报错——桶里留残件比直接失败更难清理
        verifyNoInteractions(artifactStorage, attachmentPort);
    }

    @Test
    @DisplayName("产物不存在 / 沙箱已回收（readArtifact=null）⇒ 拒绝，不登记附件")
    void shouldRejectMissingArtifact() {
        givenSubTask();
        when(sandboxExecutionService.readArtifact(eq(SUB_TASK_ID), eq(PATH), anyInt())).thenReturn(null);

        assertThatThrownBy(() -> service.export(AGENT_ID, SUB_TASK_ID, PATH, null))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("产物不存在");

        verify(attachmentPort, never()).register(anyLong(), anyLong(), anyString(), anyString(),
                anyLong(), anyString());
    }

    @Test
    @DisplayName("子任务不存在 ⇒ 拒绝（归属与任务号都无从确定）")
    void shouldRejectUnknownSubTask() {
        when(subTaskQueryPort.findById(SUB_TASK_ID)).thenReturn(null);

        assertThatThrownBy(() -> service.export(AGENT_ID, SUB_TASK_ID, PATH, null))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("子任务不存在");

        verifyNoInteractions(sandboxExecutionService, artifactStorage, attachmentPort);
    }

    @Test
    @DisplayName("路径为空 ⇒ 拒绝（不按 /workspace 补全，避免静默导出另一个文件）")
    void shouldRejectBlankPath() {
        assertThatThrownBy(() -> service.export(AGENT_ID, SUB_TASK_ID, "  ", null))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("绝对路径");

        verifyNoInteractions(sandboxExecutionService);
    }

    @Test
    @DisplayName("相对路径 / 含 '..' ⇒ 在导出侧就拒绝（错误信息要指向「路径写法」，不是「产物不存在」）")
    void shouldRejectNonAbsoluteOrTraversalPath() {
        assertThatThrownBy(() -> service.export(AGENT_ID, SUB_TASK_ID, "out/x.html", null))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("绝对路径");
        assertThatThrownBy(() -> service.export(AGENT_ID, SUB_TASK_ID, "/workspace/../etc/passwd", null))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("绝对路径");

        // 路径本身就不合规 ⇒ 连 readArtifact 都不该调（否则错误会退化成「产物不存在」）
        verifyNoInteractions(sandboxExecutionService);
    }

    /** 子任务快照：只有 id / taskId / assignedAgentId 有意义，其余字段本服务不读。 */
    private void givenSubTask() {
        when(subTaskQueryPort.findById(SUB_TASK_ID)).thenReturn(new SubTaskSnapshot(
                SUB_TASK_ID, null, TASK_ID, AGENT_ID, Map.of(), "标题",
                null, null, null, null, null, null, null, null, null, null, List.of(), List.of()));
        Agent agent = new Agent();
        agent.setName("executor-1");
        when(agentService.getById(AGENT_ID)).thenReturn(agent);
    }
}
