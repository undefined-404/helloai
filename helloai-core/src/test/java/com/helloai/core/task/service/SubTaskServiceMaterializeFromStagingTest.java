package com.helloai.core.task.service;

import com.helloai.common.constant.AcceptanceKind;
import com.helloai.common.constant.SubTaskStatus;
import com.helloai.common.constant.TaskStatus;
import com.helloai.core.task.entity.SubTask;
import com.helloai.core.task.port.SubTaskDraft;
import com.helloai.core.task.port.SubTaskView;
import com.helloai.core.task.port.TaskView;
import com.helloai.core.task.service.impl.SubTaskServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@code SubTaskService#materializeFromStaging} 语义测试（`AD-5b` 最终状态直插）。
 *
 * <p><b>为什么不再测「先插待审再转正」</b>：合并基线（`V146`）起 {@code PENDING_PLAN_REVIEW} 不再是合法落库状态，
 * 物化必须在插入前定稿。本类逐条钉住四种目标（PENDING / AWAITING_DERIVED / DONE / 依赖翻译），
 * 并断言没有任何行以旧草案状态落库。</p>
 *
 * <p>装配沿用 `CALLS_REAL_METHODS`：让被测方法走真实实现，`saveBatch` 由用例补 id（真实 MP 行为），
 * `updateDependsOn` / `listViewsByIds` 由用例 stub；事务代理不在裸对象里，整批原子性由
 * `@Transactional` 形式契约与真机覆盖。</p>
 */
@DisplayName("SubTaskService.materializeFromStaging（最终状态直插）")
class SubTaskServiceMaterializeFromStagingTest {

    private static final Long TASK_ID = 9001L;

    private final SubTaskServiceImpl service = mock(SubTaskServiceImpl.class, CALLS_REAL_METHODS);

    private final TaskDerivationService derivationService = mock(TaskDerivationService.class);

    private final AtomicLong idSeq = new AtomicLong(7000);

    private List<SubTask> saved;

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(service, "taskDerivationService", derivationService);
        doAnswer(invocation -> {
            Collection<SubTask> batch = invocation.getArgument(0);
            saved = new ArrayList<>(batch);
            for (SubTask subTask : saved) {
                if (subTask.getId() == null) {
                    subTask.setId(idSeq.incrementAndGet());
                }
            }
            return true;
        }).when(service).saveBatch(anyCollection());
        doNothing().when(service).updateDependsOn(anyLong(), anyList());
        doReturn(List.<SubTaskView>of()).when(service).listViewsByIds(anyCollection());
    }

    @Test
    @DisplayName("未派生：直接以 PENDING 落库，deadline 与 planConfirmedAt 插入前定稿")
    void shouldInsertPendingWithDeadlineAndConfirmedAt() {
        when(derivationService.findActivePlanDerivation(TASK_ID, "202611110001")).thenReturn(null);

        service.materializeFromStaging(TASK_ID, List.of(draft("202611110001")), Map.of(), 60);

        assertThat(saved).hasSize(1);
        SubTask row = saved.get(0);
        assertThat(row.getStatus()).isEqualTo(SubTaskStatus.PENDING);
        assertThat(row.getDeadline()).isNotNull();
        assertThat(row.getContext()).containsKey("planConfirmedAt").doesNotContainKey("derivedTaskId");
        assertThat(row.getDraftCode()).isEqualTo("202611110001");
    }

    @Test
    @DisplayName("已派生未完成：以 AWAITING_DERIVED 落库，回填 derivedTaskId 且不下发 deadline")
    void shouldInsertAwaitingDerivedAndBindParent() {
        when(derivationService.findActivePlanDerivation(TASK_ID, "202611110002"))
                .thenReturn(derivedTask(71L, TaskStatus.IN_PROGRESS));

        service.materializeFromStaging(TASK_ID, List.of(draft("202611110002")), Map.of(), 60);

        SubTask row = saved.get(0);
        assertThat(row.getStatus()).isEqualTo(SubTaskStatus.AWAITING_DERIVED);
        assertThat(row.getDeadline()).isNull();
        assertThat(row.getContext()).containsEntry("derivedTaskId", 71L).containsKey("planConfirmedAt");
        verify(derivationService).bindParentSubTask(71L, row.getId());
    }

    @Test
    @DisplayName("已派生且完成：直接以 DONE 落库，不等一个已经完成的任务")
    void shouldInsertDoneWhenDerivedTaskAlreadyDone() {
        when(derivationService.findActivePlanDerivation(TASK_ID, "202611110003"))
                .thenReturn(derivedTask(72L, TaskStatus.DONE));

        service.materializeFromStaging(TASK_ID, List.of(draft("202611110003")), Map.of(), 60);

        assertThat(saved.get(0).getStatus()).isEqualTo(SubTaskStatus.DONE);
        assertThat(saved.get(0).getDeadline()).isNull();
    }

    @Test
    @DisplayName("依赖按 code → 本次插入的新 id 翻译，不引用暂存区 id")
    void shouldTranslateDependsCodesToInsertedIds() {
        when(derivationService.findActivePlanDerivation(anyLong(), anyString())).thenReturn(null);
        SubTaskDraft first = draft("202611110010");
        SubTaskDraft second = draft("202611110011");

        service.materializeFromStaging(TASK_ID, List.of(first, second),
                Map.of("202611110011", List.of("202611110010")), null);

        long firstId = saved.get(0).getId();
        long secondId = saved.get(1).getId();
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<Long>> ids = ArgumentCaptor.forClass(List.class);
        verify(service).updateDependsOn(org.mockito.ArgumentMatchers.eq(secondId), ids.capture());
        assertThat(ids.getValue()).containsExactly(firstId);
        verify(service, org.mockito.Mockito.never())
                .updateDependsOn(org.mockito.ArgumentMatchers.eq(firstId), anyList());
    }

    @Test
    @DisplayName("空入参返回空列表，不写库")
    void shouldReturnEmptyOnEmptyInput() {
        assertThat(service.materializeFromStaging(TASK_ID, List.of(), Map.of(), 60)).isEmpty();
        assertThat(service.materializeFromStaging(null, List.of(draft("202611110001")), Map.of(), 60)).isEmpty();
        assertThat(saved).isNull();
    }

    @Test
    @DisplayName("落库状态只允许 PENDING / AWAITING_DERIVED / DONE")
    void shouldNeverInsertLegacyDraftStatus() {
        when(derivationService.findActivePlanDerivation(anyLong(), anyString())).thenReturn(null);

        service.materializeFromStaging(TASK_ID, List.of(draft("202611110020")), Map.of(), null);

        assertThat(saved).allSatisfy(row -> assertThat(row.getStatus())
                .isIn(SubTaskStatus.PENDING, SubTaskStatus.AWAITING_DERIVED, SubTaskStatus.DONE));
    }

    private static TaskView derivedTask(Long id, TaskStatus status) {
        return new TaskView(id, "派生任务" + id, "描述", null, null, null, null, null,
                status, null, null, null, null, null, null, null);
    }

    private static SubTaskDraft draft(String code) {
        return new SubTaskDraft(TASK_ID, "子任务" + code, "内容", "交付物", "通过",
                "MEDIUM", 0, List.of(), null, SubTaskStatus.PENDING, Map.of(), List.of(),
                AcceptanceKind.AUTOMATED, 1, code);
    }
}