package com.helloai.core.planner;

import com.helloai.common.base.BizException;
import com.helloai.common.constant.AcceptanceKind;
import com.helloai.common.constant.SubTaskContextKeys;
import com.helloai.common.constant.TaskPriority;
import com.helloai.common.constant.UncertaintyKind;
import com.helloai.core.planner.entity.PlanDraft;
import com.helloai.core.planner.mapper.PlanDraftMapper;
import com.helloai.core.planner.port.PlanDraftView;
import com.helloai.core.planner.service.impl.PlanDraftServiceImpl;
import com.helloai.core.task.port.TaskView;
import com.helloai.core.task.port.UncertaintyDraft;
import com.helloai.core.task.service.TaskService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("PlanDraftService 暂存区读侧（AD-5a-3）")
class PlanDraftServiceImplTest {

    private static final long TASK_ID = 100L;

    @Mock private PlanDraftMapper planDraftMapper;
    @Mock private TaskService taskService;

    private PlanDraftServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new PlanDraftServiceImpl(planDraftMapper, taskService);
        when(taskService.getView(TASK_ID)).thenReturn(new TaskView(TASK_ID, "任务", "描述",
                null, null, null, null, Map.of()));
    }

    private static PlanDraft row(long id, String code, int seq) {
        PlanDraft d = new PlanDraft();
        d.setId(id);
        d.setTaskId(TASK_ID);
        d.setCode(code);
        d.setRound(2);
        d.setDraftSeq(seq);
        d.setTitle("草案" + seq);
        d.setAcceptance("验收" + seq);
        d.setAcceptanceKind(AcceptanceKind.STRUCTURAL);
        d.setPriority(TaskPriority.HIGH);
        d.setContract(seq == 1 ? 1 : 0);
        d.setRequiredSkills(List.of("s" + seq));
        d.setDependsOnCodes(seq == 1 ? List.of() : List.of(code.substring(0, code.length() - 1) + "1"));
        d.setContext(Map.of("k", "v"));
        d.setUpdateTime(OffsetDateTime.parse("2026-11-10T10:00:00+08:00"));
        return d;
    }

    @Test
    @DisplayName("按 code 序返回当前轮 ACTIVE 行，字段完整映射")
    void shouldListInCodeOrder() {
        when(planDraftMapper.findMaxActiveRound(TASK_ID)).thenReturn(2);
        when(planDraftMapper.listActiveIdsByRound(TASK_ID, 2)).thenReturn(List.of(1L, 2L));
        // selectBatchIds 不保证顺序：故意倒序返回
        when(planDraftMapper.selectBatchIds(List.of(1L, 2L)))
                .thenReturn(List.of(row(2L, "202611100002", 2), row(1L, "202611100001", 1)));

        List<PlanDraftView> views = service.listByTask(TASK_ID);

        assertThat(views).extracting(PlanDraftView::code)
                .containsExactly("202611100001", "202611100002");
        PlanDraftView first = views.get(0);
        assertThat(first.id()).isEqualTo(1L);
        assertThat(first.draftSeq()).isEqualTo(1);
        assertThat(first.contract()).isEqualTo(1);
        assertThat(first.priority()).isEqualTo(TaskPriority.HIGH);
        assertThat(first.requiredSkills()).containsExactly("s1");
        assertThat(first.dependsOnCodes()).isEmpty();
        assertThat(views.get(1).dependsOnCodes()).containsExactly("202611100001");
        assertThat(first.updateTime()).isNotNull();
    }

    @Test
    @DisplayName("无当前轮 / 无 ACTIVE 行 → 空列表（调用方回落旧读法）")
    void shouldReturnEmptyWhenNoStagingRows() {
        when(planDraftMapper.findMaxActiveRound(TASK_ID)).thenReturn(0);
        assertThat(service.listByTask(TASK_ID)).isEmpty();

        when(planDraftMapper.findMaxActiveRound(TASK_ID)).thenReturn(1);
        when(planDraftMapper.listActiveIdsByRound(TASK_ID, 1)).thenReturn(List.of());
        assertThat(service.listByTask(TASK_ID)).isEmpty();
    }

    @Test
    @DisplayName("任务不存在 → 400")
    void shouldRejectMissingTask() {
        when(taskService.getView(anyLong())).thenReturn(null);

        assertThatThrownBy(() -> service.listByTask(TASK_ID))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("任务不存在");
    }

    @Test
    @DisplayName("AD-5a-3b-2b 暂存区编辑：uncertainty 归一 + 显式 kind 落 context + 改写验收清旧标注")
    void shouldNormalizeOnStagingEdit() {
        PlanDraft row = row(1L, "202611100001", 1);
        row.setContent("内容说明");
        row.setDeliverable("交付物清单");
        row.setContext(new java.util.LinkedHashMap<>(Map.of(
                SubTaskContextKeys.ACCEPTANCE_PRECONDITIONS, List.of("U1"))));
        when(planDraftMapper.findIdByCode(TASK_ID, "202611100001")).thenReturn(1L);
        when(planDraftMapper.selectById(1L)).thenReturn(row);

        service.updateByCode(TASK_ID, "202611100001", null, null, "输出不少于 5 个章节且每章有结论",
                null, null,
                List.of(new UncertaintyDraft("NOT_A_KIND", "   ", null, null),
                        new UncertaintyDraft("NOT_A_KIND", "正常缺口", null, null)),
                "STRUCTURAL");

        assertThat(row.getUncertainties()).hasSize(1);
        assertThat(row.getUncertainties().get(0).kind()).isEqualTo(UncertaintyKind.UNCONFIRMED.name());
        assertThat(row.getContext()).containsEntry(SubTaskContextKeys.ACCEPTANCE_KIND_DECLARED, "STRUCTURAL");
        assertThat(row.getContext()).doesNotContainKey(SubTaskContextKeys.ACCEPTANCE_PRECONDITIONS);
        assertThat(row.getContext().get(SubTaskContextKeys.GATE_FINDINGS)).isNotNull();
    }

    @Test
    @DisplayName("AD-5a-3b-2b 暂存区编辑：单条门禁 block 即拒，不落库")
    void shouldRejectWhenStagingEditFailsGate() {
        PlanDraft row = row(1L, "202611100001", 1);
        row.setContent("内容说明");
        row.setDeliverable("交付物清单");
        row.setAcceptance("太短");
        when(planDraftMapper.findIdByCode(TASK_ID, "202611100001")).thenReturn(1L);
        when(planDraftMapper.selectById(1L)).thenReturn(row);

        assertThatThrownBy(() -> service.updateByCode(TASK_ID, "202611100001", null, null, null,
                null, null, null, null))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("草案未通过门禁");
    }
}