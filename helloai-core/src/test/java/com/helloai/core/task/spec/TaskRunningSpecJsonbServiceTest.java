package com.helloai.core.task.spec;

import com.helloai.core.task.entity.Task;
import com.helloai.core.task.service.TaskService;
import com.helloai.core.task.service.impl.TaskRunningSpecJsonbServiceImpl;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("TaskRunningSpecJsonbService")
class TaskRunningSpecJsonbServiceTest {

    @Mock
    private TaskService taskService;

    @InjectMocks
    private TaskRunningSpecJsonbServiceImpl jsonbService;

    @Test
    @DisplayName("should keep both records when two predecessors append sequentially (multi-dep no overwrite)")
    void shouldKeepBothRecordsWhenTwoPredecessorsAppend() {
        Task task = taskWithContext(new HashMap<>());
        when(taskService.getById(100L)).thenReturn(task);

        jsonbService.appendExecutionRecord(100L, ExecutionRecord.builder()
                .subTaskId(11L).title("调研竞品").summary("竞品清单：A/B/C").build());
        jsonbService.appendExecutionRecord(100L, ExecutionRecord.builder()
                .subTaskId(12L).title("调研用户").summary("用户画像：白领").build());

        TaskRunningSpec spec = jsonbService.getOrCreate(100L);
        // 多前置回填互不覆盖：两条记录同时保留（防"只留第二次"回归）
        assertThat(spec.executionRecords()).hasSize(2);
        assertThat(spec.executionRecords()).extracting(ExecutionRecord::subTaskId)
                .containsExactly(11L, 12L);
        assertThat(spec.contextSummary()).contains("已完成 2 个子任务");
    }

    @Test
    @DisplayName("should overwrite only same subTaskId record on rework and keep others")
    void shouldOverwriteOnlySameSubTaskOnRework() {
        Task task = taskWithContext(new HashMap<>());
        when(taskService.getById(100L)).thenReturn(task);

        jsonbService.appendExecutionRecord(100L, ExecutionRecord.builder()
                .subTaskId(11L).title("调研竞品").summary("第一版").build());
        jsonbService.appendExecutionRecord(100L, ExecutionRecord.builder()
                .subTaskId(12L).title("调研用户").summary("用户画像：白领").build());
        // rework：11L 重做后回填第二版，只覆盖 11L 自己
        jsonbService.appendExecutionRecord(100L, ExecutionRecord.builder()
                .subTaskId(11L).title("调研竞品").summary("第二版（修正）").build());

        TaskRunningSpec spec = jsonbService.getOrCreate(100L);
        assertThat(spec.executionRecords()).hasSize(2);
        assertThat(spec.executionRecords()).extracting(ExecutionRecord::summary)
                .containsExactly("第二版（修正）", "用户画像：白领");
    }

    @Test
    @DisplayName("should findRecord by (taskId, subTaskId) and return null when absent")
    void shouldFindRecordByTaskAndSubTask() {
        Task task = taskWithContext(new HashMap<>());
        when(taskService.getById(100L)).thenReturn(task);

        jsonbService.appendExecutionRecord(100L, ExecutionRecord.builder()
                .subTaskId(11L).title("调研竞品").summary("竞品清单：A/B/C").build());
        jsonbService.appendExecutionRecord(100L, ExecutionRecord.builder()
                .subTaskId(12L).title("调研用户").summary("用户画像：白领").build());

        ExecutionRecord found = jsonbService.findRecord(100L, 12L);
        assertThat(found).isNotNull();
        assertThat(found.subTaskId()).isEqualTo(12L);
        assertThat(found.summary()).isEqualTo("用户画像：白领");

        assertThat(jsonbService.findRecord(100L, 99L)).isNull();
        assertThat(jsonbService.findRecord(null, 12L)).isNull();
    }

    @Test
    @DisplayName("should inject baseline once and skip re-initialization")
    void shouldInitializeBaselineOnce() {
        Task task = taskWithContext(new HashMap<>());
        when(taskService.getById(100L)).thenReturn(task);

        TaskBaseline baseline = TaskBaseline.builder().goal("做一个教程").build();
        jsonbService.initialize(100L, baseline);
        jsonbService.initialize(100L, TaskBaseline.builder().goal("不应覆盖").build());

        TaskRunningSpec spec = jsonbService.getOrCreate(100L);
        assertThat(spec.baseline()).isNotNull();
        assertThat(spec.baseline().goal()).isEqualTo("做一个教程");
    }

    private static Task taskWithContext(Map<String, Object> context) {
        Task task = new Task();
        task.setId(100L);
        task.setContext(context);
        return task;
    }
}
