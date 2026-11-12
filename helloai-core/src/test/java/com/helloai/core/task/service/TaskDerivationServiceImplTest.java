package com.helloai.core.task.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.helloai.common.base.BizException;
import com.helloai.common.constant.SubTaskStatus;
import com.helloai.common.constant.TaskArchetype;
import com.helloai.common.constant.TaskStatus;
import com.helloai.core.task.entity.Task;
import com.helloai.core.task.mapper.TaskMapper;
import com.helloai.core.task.port.SubTaskView;
import com.helloai.core.task.port.TaskView;
import com.helloai.core.task.service.impl.TaskDerivationServiceImpl;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DuplicateKeyException;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link TaskDerivationServiceImpl} 单元测试（ADR-008 §5 草案期派生写原语）。
 *
 * <p>覆盖「Y 从草案继承什么」「深度闸门」「并发双击转 400」「父锚回填的双锚互斥」四类语义。
 * TableInfo 预热规避 MP lambda 缓存坑（{@code TeamServiceImplTest} 同款）。</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("TaskDerivationService 草案期派生")
class TaskDerivationServiceImplTest {

    private static final Long PARENT_TASK_ID = 100L;
    private static final String CODE = "202611080002";
    private static final Long DERIVED_TASK_ID = 71L;

    @BeforeAll
    static void initTableInfo() {
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "");
        TableInfoHelper.initTableInfo(assistant, Task.class);
    }

    @Mock
    private TaskMapper taskMapper;
    @Mock
    private com.helloai.core.task.mapper.SubTaskMapper subTaskMapper;
    @Mock
    private com.helloai.core.task.service.SubTaskStateMachineService subTaskStateMachineService;

    private TaskDerivationServiceImpl service() {
        return new TaskDerivationServiceImpl(taskMapper, subTaskMapper, subTaskStateMachineService);
    }

    private static Task parent(int depth) {
        Task t = new Task();
        t.setId(PARENT_TASK_ID);
        t.setTitle("父任务");
        t.setDepth(depth);
        t.setRootTaskId(depth == 0 ? PARENT_TASK_ID : 90L);
        t.setStatus(TaskStatus.AWAITING_PLAN_CONFIRM);
        t.setOwnerId(7L);
        t.setSlaMinutes(120);
        t.setPriority("HIGH");
        t.setTaskArchetype(TaskArchetype.BUILD);
        t.setAgentPolicy(Map.of("mode", "single"));
        t.setRequiredSkills(List.of("parent-skill"));
        return t;
    }

    private static SubTaskView draft() {
        return new SubTaskView(5L, PARENT_TASK_ID, SubTaskStatus.PENDING, null, null, "拆出报表引擎", "实现报表引擎并接入数据源", "引擎代码完整版本", "运行 pytest 输出 5 passed", null, null, Map.of(), List.of(), List.of(), null, "MEDIUM", 0, List.of("draft-skill"), null, Map.of(), null, null, 0, null, null, CODE);
    }

    @Test
    @DisplayName("派生：继承父任务 archetype/owner/SLA/策略，技能与优先级取草案，depth=父+1、round=历史最大+1")
    void shouldCreateDerivedTaskInheritingParent() {
        when(taskMapper.selectById(PARENT_TASK_ID)).thenReturn(parent(0));
        when(taskMapper.selectMaxPlanDerivationRound(PARENT_TASK_ID, CODE)).thenReturn(2);
        doAnswer(inv -> {
            ((Task) inv.getArgument(0)).setId(DERIVED_TASK_ID);
            return 1;
        }).when(taskMapper).insert(any(Task.class));

        TaskView view = service().createPlanDerivation(PARENT_TASK_ID, CODE, draft());

        ArgumentCaptor<Task> captor = ArgumentCaptor.forClass(Task.class);
        verify(taskMapper).insert(captor.capture());
        Task y = captor.getValue();
        assertThat(y.getTitle()).isEqualTo("拆出报表引擎");
        assertThat(y.getDescription())
                .contains("实现报表引擎并接入数据源")
                .contains("交付物：引擎代码完整版本")
                .contains("验收判据：运行 pytest 输出 5 passed");
        assertThat(y.getStatus()).isEqualTo(TaskStatus.PENDING);
        assertThat(y.getParentTaskId()).isEqualTo(PARENT_TASK_ID);
        assertThat(y.getParentDraftCode()).isEqualTo(CODE);
        assertThat(y.getParentSubTaskId()).isNull();
        assertThat(y.getRootTaskId()).isEqualTo(PARENT_TASK_ID);
        assertThat(y.getDepth()).isEqualTo(1);
        assertThat(y.getDerivationReason()).isEqualTo("PLAN");
        assertThat(y.getDerivationRound()).isEqualTo(3);
        // archetype 继承父任务，不重新判定；技能取草案（非父任务回落）
        assertThat(y.getTaskArchetype()).isEqualTo(TaskArchetype.BUILD);
        assertThat(y.getOwnerId()).isEqualTo(7L);
        assertThat(y.getSlaMinutes()).isEqualTo(120);
        assertThat(y.getPriority()).isEqualTo("MEDIUM");
        assertThat(y.getRequiredSkills()).containsExactly("draft-skill");
        assertThat(y.getAgentPolicy()).containsEntry("mode", "single");
        assertThat(view.id()).isEqualTo(DERIVED_TASK_ID);
        assertThat(view.depth()).isEqualTo(1);
    }

    @Test
    @DisplayName("深度闸门：父任务已在第 2 层 ⇒ 派生第 3 层被拒（400，不落库）")
    void shouldRejectWhenDepthExceeded() {
        when(taskMapper.selectById(PARENT_TASK_ID)).thenReturn(parent(2));

        assertThatThrownBy(() -> service().createPlanDerivation(PARENT_TASK_ID, CODE, draft()))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("上限")
                .extracting(e -> ((BizException) e).getCode())
                .isEqualTo(400);
        verify(taskMapper, never()).insert(any(Task.class));
    }

    @Test
    @DisplayName("并发双击：唯一索引冲突 ⇒ 转 400（用户可纠正），不冒 500")
    void shouldMapDuplicateKeyToBadRequest() {
        when(taskMapper.selectById(PARENT_TASK_ID)).thenReturn(parent(0));
        when(taskMapper.selectMaxPlanDerivationRound(PARENT_TASK_ID, CODE)).thenReturn(0);
        when(taskMapper.insert(any(Task.class))).thenThrow(new DuplicateKeyException("uk_task_parent_draft_active"));

        assertThatThrownBy(() -> service().createPlanDerivation(PARENT_TASK_ID, CODE, draft()))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("已有进行中的派生任务")
                .extracting(e -> ((BizException) e).getCode())
                .isEqualTo(400);
    }

    @Test
    @DisplayName("父锚回填：写 parent_sub_task_id 同时清 parent_draft_code（chk_task_parent_anchor 双锚互斥）")
    void shouldBindAndClearDraftCode() {
        when(taskMapper.update(eq(null), any())).thenReturn(1);

        service().bindParentSubTask(DERIVED_TASK_ID, 5L);

        verify(taskMapper).update(eq(null), any());
    }

    @Test
    @DisplayName("父锚回填幂等：update 落空但已是同一父 SubTask ⇒ 不抛")
    void shouldTolerateIdempotentBind() {
        Task bound = new Task();
        bound.setId(DERIVED_TASK_ID);
        bound.setParentSubTaskId(5L);
        when(taskMapper.update(eq(null), any())).thenReturn(0);
        when(taskMapper.selectById(DERIVED_TASK_ID)).thenReturn(bound);

        service().bindParentSubTask(DERIVED_TASK_ID, 5L);
        // 无异常即幂等
    }

    @Test
    @DisplayName("父锚回填冲突：已绑定其他父 SubTask ⇒ 400，拒绝覆盖")
    void shouldRejectRebindingToDifferentSubTask() {
        Task bound = new Task();
        bound.setId(DERIVED_TASK_ID);
        bound.setParentSubTaskId(99L);
        when(taskMapper.update(eq(null), any())).thenReturn(0);
        when(taskMapper.selectById(DERIVED_TASK_ID)).thenReturn(bound);

        assertThatThrownBy(() -> service().bindParentSubTask(DERIVED_TASK_ID, 5L))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("拒绝覆盖")
                .extracting(e -> ((BizException) e).getCode())
                .isEqualTo(400);
    }

    @Test
    @DisplayName("参数缺失：空 parentTaskId / 空 code / 空草案 ⇒ 400（不查库）")
    void shouldRejectIncompleteArguments() {
        assertThatThrownBy(() -> service().createPlanDerivation(null, CODE, draft()))
                .isInstanceOf(BizException.class);
        assertThatThrownBy(() -> service().createPlanDerivation(PARENT_TASK_ID, " ", draft()))
                .isInstanceOf(BizException.class);
        assertThatThrownBy(() -> service().createPlanDerivation(PARENT_TASK_ID, CODE, null))
                .isInstanceOf(BizException.class);
        verify(taskMapper, never()).selectById(anyLong());
    }

    @Test
    @DisplayName("findActivePlanDerivation：参数不全直接返回 null（不查库）")
    void shouldReturnNullWhenLookupArgumentsIncomplete() {
        assertThat(service().findActivePlanDerivation(null, CODE)).isNull();
        assertThat(service().findActivePlanDerivation(PARENT_TASK_ID, " ")).isNull();
        verify(taskMapper, never()).selectOne(any());
        verify(taskMapper, never()).selectList(any());
    }

    @Test
    @DisplayName("无活跃派生时返回空列表（不做全表查询兜底）")
    void shouldReturnEmptyListWhenParentMissing() {
        assertThat(service().listActivePlanDerivations(null)).isEmpty();
        verify(taskMapper, never()).selectList(any());
    }

    @Test
    @DisplayName("执行期派生：RESTRUCTURING 父 → 建 Y（parent_sub_task_id 锚）+ 同事务置 AWAITING_DERIVED")
    void shouldCreateRestructureTaskAndFlipParent() {
        com.helloai.core.task.entity.SubTask parentSub = new com.helloai.core.task.entity.SubTask();
        parentSub.setId(5L);
        parentSub.setTaskId(PARENT_TASK_ID);
        parentSub.setStatus(SubTaskStatus.RESTRUCTURING);
        when(subTaskMapper.selectById(5L)).thenReturn(parentSub);
        when(taskMapper.selectById(PARENT_TASK_ID)).thenReturn(parent(0));
        when(taskMapper.selectCount(any())).thenReturn(2L);
        doAnswer(inv -> {
            ((Task) inv.getArgument(0)).setId(DERIVED_TASK_ID);
            return 1;
        }).when(taskMapper).insert(any(Task.class));

        com.helloai.core.task.port.TaskDraft draft = new com.helloai.core.task.port.TaskDraft(
                "重构后的选点分析", "重做选点", TaskStatus.PENDING, Map.of(), "BUILD", List.of(), 7L);
        TaskView view = service().createRestructureTask(draft, 5L);

        ArgumentCaptor<Task> captor = ArgumentCaptor.forClass(Task.class);
        verify(taskMapper).insert(captor.capture());
        Task y = captor.getValue();
        assertThat(y.getParentSubTaskId()).isEqualTo(5L);
        assertThat(y.getParentTaskId()).isNull();
        assertThat(y.getParentDraftCode()).isNull();
        assertThat(y.getRootTaskId()).isEqualTo(PARENT_TASK_ID);
        assertThat(y.getDepth()).isEqualTo(1);
        assertThat(y.getDerivationReason()).isEqualTo("RESTRUCTURE");
        assertThat(y.getDerivationRound()).isEqualTo(3);
        assertThat(y.getTaskArchetype()).isEqualTo(TaskArchetype.BUILD);
        verify(subTaskStateMachineService).changeStatus(eq(5L), eq(SubTaskStatus.AWAITING_DERIVED),
                isNull(), anyMap());
        assertThat(view.id()).isEqualTo(DERIVED_TASK_ID);
    }

    @Test
    @DisplayName("执行期派生：Y 继承父任务的 executionEnv（只搬这一个键，不整份复制 context）")
    void shouldInheritExecutionEnvFromParentTask() {
        com.helloai.core.task.entity.SubTask parentSub = new com.helloai.core.task.entity.SubTask();
        parentSub.setId(5L);
        parentSub.setTaskId(PARENT_TASK_ID);
        parentSub.setStatus(SubTaskStatus.RESTRUCTURING);
        when(subTaskMapper.selectById(5L)).thenReturn(parentSub);
        Map<String, Object> env = Map.of("repo", "E:/yhzx/1027/helloai", "runtime", List.of("jdk17"));
        Task parentTask = parent(0);
        parentTask.setContext(new java.util.LinkedHashMap<>(Map.of(
                "executionEnv", env,
                "requirementPackage", Map.of("goal", "父任务的需求包"))));
        when(taskMapper.selectById(PARENT_TASK_ID)).thenReturn(parentTask);
        when(taskMapper.selectCount(any())).thenReturn(0L);
        doAnswer(inv -> {
            ((Task) inv.getArgument(0)).setId(DERIVED_TASK_ID);
            return 1;
        }).when(taskMapper).insert(any(Task.class));

        com.helloai.core.task.port.TaskDraft draft = new com.helloai.core.task.port.TaskDraft(
                "重构后的选点分析", "重做选点", TaskStatus.PENDING, Map.of(), "BUILD", List.of(), 7L);
        service().createRestructureTask(draft, 5L);

        ArgumentCaptor<Task> captor = ArgumentCaptor.forClass(Task.class);
        verify(taskMapper).insert(captor.capture());
        assertThat(captor.getValue().getContext())
                .as("Y 与 X 跑在同一台机器同一个仓库；不继承的话 Y 拆解时只能渲染「未声明」")
                .containsEntry("executionEnv", env);
        assertThat(captor.getValue().getContext())
                .as("父任务的 requirementPackage 属于另一个需求，整份复制会让 Y 读到过期上下文")
                .doesNotContainKey("requirementPackage");
    }

    @Test
    @DisplayName("执行期派生：draft 显式给 executionEnv 时以它为准，不被父任务覆盖")
    void shouldPreferDraftExecutionEnvOverParent() {
        com.helloai.core.task.entity.SubTask parentSub = new com.helloai.core.task.entity.SubTask();
        parentSub.setId(5L);
        parentSub.setTaskId(PARENT_TASK_ID);
        parentSub.setStatus(SubTaskStatus.RESTRUCTURING);
        when(subTaskMapper.selectById(5L)).thenReturn(parentSub);
        Task parentTask = parent(0);
        parentTask.setContext(Map.of("executionEnv", Map.of("repo", "父路径")));
        when(taskMapper.selectById(PARENT_TASK_ID)).thenReturn(parentTask);
        when(taskMapper.selectCount(any())).thenReturn(0L);
        doAnswer(inv -> {
            ((Task) inv.getArgument(0)).setId(DERIVED_TASK_ID);
            return 1;
        }).when(taskMapper).insert(any(Task.class));

        Map<String, Object> draftEnv = Map.of("repo", "子路径");
        com.helloai.core.task.port.TaskDraft draft = new com.helloai.core.task.port.TaskDraft(
                "t", "d", TaskStatus.PENDING,
                Map.of("executionEnv", draftEnv), "BUILD", List.of(), 7L);
        service().createRestructureTask(draft, 5L);

        ArgumentCaptor<Task> captor = ArgumentCaptor.forClass(Task.class);
        verify(taskMapper).insert(captor.capture());
        assertThat(captor.getValue().getContext()).containsEntry("executionEnv", draftEnv);
    }

    @Test
    @DisplayName("执行期派生：父 SubTask 非 RESTRUCTURING ⇒ 400，不建 Y")
    void shouldRejectRestructureWhenParentNotRestructuring() {
        com.helloai.core.task.entity.SubTask parentSub = new com.helloai.core.task.entity.SubTask();
        parentSub.setId(5L);
        parentSub.setTaskId(PARENT_TASK_ID);
        parentSub.setStatus(SubTaskStatus.BLOCKED);
        when(subTaskMapper.selectById(5L)).thenReturn(parentSub);

        com.helloai.core.task.port.TaskDraft draft = new com.helloai.core.task.port.TaskDraft(
                "t", "d", TaskStatus.PENDING, Map.of(), "BUILD", List.of(), 7L);
        assertThatThrownBy(() -> service().createRestructureTask(draft, 5L))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("重构中");
        verify(taskMapper, never()).insert(any(Task.class));
    }

    @Test
    @DisplayName("restructureViable：只判存在性——父已删 → false，父存在（任意状态）→ true")
    void shouldProbeRestructureViability() {
        com.helloai.core.task.entity.SubTask restructuring = new com.helloai.core.task.entity.SubTask();
        restructuring.setId(5L);
        restructuring.setTaskId(PARENT_TASK_ID);
        restructuring.setStatus(SubTaskStatus.RESTRUCTURING);

        // 父 SubTask 已被级联删除（解耦方向一的触发场景）→ 不可派生
        when(subTaskMapper.selectById(5L)).thenReturn(null);
        assertThat(service().restructureViable(5L)).isFalse();

        // 父存在但非 RESTRUCTURING（AWAITING_DERIVED / BLOCKED 等）→ 仍 true：
        // 状态不对是可纠正的，交给 createRestructureTask 的 400 抛给用户，不静默降级断血缘
        com.helloai.core.task.entity.SubTask blocked = new com.helloai.core.task.entity.SubTask();
        blocked.setId(5L);
        blocked.setTaskId(PARENT_TASK_ID);
        blocked.setStatus(SubTaskStatus.BLOCKED);
        when(subTaskMapper.selectById(5L)).thenReturn(blocked);
        assertThat(service().restructureViable(5L)).isTrue();

        // 父存在且 RESTRUCTURING → true
        when(subTaskMapper.selectById(5L)).thenReturn(restructuring);
        assertThat(service().restructureViable(5L)).isTrue();
    }

    // ══════════════════════════════════════════════════════════════
    //  assertDerivable：把「能不能再拆一次」从状态判断改为事实判断
    // ══════════════════════════════════════════════════════════════

    private static com.helloai.core.task.entity.SubTask subTask(long id, SubTaskStatus status) {
        com.helloai.core.task.entity.SubTask st = new com.helloai.core.task.entity.SubTask();
        st.setId(id);
        st.setTaskId(PARENT_TASK_ID);
        st.setStatus(status);
        return st;
    }

    @Test
    @DisplayName("assertDerivable：RESTRUCTURING 直接放行，不查派生事实")
    void shouldPassAssertWhenRestructuring() {
        when(subTaskMapper.selectById(5L)).thenReturn(subTask(5L, SubTaskStatus.RESTRUCTURING));

        service().assertDerivable(5L);

        verify(subTaskStateMachineService, never()).changeStatus(anyLong(), any(), any(), anyMap());
    }

    @Test
    @DisplayName("assertDerivable：AWAITING_DERIVED 且仍有活跃派生 → 400 并告知派生任务 id")
    void shouldRejectWhenActiveDerivationStillExists() {
        when(subTaskMapper.selectById(5L)).thenReturn(subTask(5L, SubTaskStatus.AWAITING_DERIVED));
        Task derived = new Task();
        derived.setId(DERIVED_TASK_ID);
        derived.setParentSubTaskId(5L);
        derived.setStatus(TaskStatus.IN_PROGRESS);
        when(taskMapper.selectOne(any())).thenReturn(derived);

        assertThatThrownBy(() -> service().assertDerivable(5L))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("已完成二次拆分")
                .hasMessageContaining(String.valueOf(DERIVED_TASK_ID));
        // 有活跃派生 ⇒ 标记是对的，不得自愈（否则会把正确状态改坏）
        verify(subTaskStateMachineService, never()).changeStatus(anyLong(), any(), any(), anyMap());
    }

    @Test
    @DisplayName("assertDerivable：派生任务已删（事实为空）→ 标记失实，自愈回 RESTRUCTURING")
    void shouldSelfHealWhenActiveDerivationMissing() {
        when(subTaskMapper.selectById(5L)).thenReturn(subTask(5L, SubTaskStatus.AWAITING_DERIVED));
        when(taskMapper.selectOne(any())).thenReturn(null);

        service().assertDerivable(5L);

        ArgumentCaptor<SubTaskStatus> captor = ArgumentCaptor.forClass(SubTaskStatus.class);
        verify(subTaskStateMachineService).changeStatus(eq(5L), captor.capture(), isNull(), anyMap());
        assertThat(captor.getValue())
                .as("状态机为「派生失败/废除」预留的出边，不是新语义")
                .isEqualTo(SubTaskStatus.RESTRUCTURING);
    }

    @Test
    @DisplayName("assertDerivable：其它状态（BLOCKED/PENDING 等）→ 400，不静默放行")
    void shouldRejectOtherStatus() {
        when(subTaskMapper.selectById(5L)).thenReturn(subTask(5L, SubTaskStatus.BLOCKED));

        assertThatThrownBy(() -> service().assertDerivable(5L))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("仅重构中状态可派生独立任务");
        verify(subTaskStateMachineService, never()).changeStatus(anyLong(), any(), any(), anyMap());
    }
}
