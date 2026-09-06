package com.helloai.core.task.workflow.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.helloai.common.base.BizException;
import com.helloai.common.constant.AgentRole;
import com.helloai.common.constant.TaskStatus;
import com.helloai.common.constant.WorkflowTemplateStatus;
import com.helloai.core.task.entity.SubTask;
import com.helloai.core.task.entity.Task;
import com.helloai.core.task.service.SubTaskDispatchService;
import com.helloai.core.task.service.SubTaskService;
import com.helloai.core.task.service.TaskService;
import com.helloai.core.task.workflow.entity.WorkflowInstance;
import com.helloai.core.task.workflow.entity.WorkflowTemplate;
import com.helloai.core.task.workflow.entity.WorkflowTemplateVersion;
import com.helloai.core.task.workflow.mapper.WorkflowInstanceMapper;
import com.helloai.core.task.workflow.service.WorkflowInstanceService;
import com.helloai.core.task.workflow.service.WorkflowTemplateService;
import com.helloai.core.task.workflow.support.WorkflowParamRenderer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Workflow 实例服务实现（N-001，C1-S2）。
 *
 * <p>实例化 = 模板当前激活版本 + 参数 → 一次性物化 task/sub_task（D1）；
 * 复用现有 createTask / sub_task.create / updateDependsOn / dispatchPendingSubTaskAuto
 * 链路（C1 设计 §5 实例化流程）。实例化后由现有调度链接管，本服务零运行期干预。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WorkflowInstanceServiceImpl
        extends ServiceImpl<WorkflowInstanceMapper, WorkflowInstance>
        implements WorkflowInstanceService {

    private static final String STATUS_RUNNING = "RUNNING";

    private final WorkflowTemplateService templateService;
    private final TaskService taskService;
    private final SubTaskService subTaskService;
    private final SubTaskDispatchService subTaskDispatchService;

    @Transactional(rollbackFor = Exception.class)
    @Override
    public WorkflowInstance createWorkflowInstance(Long templateId, Map<String, Object> params) {
        // 1) 模板须生效且已发布版本
        WorkflowTemplate template = templateService.getById(templateId);
        if (template == null || template.getStatus() != WorkflowTemplateStatus.ACTIVE
                || template.getCurrentVersionId() == null) {
            throw new BizException("模板未生效或未发布版本，禁止实例化: id=" + templateId);
        }
        WorkflowTemplateVersion version = templateService.getVersion(template.getCurrentVersionId());
        Map<String, Object> definition = version.getDefinition();
        Map<String, Object> safeParams = params != null ? params : Map.of();

        // 2) params 必填校验
        validateParams(definition, safeParams);

        // 3) taskDefaults 渲染 → 创建 Task（PENDING）
        Map<String, Object> defaults = castMap(definition.get("taskDefaults"));
        String title = WorkflowParamRenderer.render(str(defaults.get("titleTemplate")), safeParams);
        if (title == null || title.isBlank()) {
            title = template.getName() + "（实例化）";
        }
        String description = WorkflowParamRenderer.render(str(defaults.get("descriptionTemplate")), safeParams);
        Integer slaMinutes = defaults.get("slaMinutes") instanceof Number n ? n.intValue() : null;
        Map<String, Object> agentPolicy = castMap(defaults.get("agentPolicy"));
        List<String> requiredSkills = castStringList(defaults.get("requiredSkills"));
        Task task = taskService.createTask(title, description, slaMinutes, agentPolicy, requiredSkills);

        // 4) 遍历节点创建 sub_task（创建即 PENDING，直接进入分发视野）
        List<Object> nodes = castNodeList(definition.get("nodes"));
        Map<String, Long> nodeKeyToSubTaskId = new HashMap<>();
        List<Long> subTaskIds = new ArrayList<>();
        for (Object nodeObj : nodes) {
            Map<String, Object> node = castMap(nodeObj);
            String nodeKey = str(node.get("nodeKey"));
            Map<String, Object> renderedSpec =
                    WorkflowParamRenderer.renderMap(castMap(node.get("spec")), safeParams);

            SubTask st = new SubTask();
            st.setTaskId(task.getId());
            st.setTitle(renderTitle(renderedSpec, nodeKey));
            st.setContent(str(renderedSpec.get("goal")));
            st.setDeliverable(str(renderedSpec.get("deliverable")));
            st.setAcceptance(pick(renderedSpec, "acceptance", "definition_of_done"));
            // context 单顶级键 workflow（C1 设计 D6-1，不平铺散键）
            Map<String, Object> workflowMeta = new HashMap<>();
            workflowMeta.put("nodeKey", nodeKey);
            workflowMeta.put("spec", renderedSpec);
            workflowMeta.put("templateVersionId", version.getId());
            Map<String, Object> context = new HashMap<>();
            context.put("workflow", workflowMeta);
            st.setContext(context);

            SubTask saved = subTaskService.create(st, null);
            nodeKeyToSubTaskId.put(nodeKey, saved.getId());
            subTaskIds.add(saved.getId());
        }

        // 5) depends_on 回填（node_key 依赖 → 真实 sub_task id）
        for (Object nodeObj : nodes) {
            Map<String, Object> node = castMap(nodeObj);
            String nodeKey = str(node.get("nodeKey"));
            List<String> depKeys = castStringList(node.get("dependsOn"));
            List<Long> depIds = depKeys.stream()
                    .map(nodeKeyToSubTaskId::get)
                    .filter(Objects::nonNull)
                    .toList();
            if (!depIds.isEmpty()) {
                subTaskService.updateDependsOn(nodeKeyToSubTaskId.get(nodeKey), depIds);
            }
        }

        // 6) 创建实例（绑定 task_id；纯查询聚合，status_snapshot 仅展示）
        WorkflowInstance instance = new WorkflowInstance();
        instance.setTemplateId(templateId);
        instance.setVersionId(version.getId());
        instance.setParams(safeParams);
        instance.setTaskId(task.getId());
        instance.setStartTime(OffsetDateTime.now());
        instance.setStatusSnapshot(STATUS_RUNNING);
        save(instance);

        // 7) 触发分发（依赖未就绪被 ready 守卫自动拦截）+ Task 置 IN_PROGRESS
        for (Long subTaskId : subTaskIds) {
            subTaskDispatchService.dispatchPendingSubTaskAuto(subTaskId, AgentRole.EXECUTOR);
        }
        taskService.updateStatus(task.getId(), TaskStatus.IN_PROGRESS);

        log.info("Workflow 实例化完成: instanceId={}, templateId={}, versionId={}, taskId={}, nodeCount={}",
                instance.getId(), templateId, version.getId(), task.getId(), nodes.size());
        return instance;
    }

    // ══════════════════════════════════════════════════════════════
    //  内部实现
    // ══════════════════════════════════════════════════════════════

    /** paramsSchema 中 required=true 的键必须非空。 */
    private void validateParams(Map<String, Object> definition, Map<String, Object> params) {
        Object psObj = definition.get("paramsSchema");
        if (!(psObj instanceof Map<?, ?> schema)) {
            return;
        }
        for (Map.Entry<?, ?> e : schema.entrySet()) {
            if (!(e.getValue() instanceof Map<?, ?> meta)) {
                continue;
            }
            Object required = meta.get("required");
            if (required != null && Boolean.parseBoolean(String.valueOf(required))) {
                Object v = params.get(e.getKey());
                if (v == null || String.valueOf(v).isBlank()) {
                    throw new BizException("实例化参数缺失必填项: " + e.getKey());
                }
            }
        }
    }

    /** sub_task.title：spec.title ?? spec.goal（渲染后）；两者都缺 → 节点 nodeKey 兜底。 */
    private String renderTitle(Map<String, Object> spec, String nodeKey) {
        String title = pick(spec, "title", "goal");
        return title != null && !title.isBlank() ? title : nodeKey;
    }

    private String pick(Map<String, Object> map, String... keys) {
        for (String key : keys) {
            Object v = map.get(key);
            if (v != null && !String.valueOf(v).isBlank()) {
                return String.valueOf(v);
            }
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> castMap(Object o) {
        if (o instanceof Map<?, ?> m) {
            Map<String, Object> out = new HashMap<>();
            for (Map.Entry<?, ?> e : m.entrySet()) {
                out.put(String.valueOf(e.getKey()), e.getValue());
            }
            return out;
        }
        return new HashMap<>();
    }

    @SuppressWarnings("unchecked")
    private static List<Object> castNodeList(Object o) {
        if (o instanceof List<?> list) {
            return new ArrayList<>(list);
        }
        return new ArrayList<>();
    }

    @SuppressWarnings("unchecked")
    private static List<String> castStringList(Object o) {
        if (o instanceof List<?> list) {
            List<String> out = new ArrayList<>();
            for (Object item : list) {
                out.add(String.valueOf(item));
            }
            return out;
        }
        return new ArrayList<>();
    }

    private static String str(Object o) {
        return o == null ? null : String.valueOf(o);
    }
}
