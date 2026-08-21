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

    // ──────────────── 契约先行拆解（Phase 2）：contract 往返 / 渲染 / 保留 ────────────────

    @Test
    @DisplayName("should write contract and keep it in toMap/fromMap round trip")
    void shouldUpdateContractAndRoundTrip() {
        Task task = taskWithContext(new HashMap<>());
        when(taskService.getById(100L)).thenReturn(task);

        Map<String, Object> contract = Map.of(
                "subTaskId", 11L, "title", "契约定义",
                "content", "接口签名：POST /api/orders", "backfilledAt", "2026-08-21T10:00:00+08:00");
        jsonbService.updateContract(100L, contract);

        TaskRunningSpec spec = jsonbService.getOrCreate(100L);
        assertThat(spec.contract()).isNotNull()
                .containsEntry("title", "契约定义")
                .containsEntry("content", "接口签名：POST /api/orders");

        // JSONB 边界往返：toMap → fromMap 契约不丢
        TaskRunningSpec roundTripped = TaskRunningSpec.fromMap(spec.toMap());
        assertThat(roundTripped.contract()).containsEntry("subTaskId", 11L)
                .containsEntry("backfilledAt", "2026-08-21T10:00:00+08:00");
    }

    @Test
    @DisplayName("should render contract section when present and omit it when absent")
    void shouldRenderContractSectionOnlyWhenPresent() {
        Task task = taskWithContext(new HashMap<>());
        when(taskService.getById(100L)).thenReturn(task);

        // 无契约：Prompt 段不含任务契约节
        assertThat(jsonbService.buildExecutorPromptSection(100L))
                .doesNotContain("## 任务契约");

        jsonbService.updateContract(100L, Map.of(
                "title", "接口契约 v1", "content", "GET /api/users → 200"));

        String section = jsonbService.buildExecutorPromptSection(100L);
        assertThat(section)
                .contains("## 任务契约")
                .contains("契约来源：接口契约 v1")
                .contains("GET /api/users → 200");
    }

    @Test
    @DisplayName("should keep contract when appending execution records (no loss on rebuild)")
    void shouldKeepContractOnAppendExecutionRecord() {
        Task task = taskWithContext(new HashMap<>());
        when(taskService.getById(100L)).thenReturn(task);

        jsonbService.updateContract(100L, Map.of(
                "title", "契约定义", "content", "错误码表：400/401/500"));
        jsonbService.appendExecutionRecord(100L, ExecutionRecord.builder()
                .subTaskId(11L).title("契约定义").summary("契约已产出").build());

        TaskRunningSpec spec = jsonbService.getOrCreate(100L);
        assertThat(spec.executionRecords()).hasSize(1);
        // appendExecutionRecord 重建 spec 时不得丢契约（回归防御）
        assertThat(spec.contract()).isNotNull()
                .containsEntry("content", "错误码表：400/401/500");
    }

    @Test
    @DisplayName("should overwrite contract on repeated update (latest wins)")
    void shouldOverwriteContractOnRepeatedUpdate() {
        Task task = taskWithContext(new HashMap<>());
        when(taskService.getById(100L)).thenReturn(task);

        jsonbService.updateContract(100L, Map.of("title", "v1", "content", "旧契约"));
        jsonbService.updateContract(100L, Map.of("title", "v2", "content", "新契约"));

        TaskRunningSpec spec = jsonbService.getOrCreate(100L);
        assertThat(spec.contract()).containsEntry("content", "新契约");
    }

    private static Task taskWithContext(Map<String, Object> context) {
        Task task = new Task();
        task.setId(100L);
        task.setContext(context);
        return task;
    }
}
