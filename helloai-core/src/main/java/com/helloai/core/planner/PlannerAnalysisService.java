package com.helloai.core.planner;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.helloai.common.base.BizException;
import com.helloai.common.config.AgentDispatchProperties;
import com.helloai.common.constant.AgentAccessType;
import com.helloai.common.constant.AgentRole;
import com.helloai.common.constant.SubTaskStatus;
import com.helloai.common.constant.TaskStatus;
import com.helloai.core.agent.domain.AgentResult;
import com.helloai.core.agent.domain.AgentTask;
import com.helloai.core.agent.entity.Agent;
import com.helloai.core.agent.execution.PlatformAgentExecutionService;
import com.helloai.core.agent.executor.AgentSelector;
import com.helloai.core.agent.service.AgentService;
import com.helloai.core.task.entity.SubTask;
import com.helloai.core.task.entity.Task;
import com.helloai.core.task.service.SubTaskDispatchService;
import com.helloai.core.task.service.SubTaskService;
import com.helloai.core.task.service.TaskService;
import com.helloai.core.task.service.TaskTimelineService;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Planner 平台内自动拆解服务（需求 → 子任务草案 → 用户确认 → 进入既有分发链）。
 *
 * <p>职责边界（对齐 §6.3 分层红线：编排逻辑收口在 core，Controller 只做薄转发）：</p>
 * <ul>
 *     <li>{@link #decompose(Long)}：CAS 推进 Task → PLANNING，选平台内 API_KEY_LLM Planner
 *         调 LLM 结构化输出，批量落库 {@code PENDING_PLAN_REVIEW} 草案；失败回退 PENDING。</li>
 *     <li>{@link #listDrafts(Long)}：查看草案列表。</li>
 *     <li>{@link #confirmPlan(Long)}：草案批量转正（→ PENDING），Task → IN_PROGRESS，
 *         按 {@code autoAssignOnCreate} 配置触发既有自动分发链（与手工创建子任务同构）。</li>
 *     <li>{@link #rejectPlan(Long)}：草案翻 CANCELLED（保留审计），Task 回退 PENDING 可重新拆解。</li>
 * </ul>
 *
 * <p>草案态 {@code PENDING_PLAN_REVIEW} 对 claim/assignNext/自动重派/补偿定时任务全部不可见
 * （它们只认 PENDING 等状态），无需额外隔离逻辑。</p>
 *
 * <p>confirm/reject 刻意不加类级事务：逐条 changeStatus 各自独立事务（与既有风格一致），
 * 中途失败可重入——已转正/已取消的子任务不再出现在草案列表，重调即续做剩余部分。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PlannerAnalysisService {

    /** 单次拆解允许落库的草案数量上限（与 Prompt 模板的 3~10 约定对齐，服务端只做硬上限）。 */
    private static final int MAX_DRAFT_COUNT = 10;

    /** timeline detail 中 LLM 原始输出摘要的截断长度。 */
    private static final int RAW_OUTPUT_SUMMARY_LIMIT = 500;

    private static final String PROMPT_TEMPLATE_PATH = "prompts/planner-decompose.md";

    private static final Set<String> VALID_PRIORITIES = Set.of("HIGH", "MEDIUM", "LOW");

    private final TaskService taskService;
    private final SubTaskService subTaskService;
    private final AgentService agentService;
    private final AgentSelector agentSelector;
    private final PlatformAgentExecutionService platformAgentExecutionService;
    private final TaskTimelineService taskTimelineService;
    private final SubTaskDispatchService subTaskDispatchService;
    private final AgentDispatchProperties agentDispatchProperties;
    private final ObjectMapper objectMapper;

    // ══════════════════════════════════════════════════════════════
    //  拆解：Task → PENDING_PLAN_REVIEW 草案
    // ══════════════════════════════════════════════════════════════

    /**
     * 触发平台内自动拆解。
     *
     * <p>不加事务：LLM 调用耗时较长，不能占用数据库事务；草案批量落库走
     * {@link SubTaskService#saveBatch(java.util.Collection)}（ServiceImpl 自带事务，原子提交）。
     * 任何失败路径都会把 Task 从 PLANNING 回退 PENDING 并记录 timeline。</p>
     *
     * @return 落库后的草案列表
     */
    public List<SubTask> decompose(Long taskId) {
        Task task = taskService.getById(taskId);
        if (task == null) {
            throw new BizException("任务不存在: " + taskId);
        }
        if (task.getStatus() != TaskStatus.PENDING) {
            throw new BizException("只有 PENDING 状态的任务才能触发拆解: taskId=" + taskId
                    + ", status=" + task.getStatus());
        }
        // 已有非 CANCELLED 子任务不再拆分（对齐 openMoss 防重复拆分原则）
        long existing = subTaskService.lambdaQuery()
                .eq(SubTask::getTaskId, taskId)
                .ne(SubTask::getStatus, SubTaskStatus.CANCELLED)
                .count();
        if (existing > 0) {
            throw new BizException("任务已存在 " + existing + " 个子任务，不允许重复拆解；"
                    + "如需重新规划请先取消既有子任务");
        }

        // CAS 推进 PENDING → PLANNING，防并发重复拆解
        boolean cas = taskService.lambdaUpdate()
                .eq(Task::getId, taskId)
                .eq(Task::getStatus, TaskStatus.PENDING)
                .set(Task::getStatus, TaskStatus.PLANNING)
                .update();
        if (!cas) {
            throw new BizException("任务正在被其他请求拆解中，请稍后查看草案: taskId=" + taskId);
        }

        try {
            Agent planner = pickPlannerAgent();
            String prompt = renderPrompt(task);

            AgentTask agentTask = AgentTask.builder()
                    .systemPrompt("")
                    .userPrompt(prompt)
                    .context(Map.of("taskId", taskId, "scene", "planner_decompose"))
                    .requiredCapabilities(Map.of())
                    .build();
            taskTimelineService.recordEvent(taskId, null, "task_plan_llm_call_start",
                    AgentRole.PLANNER, planner.getId(),
                    Map.of("agentId", planner.getId(), "agentName", planner.getName()));
            AgentResult result = platformAgentExecutionService.executeSync(planner, agentTask);
            if (!result.isSuccess()) {
                throw new BizException("Planner LLM 调用失败: " + result.getErrorMessage());
            }

            List<PlanDraftItem> items = parseDraftItems(result.getOutput());
            List<SubTask> drafts = buildDrafts(taskId, items, planner);
            subTaskService.saveBatch(drafts);

            taskTimelineService.recordEvent(taskId, null, "task_plan_generated",
                    AgentRole.PLANNER, planner.getId(),
                    Map.of("agentId", planner.getId(),
                            "agentName", planner.getName(),
                            "draftCount", drafts.size(),
                            "rawOutputSummary", summarize(result.getOutput())));
            log.info("任务拆解草案生成: taskId={}, plannerAgentId={}, draftCount={}",
                    taskId, planner.getId(), drafts.size());
            return drafts;
        } catch (Exception e) {
            rollbackToPending(taskId);
            taskTimelineService.recordEvent(taskId, null, "task_plan_failed",
                    AgentRole.PLANNER, null,
                    Map.of("error", e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName()));
            log.warn("任务拆解失败，已回退 PENDING: taskId={}", taskId, e);
            if (e instanceof BizException be) {
                throw be;
            }
            throw new BizException("任务拆解失败: " + e.getMessage());
        }
    }

    // ══════════════════════════════════════════════════════════════
    //  草案查看 / 确认 / 拒绝
    // ══════════════════════════════════════════════════════════════

    /** 查看指定任务的草案列表（PENDING_PLAN_REVIEW）。 */
    public List<SubTask> listDrafts(Long taskId) {
        if (taskService.getById(taskId) == null) {
            throw new BizException("任务不存在: " + taskId);
        }
        return subTaskService.list(taskId, SubTaskStatus.PENDING_PLAN_REVIEW, null, null, 0)
                .getRecords();
    }

    /**
     * 确认草案：全部 PENDING_PLAN_REVIEW → PENDING，Task → IN_PROGRESS。
     *
     * <p>随后按 {@code helloai.dispatch.auto-assign-on-create} 配置决定是否
     * 逐条走 {@link SubTaskDispatchService#dispatchPendingSubTaskAuto} 自动分配，
     * 与 SubTaskController 手工创建子任务的分发路径完全同构。</p>
     *
     * @return 转正后的子任务列表
     */
    public List<SubTask> confirmPlan(Long taskId) {
        Task task = taskService.getById(taskId);
        if (task == null) {
            throw new BizException("任务不存在: " + taskId);
        }
        if (task.getStatus() != TaskStatus.PLANNING) {
            throw new BizException("只有 PLANNING 状态的任务才能确认草案: taskId=" + taskId
                    + ", status=" + task.getStatus());
        }
        List<SubTask> drafts = subTaskService.list(
                taskId, SubTaskStatus.PENDING_PLAN_REVIEW, null, null, 0).getRecords();
        if (drafts.isEmpty()) {
            throw new BizException("任务没有待确认的规划草案: taskId=" + taskId);
        }

        for (SubTask draft : drafts) {
            subTaskService.changeStatus(draft.getId(), SubTaskStatus.PENDING, null,
                    Map.of("planConfirmedAt", OffsetDateTime.now().toString()));
        }
        task.setStatus(TaskStatus.IN_PROGRESS);
        taskService.updateById(task);
        taskTimelineService.recordEvent(taskId, null, "task_plan_confirmed",
                AgentRole.PLANNER, null, Map.of("subTaskCount", drafts.size()));

        // 事务外触发自动分发（分发链内部有独立事务与事件），单条失败不阻断其余
        if (agentDispatchProperties.isAutoAssignOnCreate()) {
            for (SubTask draft : drafts) {
                try {
                    subTaskDispatchService.dispatchPendingSubTaskAuto(draft.getId(), AgentRole.EXECUTOR);
                } catch (Exception e) {
                    log.warn("草案转正后自动分发失败（保持 PENDING 等待兜底任务）: subTaskId={}, err={}",
                            draft.getId(), e.getMessage());
                }
            }
        }
        log.info("任务规划草案已确认: taskId={}, subTaskCount={}, autoAssign={}",
                taskId, drafts.size(), agentDispatchProperties.isAutoAssignOnCreate());
        return drafts.stream().map(d -> subTaskService.getById(d.getId())).toList();
    }

    /**
     * 拒绝草案：全部 PENDING_PLAN_REVIEW → CANCELLED（保留审计），Task 回退 PENDING 可重新拆解。
     *
     * @return 被取消的草案数量
     */
    public int rejectPlan(Long taskId) {
        Task task = taskService.getById(taskId);
        if (task == null) {
            throw new BizException("任务不存在: " + taskId);
        }
        if (task.getStatus() != TaskStatus.PLANNING) {
            throw new BizException("只有 PLANNING 状态的任务才能拒绝草案: taskId=" + taskId
                    + ", status=" + task.getStatus());
        }
        List<SubTask> drafts = subTaskService.list(
                taskId, SubTaskStatus.PENDING_PLAN_REVIEW, null, null, 0).getRecords();

        for (SubTask draft : drafts) {
            subTaskService.changeStatus(draft.getId(), SubTaskStatus.CANCELLED, null,
                    Map.of("planRejectedAt", OffsetDateTime.now().toString()));
        }
        task.setStatus(TaskStatus.PENDING);
        taskService.updateById(task);
        taskTimelineService.recordEvent(taskId, null, "task_plan_rejected",
                AgentRole.PLANNER, null, Map.of("cancelledCount", drafts.size()));
        log.info("任务规划草案已拒绝: taskId={}, cancelledCount={}", taskId, drafts.size());
        return drafts.size();
    }

    // ══════════════════════════════════════════════════════════════
    //  内部实现
    // ══════════════════════════════════════════════════════════════

    /** 选平台内 API_KEY_LLM Planner Agent；无可用时报错并附操作指引。 */
    private Agent pickPlannerAgent() {
        Agent preferred = agentSelector.pickPreferred(AgentRole.PLANNER);
        if (preferred != null && preferred.getAccessType() == AgentAccessType.API_KEY_LLM) {
            return preferred;
        }
        // 首选非平台内执行面时，从同角色候选中过滤 API_KEY_LLM
        return agentService.listByRole(AgentRole.PLANNER).stream()
                .filter(a -> a.getAccessType() == AgentAccessType.API_KEY_LLM)
                .findFirst()
                .orElseThrow(() -> new BizException(
                        "无可用的平台内 Planner Agent（需要 role=PLANNER 且 accessType=API_KEY_LLM）；"
                                + "请先在 Agent 管理中注册，或改用外部 Planner Agent 手工创建子任务"));
    }

    /** 加载 classpath 模板并替换占位符。 */
    private String renderPrompt(Task task) {
        ClassPathResource resource = new ClassPathResource(PROMPT_TEMPLATE_PATH);
        if (!resource.exists()) {
            throw new BizException("未找到拆解 Prompt 模板: " + PROMPT_TEMPLATE_PATH);
        }
        String template;
        try (InputStream in = resource.getInputStream()) {
            template = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new BizException("读取拆解 Prompt 模板失败: " + e.getMessage());
        }
        return template
                .replace("{{TASK_TITLE}}", task.getTitle() != null ? task.getTitle() : "")
                .replace("{{TASK_DESCRIPTION}}",
                        task.getDescription() != null && !task.getDescription().isBlank()
                                ? task.getDescription() : "（无补充描述，请依据标题拆解）");
    }

    /** 解析 LLM 输出为草案条目：strip markdown fence 容错 + 逐条校验必填字段与数量上限。 */
    private List<PlanDraftItem> parseDraftItems(String rawOutput) {
        if (rawOutput == null || rawOutput.isBlank()) {
            throw new BizException("Planner LLM 返回内容为空");
        }
        String cleaned = stripToJsonArray(rawOutput);
        List<PlanDraftItem> items;
        try {
            items = objectMapper.readValue(cleaned, new TypeReference<List<PlanDraftItem>>() {});
        } catch (Exception e) {
            throw new BizException("Planner LLM 输出 JSON 解析失败: " + e.getMessage()
                    + "; 原始输出摘要: " + summarize(rawOutput));
        }
        if (items == null || items.isEmpty()) {
            throw new BizException("Planner LLM 未拆解出任何子任务");
        }
        if (items.size() > MAX_DRAFT_COUNT) {
            throw new BizException("拆解结果超过数量上限 " + MAX_DRAFT_COUNT + ": 实际 " + items.size());
        }
        for (int i = 0; i < items.size(); i++) {
            PlanDraftItem item = items.get(i);
            if (item.getTitle() == null || item.getTitle().isBlank()) {
                throw new BizException("拆解结果第 " + (i + 1) + " 条缺少 title");
            }
        }
        return items;
    }

    /** 剥离 markdown 代码块围栏，并兜底截取首尾方括号之间的 JSON 数组。 */
    private String stripToJsonArray(String raw) {
        String cleaned = raw.trim();
        if (cleaned.startsWith("```")) {
            int firstNewline = cleaned.indexOf('\n');
            if (firstNewline > 0) {
                cleaned = cleaned.substring(firstNewline + 1);
            }
            int fenceEnd = cleaned.lastIndexOf("```");
            if (fenceEnd >= 0) {
                cleaned = cleaned.substring(0, fenceEnd);
            }
            cleaned = cleaned.trim();
        }
        if (!cleaned.startsWith("[")) {
            int start = cleaned.indexOf('[');
            int end = cleaned.lastIndexOf(']');
            if (start >= 0 && end > start) {
                cleaned = cleaned.substring(start, end + 1);
            }
        }
        return cleaned;
    }

    /** 草案实体装配：status=PENDING_PLAN_REVIEW，context 记录拆解来源审计信息。 */
    private List<SubTask> buildDrafts(Long taskId, List<PlanDraftItem> items, Agent planner) {
        List<SubTask> drafts = new ArrayList<>(items.size());
        String generatedAt = OffsetDateTime.now().toString();
        for (PlanDraftItem item : items) {
            SubTask draft = new SubTask();
            draft.setTaskId(taskId);
            draft.setTitle(item.getTitle().trim());
            draft.setContent(item.getContent());
            draft.setDeliverable(item.getDeliverable());
            draft.setAcceptance(item.getAcceptance());
            draft.setPriority(normalizePriority(item.getPriority()));
            draft.setStatus(SubTaskStatus.PENDING_PLAN_REVIEW);
            Map<String, Object> context = new HashMap<>();
            context.put("plannerAgentId", planner.getId());
            context.put("plannerAgentName", planner.getName());
            context.put("planGeneratedAt", generatedAt);
            draft.setContext(context);
            drafts.add(draft);
        }
        return drafts;
    }

    private String normalizePriority(String priority) {
        if (priority == null) {
            return "MEDIUM";
        }
        String upper = priority.trim().toUpperCase();
        return VALID_PRIORITIES.contains(upper) ? upper : "MEDIUM";
    }

    /** 失败回退：仅当 Task 仍处 PLANNING 时回退 PENDING（避免覆盖并发确认结果）。 */
    private void rollbackToPending(Long taskId) {
        try {
            taskService.lambdaUpdate()
                    .eq(Task::getId, taskId)
                    .eq(Task::getStatus, TaskStatus.PLANNING)
                    .set(Task::getStatus, TaskStatus.PENDING)
                    .update();
        } catch (Exception e) {
            log.error("任务拆解失败后回退 PENDING 异常: taskId={}", taskId, e);
        }
    }

    private String summarize(String raw) {
        if (raw == null) {
            return "";
        }
        String trimmed = raw.trim();
        return trimmed.length() <= RAW_OUTPUT_SUMMARY_LIMIT
                ? trimmed : trimmed.substring(0, RAW_OUTPUT_SUMMARY_LIMIT) + "...";
    }

    /** LLM 结构化输出条目（未知字段容忍，避免 LLM 多给字段导致整批失败）。 */
    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class PlanDraftItem {
        private String title;
        private String content;
        private String deliverable;
        private String acceptance;
        private String priority;
    }
}
