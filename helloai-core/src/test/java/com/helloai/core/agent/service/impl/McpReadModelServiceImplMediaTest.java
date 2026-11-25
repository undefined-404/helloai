package com.helloai.core.agent.service.impl;

import com.helloai.common.constant.SubTaskStatus;
import com.helloai.core.agent.port.AttachmentPort;
import com.helloai.core.agent.port.AttachmentRef;
import com.helloai.core.agent.port.SubTaskQueryPort;
import com.helloai.core.agent.port.SubTaskSnapshot;
import com.helloai.core.agent.port.TaskRunningSpecPort;
import com.helloai.core.agent.service.AgentExecutionRecordService;
import com.helloai.core.agent.service.McpToolService.GetDepsSummaryResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@code getDepsSummary} 的「图片/音视频不得进文本通道」判据（`VU-1` 同一事实的第二个消费方）。
 *
 * <p><b>为什么这条必须单独测</b>：`VU-1` 在 {@code AgentRuntimeContextAssembler} 修掉
 * 「所有附件无差别 UTF-8 解码」后，本类仍保留同款解码——上游产出图（如 {@code chart.png}）
 * 会被解成乱码塞进 MCP 工具的 {@code deps.content}，执行者读到的是垃圾字节。
 * 两处判据若只修一处，就会出现「执行链不看乱码、MCP 工具仍看乱码」的静默不一致
 * （判据 4：同一事实双写必分叉）。</p>
 *
 * <p><b>判据取向</b>：断言的是<b>行为</b>（图片不进 content / 不读字节），不是实现细节——
 * 「不读字节」用 {@code verify(never())} 固化，因为「先读进内存再丢弃」在 5MB 图上
 * 是真实的资源浪费，而不仅是代码风格问题。</p>
 */
class McpReadModelServiceImplMediaTest {

    private SubTaskQueryPort subTaskQueryPort;
    private TaskRunningSpecPort taskRunningSpecPort;
    private AttachmentPort attachmentPort;
    private McpReadModelServiceImpl service;

    /** 被测子任务与其唯一前置。 */
    private static final long SUB_TASK_ID = 1001L;
    private static final long DEP_ID = 2002L;
    private static final long TASK_ID = 3003L;

    @BeforeEach
    void setUp() {
        subTaskQueryPort = mock(SubTaskQueryPort.class);
        taskRunningSpecPort = mock(TaskRunningSpecPort.class);
        attachmentPort = mock(AttachmentPort.class);
        AgentExecutionRecordService recordService = mock(AgentExecutionRecordService.class);
        service = new McpReadModelServiceImpl(subTaskQueryPort, taskRunningSpecPort, attachmentPort, recordService);

        SubTaskSnapshot current = snapshot(SUB_TASK_ID, "当前任务", SubTaskStatus.IN_PROGRESS, List.of(DEP_ID));
        SubTaskSnapshot dep = snapshot(DEP_ID, "前置任务", SubTaskStatus.DONE, List.of());
        when(subTaskQueryPort.findById(SUB_TASK_ID)).thenReturn(current);
        when(subTaskQueryPort.listByIds(List.of(DEP_ID))).thenReturn(List.of(dep));
        when(taskRunningSpecPort.findExecutionSummary(anyLong(), anyLong())).thenReturn(null);
    }

    @Test
    @DisplayName("图片附件不进文本通道：content 不含其字节，且根本不读字节")
    void shouldSkipImageAttachmentWithoutLoadingBytes() {
        byte[] pngBytes = new byte[]{(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A};
        AttachmentRef image = new AttachmentRef(11L, "chart.png", "png", "image/png",
                (long) pngBytes.length, null, true);
        when(attachmentPort.listActive(DEP_ID)).thenReturn(List.of(image));

        GetDepsSummaryResult result = service.getDepsSummary(1L, SUB_TASK_ID);

        GetDepsSummaryResult.DepItem item = onlyDep(result);
        assertThat(item.getContent()).isNull();
        assertThat(item.getLoaded()).isFalse();
        assertThat(item.getContentChars()).isZero();
        // 关键判据：不读字节（先读后丢在 5MB 图上是真实浪费，不是风格问题）
        verify(attachmentPort, never()).loadContent(any());
    }

    @Test
    @DisplayName("音频/视频附件同样不进文本通道（模型能力未声明 audio/video）")
    void shouldSkipAudioVideoAttachment() {
        AttachmentRef video = new AttachmentRef(12L, "demo.mp4", "mp4", "video/mp4",
                2048L, null, true);
        when(attachmentPort.listActive(DEP_ID)).thenReturn(List.of(video));

        GetDepsSummaryResult result = service.getDepsSummary(1L, SUB_TASK_ID);

        GetDepsSummaryResult.DepItem item = onlyDep(result);
        assertThat(item.getContent()).isNull();
        assertThat(item.getLoaded()).isFalse();
        verify(attachmentPort, never()).loadContent(any());
    }

    @Test
    @DisplayName("MIME 缺失但扩展名是图片（octet-stream 兜底路径）同样被排除")
    void shouldSkipImageByExtensionWhenMimeIsOctetStream() {
        AttachmentRef image = new AttachmentRef(13L, "screenshot.JPG", "JPG",
                "application/octet-stream", 512L, null, true);
        when(attachmentPort.listActive(DEP_ID)).thenReturn(List.of(image));

        GetDepsSummaryResult result = service.getDepsSummary(1L, SUB_TASK_ID);

        assertThat(onlyDep(result).getLoaded()).isFalse();
        verify(attachmentPort, never()).loadContent(any());
    }

    @Test
    @DisplayName("文本附件仍照原样注入（修复不得误伤正常路径）")
    void shouldStillLoadTextAttachment() {
        AttachmentRef markdown = new AttachmentRef(14L, "report.md", "md", "text/markdown",
                64L, null, true);
        when(attachmentPort.listActive(DEP_ID)).thenReturn(List.of(markdown));
        when(attachmentPort.loadContent(14L)).thenReturn("# 结论\n分析完成".getBytes(StandardCharsets.UTF_8));

        GetDepsSummaryResult result = service.getDepsSummary(1L, SUB_TASK_ID);

        GetDepsSummaryResult.DepItem item = onlyDep(result);
        assertThat(item.getLoaded()).isTrue();
        assertThat(item.getContent()).contains("# 结论").contains("分析完成");
    }

    @Test
    @DisplayName("图文混合：图被排除、文照常注入，两者互不影响")
    void shouldKeepTextWhenImagePresent() {
        AttachmentRef image = new AttachmentRef(15L, "chart.png", "png", "image/png", 128L, null, true);
        AttachmentRef markdown = new AttachmentRef(16L, "report.md", "md", "text/markdown", 32L, null, true);
        // listActive 语义为倒序，此处按「后创建在前」给出：report 后创建
        when(attachmentPort.listActive(DEP_ID)).thenReturn(List.of(markdown, image));
        when(attachmentPort.loadContent(16L)).thenReturn("正文".getBytes(StandardCharsets.UTF_8));

        GetDepsSummaryResult result = service.getDepsSummary(1L, SUB_TASK_ID);

        GetDepsSummaryResult.DepItem item = onlyDep(result);
        assertThat(item.getLoaded()).isTrue();
        assertThat(item.getContent()).contains("正文");
        assertThat(item.getContent()).doesNotContain("\uFFFD");
        verify(attachmentPort, never()).loadContent(15L);
    }

    // ── 工具 ────────────────────────────────────────────────────────

    private static GetDepsSummaryResult.DepItem onlyDep(GetDepsSummaryResult result) {
        assertThat(result.getDeps()).hasSize(1);
        return result.getDeps().get(0);
    }

    /**
     * 子任务快照：用 {@code builder()} 而非位置参数构造。
     *
     * <p>该类 javadoc 明确「新增字段一律追加到末尾 + 测试侧建议改用 builder」——位置参数
     * 在 19 字段的 record 上极易错位（本次首版就写错了 {@code status}/{@code taskId} 顺序）。</p>
     */
    private static SubTaskSnapshot snapshot(long id, String title, SubTaskStatus status, List<Long> dependsOn) {
        Map<String, Object> context = new LinkedHashMap<>();
        return SubTaskSnapshot.builder()
                .id(id)
                .taskId(TASK_ID)
                .title(title)
                .content("描述")
                .deliverable("交付物")
                .acceptance("验收标准")
                .status(status)
                .dependsOn(dependsOn)
                .context(context)
                .uncertainties(List.of())
                .constraintRefs(List.of())
                .build();
    }
}