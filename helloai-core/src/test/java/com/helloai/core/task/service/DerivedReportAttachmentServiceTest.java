package com.helloai.core.task.service;

import com.helloai.common.constant.TaskStatus;
import com.helloai.core.task.entity.Attachment;
import com.helloai.core.task.entity.SubTask;
import com.helloai.core.task.entity.Task;
import com.helloai.core.task.mapper.TaskMapper;
import com.helloai.core.task.service.impl.DerivedReportAttachmentServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("派生报告回挂（ADR-008 §15）")
class DerivedReportAttachmentServiceTest {

    private static final long DERIVED_ID = 100L;
    private static final long PARENT_ID = 200L;

    @Mock private TaskMapper taskMapper;
    @Mock private SubTaskService subTaskService;
    @Mock private AttachmentService attachmentService;

    private DerivedReportAttachmentService service;

    @BeforeEach
    void setUp() {
        service = new DerivedReportAttachmentServiceImpl(taskMapper, subTaskService, attachmentService);
    }

    private Task derived(TaskStatus status, String report) {
        Task task = new Task();
        task.setId(DERIVED_ID);
        task.setParentSubTaskId(PARENT_ID);
        task.setStatus(status);
        task.setFinalReport(report);
        return task;
    }

    private SubTask parent() {
        SubTask subTask = new SubTask();
        subTask.setId(PARENT_ID);
        subTask.setTaskId(9L);
        subTask.setTitle("写周报");
        subTask.setContext(Map.of("lastExecution", Map.of("output", "原产出：三条完成事项")));
        return subTask;
    }

    @Test
    @DisplayName("DONE + 有报告 → 合并「原产出 + 报告」挂到父任务")
    void shouldAttachMergedReport() {
        when(taskMapper.selectById(DERIVED_ID)).thenReturn(derived(TaskStatus.DONE, "派生报告正文"));
        when(subTaskService.getById(PARENT_ID)).thenReturn(parent());
        when(attachmentService.listActive(PARENT_ID)).thenReturn(List.of());

        assertThat(service.attachIfDue(DERIVED_ID)).isTrue();

        ArgumentCaptor<byte[]> content = ArgumentCaptor.forClass(byte[].class);
        verify(attachmentService).registerSystem(eq(PARENT_ID), eq("派生报告-" + DERIVED_ID + ".md"),
                anyString(), content.capture(), eq("system"));
        String merged = new String(content.getValue(), StandardCharsets.UTF_8);
        assertThat(merged).contains("派生任务报告回挂")
                .contains("原产出：三条完成事项")
                .contains("派生报告正文")
                .contains("派生报告为最新结论");
    }

    @Test
    @DisplayName("同名 ACTIVE 附件已存在 → 幂等跳过")
    void shouldSkipWhenAlreadyAttached() {
        when(taskMapper.selectById(DERIVED_ID)).thenReturn(derived(TaskStatus.DONE, "报告"));
        when(subTaskService.getById(PARENT_ID)).thenReturn(parent());
        Attachment existing = new Attachment();
        existing.setFileName("派生报告-" + DERIVED_ID + ".md");
        when(attachmentService.listActive(PARENT_ID)).thenReturn(List.of(existing));

        assertThat(service.attachIfDue(DERIVED_ID)).isFalse();
        verify(attachmentService, never()).registerSystem(anyLong(), anyString(), anyString(), any(), anyString());
    }

    @Test
    @DisplayName("报告为空 / 非 DONE / 无父锚 → 不回挂")
    void shouldSkipWhenNotDue() {
        when(taskMapper.selectById(DERIVED_ID)).thenReturn(derived(TaskStatus.DONE, "  "));
        assertThat(service.attachIfDue(DERIVED_ID)).isFalse();

        when(taskMapper.selectById(DERIVED_ID)).thenReturn(derived(TaskStatus.IN_PROGRESS, "报告"));
        assertThat(service.attachIfDue(DERIVED_ID)).isFalse();

        Task orphan = derived(TaskStatus.DONE, "报告");
        orphan.setParentSubTaskId(null);
        when(taskMapper.selectById(DERIVED_ID)).thenReturn(orphan);
        assertThat(service.attachIfDue(DERIVED_ID)).isFalse();

        verify(attachmentService, never()).registerSystem(anyLong(), anyString(), anyString(), any(), anyString());
    }
}
