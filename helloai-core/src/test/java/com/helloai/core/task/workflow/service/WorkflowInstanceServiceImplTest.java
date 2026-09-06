package com.helloai.core.task.workflow.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.helloai.common.base.BizException;
import com.helloai.common.constant.AgentRole;
import com.helloai.common.constant.SubTaskStatus;
import com.helloai.common.constant.TaskStatus;
import com.helloai.common.constant.WorkflowInstanceStatus;
import com.helloai.common.constant.WorkflowTemplateStatus;
import com.helloai.core.task.entity.SubTask;
import com.helloai.core.task.entity.Task;
import com.helloai.core.task.service.SubTaskDispatchService;
import com.helloai.core.task.service.SubTaskService;
import com.helloai.core.task.service.TaskService;
import com.helloai.core.task.workflow.domain.WorkflowInstanceStatusView;
import com.helloai.core.task.workflow.entity.WorkflowInstance;
import com.helloai.core.task.workflow.entity.WorkflowTemplate;
import com.helloai.core.task.workflow.entity.WorkflowTemplateVersion;
import com.helloai.core.task.workflow.mapper.WorkflowInstanceMapper;
import com.helloai.core.task.workflow.service.impl.WorkflowInstanceServiceImpl;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link WorkflowInstanceServiceImpl} C1-S2 单元测试：模板 + 参数 → 一次性物化 task/sub_task。
 *
 * <p>断言实例化链路：task 创建参数 / 节点→sub_task 物化（spec 渲染 + context 单顶级键
 * workflow）/ depends_on 回填映射 / 实例绑定 / 分发触发 / task IN_PROGRESS。</p>
 */
@DisplayName("WorkflowInstanceService 实例化（C1-S2）")
class WorkflowInstanceServiceImplTest {

    private static final Long TEMPLATE_ID = 1L;
    private static final Long VERSION_ID = 10L;
    private static final Long TASK_ID = 100L;

    @BeforeAll
    static void initTableInfo() {
        org.apache.ibatis.builder.MapperBuilderAssistant assistant =
                new org.apache.ibatis.builder.MapperBuilderAssistant(new MybatisConfiguration(), "");
        TableInfoHelper.initTableInfo(assistant, WorkflowInstance.class);
    }

    private WorkflowTemplateService templateService;
    private TaskService taskService;
    private SubTaskService subTaskService;
    private SubTaskDispatchService subTaskDispatchService;
    private WorkflowInstanceMapper instanceMapper;
    private WorkflowInstanceServiceImpl service;

    @BeforeEach
    void setUp() {
        templateService = mock(WorkflowTemplateService.class);
        taskService = mock(TaskService.class);
        subTaskService = mock(SubTaskService.class);
        subTaskDispatchService = mock(SubTaskDispatchService.class);
        instanceMapper = mock(WorkflowInstanceMapper.class);
        service = spy(new WorkflowInstanceServiceImpl(
                templateService, taskService, subTaskService, subTaskDispatchService));
        ReflectionTestUtils.setField(service, "baseMapper", instanceMapper);
    }

    private WorkflowTemplate activeTemplate() {
        WorkflowTemplate t = new WorkflowTemplate();
        t.setId(TEMPLATE_ID);
        t.setName("dev-release");
        t.setStatus(WorkflowTemplateStatus.ACTIVE);
        t.setCurrentVersionId(VERSION_ID);
        return t;
    }

    private WorkflowTemplateVersion version(Map<String, Object> definition) {
        WorkflowTemplateVersion v = new WorkflowTemplateVersion();
        v.setId(VERSION_ID);
        v.setTemplateId(TEMPLATE_ID);
        v.setVersionNo(1);
        v.setDefinition(definition);
        return v;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> definition() {
        return Map.of(
                "taskDefaults", Map.of(
                        "titleTemplate", "{{goal}} 发布",
                        "descriptionTemplate", "基于 {{platform}} 的发布流程",
                        "agentPolicy", Map.of("difficulty", "MEDIUM"),
                        "requiredSkills", List.of("eng-verification"),
                        "slaMinutes", 120),
                "paramsSchema", Map.of(
                        "goal", Map.of("type", "string", "required", true),
                        "platform", Map.of("type", "string", "required", false)),
                "nodes", List.of(
                        Map.of("nodeKey", "contract", "role", "executor",
                                "spec", Map.of("title", "契约 {{goal}}", "goal", "明确需求边界")),
                        Map.of("nodeKey", "implement", "role", "executor",
                                "spec", Map.of("title", "实现 {{goal}}", "goal", "按契约实现",
                                        "deliverable", "代码与文档",
                                        "acceptance", "验收 {{platform}} 集成",
                                        "estimated_effort", 2),
                                "dependsOn", List.of("contract"))));
    }

    @Nested
    @DisplayName("前置校验")
    class Guard {

        @Test
        @DisplayName("模板不存在 / 非 ACTIVE / 无 current_version → BizException")
        void shouldRejectInactiveTemplate() {
            WorkflowTemplate draft = new WorkflowTemplate();
            draft.setId(TEMPLATE_ID);
            draft.setStatus(WorkflowTemplateStatus.DRAFT);
            when(templateService.getById(TEMPLATE_ID)).thenReturn(draft);

            assertThatThrownBy(() -> service.createWorkflowInstance(TEMPLATE_ID, Map.of()))
                    .isInstanceOf(BizException.class)
                    .hasMessageContaining("未生效或未发布");
        }

        @Test
        @DisplayName("params 必填缺失 → BizException，不触 task/sub_task")
        void shouldRejectMissingRequiredParam() {
            when(templateService.getById(TEMPLATE_ID)).thenReturn(activeTemplate());
            when(templateService.getVersion(VERSION_ID)).thenReturn(version(definition()));

            assertThatThrownBy(() -> service.createWorkflowInstance(TEMPLATE_ID, Map.of()))
                    .isInstanceOf(BizException.class)
                    .hasMessageContaining("缺失必填项: goal");
            verify(taskService, never()).createTask(any(), any());
        }
    }

    @Nested
    @DisplayName("实例化主链路")
    class Instantiate {

        @Test
        @DisplayName("2 节点依赖实例化：task 渲染创建 → sub_task 物化 → depends_on 回填 → 分发 → IN_PROGRESS")
        void shouldMaterializeTemplate() {
            when(templateService.getById(TEMPLATE_ID)).thenReturn(activeTemplate());
            when(templateService.getVersion(VERSION_ID)).thenReturn(version(definition()));
            Task task = new Task();
            task.setId(TASK_ID);
            when(taskService.createTask("报表 发布", "基于 web 的发布流程", 120,
                    Map.of("difficulty", "MEDIUM"), List.of("eng-verification")))
                    .thenReturn(task);
            when(subTaskService.create(any(SubTask.class), any()))
                    .thenAnswer(inv -> {
                        SubTask st = inv.getArgument(0);
                        st.setId(st.getTitle().contains("契约") ? 201L : 202L);
                        return st;
                    });
            doReturn(true).when(service).save(any(WorkflowInstance.class));

            WorkflowInstance result = service.createWorkflowInstance(TEMPLATE_ID,
                    Map.of("goal", "报表", "platform", "web"));

            // task 创建参数（含 title 占位符渲染）
            ArgumentCaptor<SubTask> stCaptor = ArgumentCaptor.forClass(SubTask.class);
            verify(taskService).createTask("报表 发布", "基于 web 的发布流程", 120,
                    Map.of("difficulty", "MEDIUM"), List.of("eng-verification"));
            verify(taskService).updateStatus(TASK_ID, TaskStatus.IN_PROGRESS);

            // 2 个 sub_task 物化：spec 渲染 + context 单顶级键 workflow
            verify(subTaskService, times(2)).create(stCaptor.capture(), any());
            List<SubTask> created = stCaptor.getAllValues();
            SubTask contract = created.stream().filter(s -> s.getTitle().equals("契约 报表")).findFirst().orElseThrow();
            SubTask implement = created.stream().filter(s -> s.getTitle().equals("实现 报表")).findFirst().orElseThrow();
            assertThat(implement.getDeliverable()).isEqualTo("代码与文档");
            assertThat(implement.getAcceptance()).isEqualTo("验收 web 集成");
            assertThat(contract.getContent()).isEqualTo("明确需求边界");
            @SuppressWarnings("unchecked")
            Map<String, Object> wfMeta = (Map<String, Object>) contract.getContext().get("workflow");
            assertThat(wfMeta.get("nodeKey")).isEqualTo("contract");
            assertThat(wfMeta.get("templateVersionId")).isEqualTo(VERSION_ID);
            assertThat(wfMeta).containsKey("spec"); // spec 原样保留在单顶级键内
            // context 只有 workflow 一个顶级键（不平铺散键）
            assertThat(contract.getContext().keySet()).containsExactly("workflow");

            // depends_on 回填：implement(202) → [contract(201)]
            verify(subTaskService).updateDependsOn(202L, List.of(201L));
            verify(subTaskService, never()).updateDependsOn(201L, List.of());

            // 实例绑定 task_id + status_snapshot 展示 + 分发触发 2 次 + IN_PROGRESS
            assertThat(result.getTaskId()).isEqualTo(TASK_ID);
            assertThat(result.getTemplateId()).isEqualTo(TEMPLATE_ID);
            assertThat(result.getVersionId()).isEqualTo(VERSION_ID);
            assertThat(result.getStatusSnapshot()).isEqualTo("RUNNING");
            verify(subTaskDispatchService).dispatchPendingSubTaskAuto(201L, AgentRole.EXECUTOR);
            verify(subTaskDispatchService).dispatchPendingSubTaskAuto(202L, AgentRole.EXECUTOR);
        }

        @Test
        @DisplayName("spec 缺 title/goal → title 用 nodeKey 兜底")
        void shouldFallbackTitleToNodeKey() {
            when(templateService.getById(TEMPLATE_ID)).thenReturn(activeTemplate());
            when(templateService.getVersion(VERSION_ID)).thenReturn(version(Map.of(
                    "nodes", List.of(Map.of("nodeKey", "x", "role", "executor", "spec", Map.of("deliverable", "d"))),
                    "taskDefaults", Map.of("titleTemplate", "tpl {{goal}}"))));
            when(taskService.createTask(any(), any(), any(), any(), any())).thenReturn(taskOf());
            when(subTaskService.create(any(SubTask.class), any())).thenAnswer(inv -> {
                SubTask st = inv.getArgument(0);
                st.setId(301L);
                return st;
            });
            doReturn(true).when(service).save(any(WorkflowInstance.class));

            service.createWorkflowInstance(TEMPLATE_ID, Map.of("goal", "g"));

            ArgumentCaptor<SubTask> captor = ArgumentCaptor.forClass(SubTask.class);
            verify(subTaskService).create(captor.capture(), any());
            assertThat(captor.getValue().getTitle()).isEqualTo("x"); // nodeKey 兜底
        }
    }

    @Nested
    @DisplayName("实例状态聚合（纯查询投影）")
    class StatusAggregation {

        private WorkflowInstance instance(Long taskId) {
            WorkflowInstance inst = new WorkflowInstance();
            inst.setId(20L);
            inst.setTemplateId(TEMPLATE_ID);
            inst.setVersionId(VERSION_ID);
            inst.setTaskId(taskId);
            return inst;
        }

        private SubTask node(Long id, String nodeKey, SubTaskStatus status) {
            SubTask st = new SubTask();
            st.setId(id);
            st.setStatus(status);
            st.setContext(Map.of("workflow", Map.of("nodeKey", nodeKey)));
            return st;
        }

        @Test
        @DisplayName("实例不存在 → BizException")
        void shouldRejectMissingInstance() {
            when(instanceMapper.selectById(20L)).thenReturn(null);

            assertThatThrownBy(() -> service.aggregateStatus(20L))
                    .isInstanceOf(BizException.class)
                    .hasMessageContaining("实例不存在");
        }

        @Test
        @DisplayName("全部节点 DONE + task DONE → DONE（纯查询聚合，不落权威列）")
        void shouldAggregateDone() {
            when(instanceMapper.selectById(20L)).thenReturn(instance(TASK_ID));
            Task task = new Task();
            task.setId(TASK_ID);
            task.setStatus(TaskStatus.DONE);
            when(taskService.getById(TASK_ID)).thenReturn(task);
            when(subTaskService.list(any(com.baomidou.mybatisplus.core.conditions.Wrapper.class))).thenReturn(List.of(
                    node(201L, "contract", SubTaskStatus.DONE),
                    node(202L, "implement", SubTaskStatus.DONE)));

            WorkflowInstanceStatusView view = service.aggregateStatus(20L);

            assertThat(view.getStatus()).isEqualTo(WorkflowInstanceStatus.DONE);
            assertThat(view.getReason()).isEqualTo("ALL_DONE");
            assertThat(view.getDoneCount()).isEqualTo(2);
            assertThat(view.getNodeStatuses()).containsEntry("contract", "DONE");
        }

        @Test
        @DisplayName("SLA 超时仍有未终态节点 → FAILED（投影展示，不反锁 task/sub_task）")
        void shouldAggregateSlaTimeout() {
            when(instanceMapper.selectById(20L)).thenReturn(instance(TASK_ID));
            Task task = new Task();
            task.setId(TASK_ID);
            task.setStatus(TaskStatus.IN_PROGRESS);
            task.setSlaMinutes(60);
            task.setCreateTime(java.time.OffsetDateTime.now().minusHours(2));
            when(taskService.getById(TASK_ID)).thenReturn(task);
            when(subTaskService.list(any(com.baomidou.mybatisplus.core.conditions.Wrapper.class))).thenReturn(List.of(
                    node(201L, "contract", SubTaskStatus.DONE),
                    node(202L, "implement", SubTaskStatus.ASSIGNED)));

            WorkflowInstanceStatusView view = service.aggregateStatus(20L);

            assertThat(view.getStatus()).isEqualTo(WorkflowInstanceStatus.FAILED);
            assertThat(view.getReason()).isEqualTo("SLA_TIMEOUT");
        }

        @Test
        @DisplayName("节点 BLOCKED（fail-close 待人工）→ RUNNING 不终态（§7 节点失败验证）")
        void shouldStayRunningOnBlockedNode() {
            when(instanceMapper.selectById(20L)).thenReturn(instance(TASK_ID));
            Task task = new Task();
            task.setId(TASK_ID);
            task.setStatus(TaskStatus.IN_PROGRESS);
            when(taskService.getById(TASK_ID)).thenReturn(task);
            when(subTaskService.list(any(com.baomidou.mybatisplus.core.conditions.Wrapper.class))).thenReturn(List.of(
                    node(201L, "contract", SubTaskStatus.DONE),
                    node(202L, "implement", SubTaskStatus.BLOCKED)));

            WorkflowInstanceStatusView view = service.aggregateStatus(20L);

            assertThat(view.getStatus()).isEqualTo(WorkflowInstanceStatus.RUNNING);
            assertThat(view.getReason()).isEqualTo("IN_PROGRESS");
            // 反锁禁令：聚合投影不含任何写操作，task/sub_task 保持原状
            verify(taskService, org.mockito.Mockito.never()).updateStatus(any(), any());
        }
    }

    private Task taskOf() {
        Task t = new Task();
        t.setId(TASK_ID);
        return t;
    }
}
