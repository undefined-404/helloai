package com.helloai.core.task.service.impl;

import com.helloai.core.task.service.TaskRunningSpecService;
import com.helloai.core.task.spec.ExecutionRecord;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/**
 * {@code TaskRunningSpecPortAdapter} 单测（2026-10-01 W7 新建）。
 *
 * <p>契约只暴露摘要字符串（消费方实际只读 {@code ExecutionRecord.summary()}），
 * 故覆盖两点：有记录取 summary、记录为 null 收敛为 null（消费方自行判空，本层不做
 * 「空串替代」这类隐式改写）。</p>
 *
 * <p><b>不存在「记录存在但 summary 为空」的用例</b>：{@code ExecutionRecord} 的
 * {@code summary} 是 builder 必填项（缺失即 {@code IllegalArgumentException: summary is required}），
 * 该分支在领域上不可达，为此造例只会测到 record 自身的校验、而非本适配器。</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("TaskRunningSpecPortAdapter")
class TaskRunningSpecPortAdapterTest {

    @Mock
    private TaskRunningSpecService taskRunningSpecService;

    @InjectMocks
    private TaskRunningSpecPortAdapter adapter;

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
}
