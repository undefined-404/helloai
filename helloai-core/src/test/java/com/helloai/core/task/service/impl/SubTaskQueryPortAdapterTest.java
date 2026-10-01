package com.helloai.core.task.service.impl;

import com.helloai.common.constant.SubTaskStatus;
import com.helloai.core.agent.port.SubTaskSnapshot;
import com.helloai.core.task.entity.SubTask;
import com.helloai.core.task.service.SubTaskService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.when;

/**
 * {@code SubTaskQueryPortAdapter} 单测（2026-10-01，W3 建；W4 补 {@code findById}）。
 *
 * <p>覆盖「task 实体 → agent 域只读快照」的映射口径与空值边界：字段全量透传、
 * 入参为空/含 null 元素时不抛异常且绝不返回 {@code null}（{@code findById} 除外——
 * 它按 {@code getById} 语义在不存在时返回 {@code null}）。</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("SubTaskQueryPortAdapter")
class SubTaskQueryPortAdapterTest {

    @Mock
    private SubTaskService subTaskService;

    @InjectMocks
    private SubTaskQueryPortAdapter adapter;

    @Test
    @DisplayName("findById：不存在返回 null（与 SubTaskService.getById 语义一致）")
    void shouldReturnNullWhenSubTaskMissing() {
        when(subTaskService.getById(9L)).thenReturn(null);

        assertThat(adapter.findById(9L)).isNull();
    }

    @Test
    @DisplayName("findById：实体 → 快照（字段全量透传）")
    void shouldMapEntityOnFindById() {
        SubTask subTask = new SubTask();
        subTask.setId(7L);
        subTask.setStatus(SubTaskStatus.ASSIGNED);
        subTask.setTaskId(8L);
        subTask.setAssignedAgentId(9L);
        when(subTaskService.getById(7L)).thenReturn(subTask);

        SubTaskSnapshot snapshot = adapter.findById(7L);

        assertThat(snapshot).isNotNull();
        assertThat(snapshot.id()).isEqualTo(7L);
        assertThat(snapshot.status()).isEqualTo(SubTaskStatus.ASSIGNED);
        assertThat(snapshot.taskId()).isEqualTo(8L);
        assertThat(snapshot.assignedAgentId()).isEqualTo(9L);
    }

    @Test
    @DisplayName("实体 → 快照：id/status/taskId/assignedAgentId/context 全量透传")
    void shouldMapEntityToSnapshot() {
        SubTask subTask = new SubTask();
        subTask.setId(11L);
        subTask.setStatus(SubTaskStatus.REWORK);
        subTask.setTaskId(22L);
        subTask.setAssignedAgentId(33L);
        subTask.setContext(Map.of("k", "v"));
        when(subTaskService.listRecentlyChanged(any(OffsetDateTime.class), anyInt()))
                .thenReturn(List.of(subTask));

        List<SubTaskSnapshot> snapshots = adapter.listRecentlyChanged(OffsetDateTime.now(), 10);

        assertThat(snapshots).hasSize(1);
        SubTaskSnapshot snapshot = snapshots.get(0);
        assertThat(snapshot.id()).isEqualTo(11L);
        assertThat(snapshot.status()).isEqualTo(SubTaskStatus.REWORK);
        assertThat(snapshot.taskId()).isEqualTo(22L);
        assertThat(snapshot.assignedAgentId()).isEqualTo(33L);
        assertThat(snapshot.context()).containsEntry("k", "v");
    }

    @Test
    @DisplayName("空源：返回空列表而非 null（契约要求）")
    void shouldReturnEmptyListForEmptySource() {
        when(subTaskService.listRecentlyChanged(any(OffsetDateTime.class), anyInt()))
                .thenReturn(List.of());

        assertThat(adapter.listRecentlyChanged(OffsetDateTime.now(), 10)).isEmpty();
    }

    @Test
    @DisplayName("源列表含 null 元素：跳过而不抛异常")
    void shouldSkipNullElements() {
        List<SubTask> source = new ArrayList<>();
        source.add(null);
        when(subTaskService.listRecentlyChanged(any(OffsetDateTime.class), anyInt()))
                .thenReturn(source);

        assertThat(adapter.listRecentlyChanged(OffsetDateTime.now(), 10)).isEmpty();
    }
}
