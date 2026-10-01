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
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * {@code SubTaskQueryPortAdapter} 单测（2026-10-01，W3 建；W4 补 {@code findById}；W7 补
 * {@code listByIds} / {@code isReady} / {@code mergeSkills}）。
 *
 * <p>覆盖「task 实体 → agent 域只读快照」的映射口径与空值边界：字段全量透传、
 * 入参为空/含 null 元素时不抛异常且绝不返回 {@code null}（{@code findById} 除外——
 * 它按 {@code getById} 语义在不存在时返回 {@code null}）；W7 新增的 {@code isReady} /
 * {@code mergeSkills} <b>不做任何本地判定</b>，仅验证「整体委派 + 空值收敛」。</p>
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
        subTask.setTitle("调度分析");
        when(subTaskService.getById(7L)).thenReturn(subTask);

        SubTaskSnapshot snapshot = adapter.findById(7L);

        assertThat(snapshot).isNotNull();
        assertThat(snapshot.id()).isEqualTo(7L);
        assertThat(snapshot.status()).isEqualTo(SubTaskStatus.ASSIGNED);
        assertThat(snapshot.taskId()).isEqualTo(8L);
        assertThat(snapshot.assignedAgentId()).isEqualTo(9L);
        assertThat(snapshot.title()).isEqualTo("调度分析");
    }

    @Test
    @DisplayName("实体 → 快照：id/status/taskId/assignedAgentId/context/title 全量透传")
    void shouldMapEntityToSnapshot() {
        SubTask subTask = new SubTask();
        subTask.setId(11L);
        subTask.setStatus(SubTaskStatus.REWORK);
        subTask.setTaskId(22L);
        subTask.setAssignedAgentId(33L);
        subTask.setContext(Map.of("k", "v"));
        subTask.setTitle("需求分析");
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
        assertThat(snapshot.title()).isEqualTo("需求分析");
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

    @Test
    @DisplayName("listByIds：入参为 null / 空时返回空列表，不触达 SubTaskService")
    void shouldReturnEmptyListWhenIdsBlank() {
        assertThat(adapter.listByIds(null)).isEmpty();
        assertThat(adapter.listByIds(List.of())).isEmpty();
        verifyNoInteractions(subTaskService);
    }

    @Test
    @DisplayName("listByIds：命中实体映射为快照（title 一并透传）")
    void shouldMapEntitiesOnListByIds() {
        SubTask dep = new SubTask();
        dep.setId(11L);
        dep.setStatus(SubTaskStatus.DONE);
        dep.setTaskId(100L);
        dep.setTitle("接口契约");
        when(subTaskService.listByIds(List.of(11L))).thenReturn(List.of(dep));

        List<SubTaskSnapshot> snapshots = adapter.listByIds(List.of(11L));

        assertThat(snapshots).hasSize(1);
        assertThat(snapshots.get(0).id()).isEqualTo(11L);
        assertThat(snapshots.get(0).status()).isEqualTo(SubTaskStatus.DONE);
        assertThat(snapshots.get(0).title()).isEqualTo("接口契约");
    }

    @Test
    @DisplayName("isReady：整体委派 SubTaskService.isReady（口径单源留提供方）")
    void shouldDelegateIsReady() {
        SubTask entity = new SubTask();
        entity.setId(7L);
        when(subTaskService.getById(7L)).thenReturn(entity);
        when(subTaskService.isReady(entity)).thenReturn(true);

        assertThat(adapter.isReady(7L)).isTrue();
    }

    @Test
    @DisplayName("isReady：子任务不存在时委派结果为 false（SubTaskService 语义）")
    void shouldDelegateIsReadyForMissingSubTask() {
        when(subTaskService.getById(9L)).thenReturn(null);
        when(subTaskService.isReady(null)).thenReturn(false);

        assertThat(adapter.isReady(9L)).isFalse();
    }

    @Test
    @DisplayName("mergeSkills：整体委派 SubTaskService.mergeSkills（合并规则单源留提供方）")
    void shouldDelegateMergeSkills() {
        SubTask entity = new SubTask();
        entity.setId(7L);
        when(subTaskService.getById(7L)).thenReturn(entity);
        when(subTaskService.mergeSkills(entity)).thenReturn(List.of("eng-doc-standard", "shell"));

        assertThat(adapter.mergeSkills(7L)).containsExactly("eng-doc-standard", "shell");
    }

    @Test
    @DisplayName("mergeSkills：提供方返回 null 时收敛为空列表（契约要求绝不返回 null）")
    void shouldCoerceNullMergeSkillsToEmptyList() {
        when(subTaskService.getById(7L)).thenReturn(null);
        when(subTaskService.mergeSkills(null)).thenReturn(null);

        assertThat(adapter.mergeSkills(7L)).isEmpty();
    }
}
