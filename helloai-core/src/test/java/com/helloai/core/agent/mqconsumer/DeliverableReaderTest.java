package com.helloai.core.agent.mqconsumer;

import com.helloai.core.agent.runtime.sandbox.SandboxArtifact;
import com.helloai.core.agent.runtime.sandbox.SandboxExecutionService;
import com.helloai.core.agent.runtime.sandbox.SandboxRuntime;
import com.helloai.core.agent.runtime.sandbox.WorkspaceEntry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * {@link DeliverableReader} 单测（`SL-9`，2026-11-13）。
 *
 * <p><b>为什么挑文件值得单独测</b>：任务级共享工作区里同时躺着上游旧稿与本轮草稿，
 * 「按大小取」会稳定选错（旧稿往往更大更完整），错误表现为「评审看到的是一份过期交付物」——
 * 不报错、不告警。这里把判据逐条钉死：mtime 优先、目录跳过、无 {@code .md} 才按体积兜底。</p>
 */
@DisplayName("DeliverableReader")
class DeliverableReaderTest {

    private final SandboxExecutionService sandboxExecutionService = mock(SandboxExecutionService.class);
    private final DeliverableReader reader = new DeliverableReader(sandboxExecutionService);

    @Test
    @DisplayName("pick：最新修改的 .md 优先（即便旧稿更大）")
    void pickShouldPreferLatestMarkdown() {
        OffsetDateTime base = OffsetDateTime.parse("2026-11-13T10:00:00Z");
        WorkspaceEntry oldDraft = new WorkspaceEntry("/workspace/old.md", 9_000L, base, "file");
        WorkspaceEntry newDraft = new WorkspaceEntry("/workspace/out/new.md", 1_200L,
                base.plusMinutes(30), "file");
        WorkspaceEntry logTxt = new WorkspaceEntry("/workspace/run.log", 50_000L,
                base.plusMinutes(40), "file");

        assertThat(DeliverableReader.pick(List.of(oldDraft, newDraft, logTxt))).isEqualTo(newDraft);
    }

    @Test
    @DisplayName("pick：没有 .md ⇒ 最大的非空文件（>200 字节），目录与碎片跳过")
    void pickShouldFallBackToLargestFile() {
        WorkspaceEntry dir = new WorkspaceEntry("/workspace/out", 0L, null, "directory");
        WorkspaceEntry fragment = new WorkspaceEntry("/workspace/tiny.txt", 12L, null, "file");
        WorkspaceEntry deliverable = new WorkspaceEntry("/workspace/report.html", 8_192L, null, "file");
        WorkspaceEntry smaller = new WorkspaceEntry("/workspace/body.txt", 900L, null, "file");

        assertThat(DeliverableReader.pick(List.of(dir, fragment, smaller, deliverable)))
                .isEqualTo(deliverable);
    }

    @Test
    @DisplayName("pick：空列表 / null / 全是目录与碎片 ⇒ null（保守，不乱猜）")
    void pickShouldReturnNullWithoutCandidates() {
        assertThat(DeliverableReader.pick(null)).isNull();
        assertThat(DeliverableReader.pick(List.of())).isNull();
        assertThat(DeliverableReader.pick(List.of(
                new WorkspaceEntry("/workspace/out", 0L, null, "dir"),
                new WorkspaceEntry("/workspace/tiny.txt", 10L, null, "file")))).isNull();
    }

    @Test
    @DisplayName("readLatestMarkdown：按选中路径回读正文（UTF-8，最多 60000 字节）")
    void shouldReadPickedFile() {
        WorkspaceEntry picked = new WorkspaceEntry("/workspace/out/draft.md", 64L,
                OffsetDateTime.parse("2026-11-13T10:00:00Z"), "file");
        when(sandboxExecutionService.listWorkspaceFiles(22L, SandboxRuntime.WORKSPACE_DIR))
                .thenReturn(List.of(picked));
        when(sandboxExecutionService.readArtifact(22L, "/workspace/out/draft.md", 60_000))
                .thenReturn(new SandboxArtifact("/workspace/out/draft.md",
                        "# 正文".getBytes(StandardCharsets.UTF_8), "text/markdown", "sha256:x", false));

        assertThat(reader.readLatestMarkdown(22L)).isEqualTo("# 正文");
        verify(sandboxExecutionService).readArtifact(eq(22L), eq("/workspace/out/draft.md"), anyInt());
    }

    @Test
    @DisplayName("readLatestMarkdown：null 句柄 / 无候选 / 回读失败 / 空白正文 ⇒ null 且不抛异常")
    void shouldDegradeToNull() {
        assertThat(reader.readLatestMarkdown(null)).isNull();
        verifyNoInteractions(sandboxExecutionService);

        when(sandboxExecutionService.listWorkspaceFiles(23L, SandboxRuntime.WORKSPACE_DIR))
                .thenReturn(List.of());
        assertThat(reader.readLatestMarkdown(23L)).isNull();

        when(sandboxExecutionService.listWorkspaceFiles(24L, SandboxRuntime.WORKSPACE_DIR))
                .thenThrow(new IllegalStateException("sandbox gone"));
        assertThat(reader.readLatestMarkdown(24L)).isNull();

        WorkspaceEntry picked = new WorkspaceEntry("/workspace/draft.md", 64L, null, "file");
        when(sandboxExecutionService.listWorkspaceFiles(25L, SandboxRuntime.WORKSPACE_DIR))
                .thenReturn(List.of(picked));
        when(sandboxExecutionService.readArtifact(eq(25L), anyString(), anyInt())).thenReturn(null);
        assertThat(reader.readLatestMarkdown(25L)).isNull();

        when(sandboxExecutionService.readArtifact(eq(25L), anyString(), anyInt()))
                .thenReturn(new SandboxArtifact("/workspace/draft.md",
                        "   ".getBytes(StandardCharsets.UTF_8), "text/markdown", "sha256:y", false));
        assertThat(reader.readLatestMarkdown(25L)).isNull();
    }
}
