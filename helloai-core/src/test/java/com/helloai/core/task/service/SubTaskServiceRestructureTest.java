package com.helloai.core.task.service;

import com.helloai.common.base.BizException;
import com.helloai.common.constant.SubTaskContextKeys;
import com.helloai.common.constant.SubTaskStatus;
import com.helloai.core.task.entity.SubTask;
import com.helloai.core.task.mapper.SubTaskMapper;
import com.helloai.core.task.port.ReviewPort;
import com.helloai.core.task.service.impl.SubTaskQueryServiceImpl;
import com.helloai.core.task.service.impl.SubTaskServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.beans.factory.ObjectProvider;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("SubTaskService 执行期重构（ADR-008 §11~§12）")
class SubTaskServiceRestructureTest {

    private static final long SUB_TASK_ID = 1L;
    private static final long TASK_ID = 9L;

    @Mock private SubTaskMapper subTaskMapper;
    @Mock private SubTaskStateMachineService stateMachineService;
    @Mock private SubTaskLeaseService leaseService;
    @Mock private TaskDerivationService taskDerivationService;
    @Mock private ObjectProvider<TaskService> taskServiceProvider;
    @Mock private ReviewPort reviewPort;

    private SubTaskService subTaskService;

    @BeforeEach
    void setUp() {
        SubTaskQueryService queryService = new SubTaskQueryServiceImpl(subTaskMapper, taskServiceProvider, reviewPort);
        // 用真实门禁（确定性层走纯函数；语义层无材料时自动跳过），语义判据另见 RuntimeContractGateServiceTest
        RuntimeContractGateService gateService = new com.helloai.core.task.service.impl.RuntimeContractGateServiceImpl(
                mock(org.springframework.beans.factory.ObjectProvider.class),
                new com.fasterxml.jackson.databind.ObjectMapper());
        subTaskService = new SubTaskServiceImpl(subTaskMapper, queryService, stateMachineService,
                leaseService, taskDerivationService, gateService);
        when(subTaskMapper.updateById(any(SubTask.class))).thenReturn(1);
    }

    private SubTask subTask(SubTaskStatus status) {
        SubTask subTask = new SubTask();
        subTask.setId(SUB_TASK_ID);
        subTask.setTaskId(TASK_ID);
        subTask.setTitle("写报告");
        subTask.setContent("读取 matchResult");
        subTask.setDeliverable("输出 matchResult 字段");
        subTask.setAcceptance("含 matchResult");
        subTask.setStatus(status);
        subTask.setContext(new LinkedHashMap<>());
        return subTask;
    }

    @Test
    @DisplayName("进入重构：BLOCKED → RESTRUCTURING，轮次递增、记进入时刻")
    void shouldEnterRestructuringWithRound() {
        SubTask subTask = subTask(SubTaskStatus.BLOCKED);
        subTask.getContext().put(SubTaskContextKeys.RESTRUCTURE_ROUND, 1);
        when(subTaskMapper.selectById(SUB_TASK_ID)).thenReturn(subTask);

        subTaskService.enterRestructuring(SUB_TASK_ID, "外部接口不可用");

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> patch = ArgumentCaptor.forClass(Map.class);
        verify(stateMachineService).changeStatus(eq(SUB_TASK_ID), eq(SubTaskStatus.RESTRUCTURING),
                isNull(), patch.capture());
        assertThat(patch.getValue().get(SubTaskContextKeys.RESTRUCTURE_ROUND)).isEqualTo(2);
        assertThat(patch.getValue()).containsKeys(SubTaskContextKeys.RESTRUCTURE_ENTERED_AT,
                SubTaskContextKeys.RESTRUCTURE_REASON);
    }

    @Test
    @DisplayName("进入重构：非四态入口一律拒绝")
    void shouldRejectEnterFromWrongStatus() {
        when(subTaskMapper.selectById(SUB_TASK_ID)).thenReturn(subTask(SubTaskStatus.PENDING));

        assertThatThrownBy(() -> subTaskService.enterRestructuring(SUB_TASK_ID, null))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("可转入重构");
        verify(stateMachineService, never()).changeStatus(any(), any(), any(), anyMap());
    }

    @Test
    @DisplayName("执行期编辑：旧值进 history，四要素按入参更新")
    void shouldAppendHistoryAndUpdateFields() {
        SubTask subTask = subTask(SubTaskStatus.RESTRUCTURING);
        when(subTaskMapper.selectById(SUB_TASK_ID)).thenReturn(subTask);

        subTaskService.updateRestructureContent(SUB_TASK_ID, "写新报告", "读取 dataResult",
                "输出 dataResult", "含 dataResult", "只读", "REVIEWED", 77L);

        ArgumentCaptor<SubTask> captor = ArgumentCaptor.forClass(SubTask.class);
        verify(subTaskMapper).updateById(captor.capture());
        SubTask saved = captor.getValue();
        assertThat(saved.getTitle()).isEqualTo("写新报告");
        assertThat(saved.getDeliverable()).isEqualTo("输出 dataResult");
        assertThat(saved.getConstraints()).isEqualTo("只读");

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> history = (List<Map<String, Object>>) saved.getContext()
                .get(SubTaskContextKeys.RESTRUCTURE_HISTORY);
        assertThat(history).hasSize(1);
        assertThat(history.get(0))
                .containsEntry("title", "写报告")
                .containsEntry("deliverable", "输出 matchResult 字段")
                .containsEntry("editorId", 77L);
    }

    @Test
    @DisplayName("执行期编辑：非重构中状态拒绝")
    void shouldRejectEditOutsideRestructuring() {
        when(subTaskMapper.selectById(SUB_TASK_ID)).thenReturn(subTask(SubTaskStatus.IN_PROGRESS));

        assertThatThrownBy(() -> subTaskService.updateRestructureContent(SUB_TASK_ID, "t", null,
                null, null, null, null, 1L))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("仅重构中状态可编辑");
        verify(subTaskMapper, never()).updateById(any(SubTask.class));
    }

    @Test
    @DisplayName("回链 G5：下游引用的标识在整改后消失 → 拒绝，不转 PENDING")
    void shouldRejectApplyWhenDownstreamReferenceBreaks() {
        SubTask current = subTask(SubTaskStatus.RESTRUCTURING);
        current.setDeliverable("输出 dataResult 字段");
        current.setAcceptance("含 dataResult");
        current.setContent("读取 dataResult");
        putBaseline(current, "输出 matchResult 字段", "含 matchResult");
        when(subTaskMapper.selectById(SUB_TASK_ID)).thenReturn(current);

        SubTask downstream = new SubTask();
        downstream.setId(2L);
        downstream.setTaskId(TASK_ID);
        downstream.setTitle("渲染结果页");
        downstream.setStatus(SubTaskStatus.PENDING);
        downstream.setContent("页面读取 matchResult 渲染列表");
        downstream.setDependsOn(List.of(SUB_TASK_ID));
        when(subTaskMapper.selectList(any())).thenReturn(List.of(downstream));

        assertThatThrownBy(() -> subTaskService.applyRestructure(SUB_TASK_ID))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("G5")
                .hasMessageContaining("matchResult");
        verify(stateMachineService, never()).changeStatus(any(), any(), any(), anyMap());
    }

    @Test
    @DisplayName("回链 G5：契约未破坏 → 落 findings 并转 PENDING")
    void shouldApplyAndReturnToPending() {
        SubTask current = subTask(SubTaskStatus.RESTRUCTURING);
        putBaseline(current, "输出 matchResult 字段", "含 matchResult");
        when(subTaskMapper.selectById(SUB_TASK_ID)).thenReturn(current);
        when(subTaskMapper.selectList(any())).thenReturn(List.of());

        subTaskService.applyRestructure(SUB_TASK_ID);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> patch = ArgumentCaptor.forClass(Map.class);
        verify(stateMachineService).changeStatus(eq(SUB_TASK_ID), eq(SubTaskStatus.PENDING),
                isNull(), patch.capture());
        assertThat(patch.getValue()).containsKeys(SubTaskContextKeys.RESTRUCTURE_GATE_FINDINGS,
                SubTaskContextKeys.RESTRUCTURE_APPLIED_AT);
    }

    private static void putBaseline(SubTask subTask, String deliverable, String acceptance) {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("title", subTask.getTitle());
        snapshot.put("content", subTask.getContent());
        snapshot.put("deliverable", deliverable);
        snapshot.put("acceptance", acceptance);
        snapshot.put("constraints", null);
        snapshot.put("editedAt", "2026-11-10T00:00:00Z");
        snapshot.put("editorId", 1L);
        subTask.getContext().put(SubTaskContextKeys.RESTRUCTURE_HISTORY, List.of(snapshot));
    }
}
