package com.helloai.core.task.service.impl;

import com.helloai.core.task.service.TaskRunningSpecService;
import com.helloai.core.task.spec.ExecutionRecord;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@code TaskRunningSpecPortAdapter} 单测（2026-10-01 W7 新建，W10 扩写路径）。
 *
 * <p>读路径契约只暴露摘要字符串（消费方实际只读 {@code ExecutionRecord.summary()}），
 * 故覆盖两点：有记录取 summary、记录为 null 收敛为 null（消费方自行判空，本层不做
 * 「空串替代」这类隐式改写）。</p>
 *
 * <p><b>不存在「记录存在但 summary 为空」的用例</b>：{@code ExecutionRecord} 的
 * {@code summary} 是 builder 必填项（缺失即 {@code IllegalArgumentException: summary is required}），
 * 该分支在领域上不可达，为此造例只会测到 record 自身的校验、而非本适配器。</p>
 *
 * <p><b>W10 新增写路径</b>：{@code parseAndAppendExecutionRecord} 承接原
 * {@code ExecutionResultHandler} 内联的「解析 EXECUTION_RECORD / fallback / 落库」整块逻辑，
 * 覆盖三条分支：解析成功、解析失败取前 200 字符 fallback、输出空白不落记录。</p>
 *
 * <p><b>W11 新增读路径</b>：{@code buildExecutorPromptSection} 供
 * {@code AgentRuntimeContextAssembler} 的 Prompt 装配使用，纯薄委托（拼装口径不外泄）。</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("TaskRunningSpecPortAdapter")
class TaskRunningSpecPortAdapterTest {

    private static final Long TASK_ID = 100L;
    private static final Long SUB_TASK_ID = 11L;
    private static final Long AGENT_ID = 7L;

    @Mock
    private TaskRunningSpecService taskRunningSpecService;

    @InjectMocks
    private TaskRunningSpecPortAdapter adapter;

    private ExecutionRecord captureAppended() {
        ArgumentCaptor<ExecutionRecord> captor = ArgumentCaptor.forClass(ExecutionRecord.class);
        verify(taskRunningSpecService).appendExecutionRecord(eq(TASK_ID), captor.capture());
        return captor.getValue();
    }

    @Test
    @DisplayName("findExecutionSummary：有记录时返回其 summary")
    void shouldReturnSummary() {
        ExecutionRecord record = ExecutionRecord.builder()
                .subTaskId(11L).summary("已交付接口契约").build();
        when(taskRunningSpecService.findRecord(100L, 11L)).thenReturn(record);

        assertThat(adapter.findExecutionSummary(100L, 11L)).isEqualTo("已交付接口契约");
    }

    @Test
    @DisplayName("findExecutionSummary：记录不存在返回 null（与 findRecord 语义一致）")
    void shouldReturnNullWhenRecordMissing() {
        when(taskRunningSpecService.findRecord(100L, 11L)).thenReturn(null);

        assertThat(adapter.findExecutionSummary(100L, 11L)).isNull();
    }

    @Test
    @DisplayName("buildExecutorPromptSection：薄委托透传全局段文本（拼装口径整体在 TaskRunningSpecService）")
    void shouldDelegateBuildExecutorPromptSection() {
        when(taskRunningSpecService.buildExecutorPromptSection(100L)).thenReturn("【运行规格段】");

        assertThat(adapter.buildExecutorPromptSection(100L)).isEqualTo("【运行规格段】");
    }

    @Test
    @DisplayName("parseAndAppendExecutionRecord：解析出 EXECUTION_RECORD 块时用解析结果回填")
    void shouldAppendParsedRecord() {
        String rawOutput = "前置说明\n---\n## EXECUTION_RECORD\nSUMMARY: 实现了登录接口\n---\n尾部说明";

        adapter.parseAndAppendExecutionRecord(TASK_ID, SUB_TASK_ID, "登录接口", AGENT_ID, rawOutput);

        ExecutionRecord appended = captureAppended();
        assertThat(appended.subTaskId()).isEqualTo(SUB_TASK_ID);
        assertThat(appended.title()).isEqualTo("登录接口");
        assertThat(appended.agentId()).isEqualTo(AGENT_ID);
        assertThat(appended.summary()).isEqualTo("实现了登录接口");
    }

    @Test
    @DisplayName("parseAndAppendExecutionRecord：解析失败时用输出原文作 fallback summary")
    void shouldAppendFallbackWhenParseFails() {
        String rawOutput = "没有任何结构化块的自由文本";

        adapter.parseAndAppendExecutionRecord(TASK_ID, SUB_TASK_ID, "标题", AGENT_ID, rawOutput);

        ExecutionRecord appended = captureAppended();
        assertThat(appended.summary()).isEqualTo(rawOutput);
        assertThat(appended.subTaskId()).isEqualTo(SUB_TASK_ID);
        assertThat(appended.title()).isEqualTo("标题");
        assertThat(appended.agentId()).isEqualTo(AGENT_ID);
    }

    @Test
    @DisplayName("parseAndAppendExecutionRecord：fallback 超长输出截断为前 200 字符 + ...")
    void shouldTruncateFallbackSummary() {
        String rawOutput = "x".repeat(250);

        adapter.parseAndAppendExecutionRecord(TASK_ID, SUB_TASK_ID, "标题", AGENT_ID, rawOutput);

        assertThat(captureAppended().summary()).isEqualTo("x".repeat(200) + "...");
    }

    @Test
    @DisplayName("parseAndAppendExecutionRecord：输出为空白/null 时不落任何记录")
    void shouldAppendNothingWhenOutputBlank() {
        adapter.parseAndAppendExecutionRecord(TASK_ID, SUB_TASK_ID, "标题", AGENT_ID, "   ");
        adapter.parseAndAppendExecutionRecord(TASK_ID, SUB_TASK_ID, "标题", AGENT_ID, null);

        verify(taskRunningSpecService, never()).appendExecutionRecord(any(), any());
    }
}
