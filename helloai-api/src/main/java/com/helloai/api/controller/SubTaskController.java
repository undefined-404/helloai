package com.helloai.api.controller;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.helloai.api.dto.PageResult;
import com.helloai.api.dto.subtask.ConversationMessageItem;
import com.helloai.api.dto.subtask.CreateSubTaskRequest;
import com.helloai.api.dto.subtask.ReassignRequest;
import com.helloai.api.dto.subtask.ReworkRequest;
import com.helloai.api.dto.subtask.SubTaskResponse;
import com.helloai.api.dto.subtask.TaskTimelineItem;
import com.helloai.common.base.R;
import com.helloai.common.config.AgentDispatchProperties;
import com.helloai.common.constant.AgentRole;
import com.helloai.common.constant.SubTaskStatus;
import com.helloai.core.agent.domain.ExecutionCommand;
import com.helloai.core.agent.entity.Agent;
import com.helloai.core.agent.entity.ConversationMessage;
import com.helloai.core.agent.service.ConversationService;
import com.helloai.core.task.entity.SubTask;
import com.helloai.core.task.entity.Task;
import com.helloai.core.task.entity.TaskTimeline;
import com.helloai.core.agent.service.AgentExecutionRecordService;
import com.helloai.core.agent.service.AgentService;
import com.helloai.core.agent.command.ExecutionCommandService;
import com.helloai.core.task.service.SubTaskDispatchService;
import com.helloai.core.task.service.SubTaskService;
import com.helloai.core.task.service.TaskService;
import com.helloai.core.task.service.TaskTimelineService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

@Slf4j
@RestController
@RequestMapping("/api/sub-tasks")
@RequiredArgsConstructor
public class SubTaskController {

    private final SubTaskService subTaskService;
    private final TaskService taskService;
    private final AgentService agentService;
    private final SubTaskDispatchService subTaskDispatchService;
    private final ExecutionCommandService executionCommandService;
    private final AgentExecutionRecordService agentExecutionRecordService;
    private final HttpServletRequest request;
    private final AgentDispatchProperties agentDispatchProperties;
    private final TaskTimelineService taskTimelineService;
    private final ConversationService conversationService;

    @PostMapping
    public R<SubTaskResponse> create(@Valid @RequestBody CreateSubTaskRequest req) {
        SubTask subTask = toEntity(req);
        subTask = subTaskService.create(subTask, req.getAssignedAgent());
        log.info("子任务创建: id={}, title={}, taskId={}", subTask.getId(), req.getTitle(), req.getTaskId());

        if (req.getAssignedAgent() == null && agentDispatchProperties.isAutoAssignOnCreate()) {
            subTaskDispatchService.dispatchPendingSubTaskAuto(subTask.getId(), AgentRole.EXECUTOR);
            subTask = subTaskService.getById(subTask.getId());
        }

        return R.ok(toResponse(subTask));
    }

    /**
     * 批量创建子任务（v2.5 M4.5 派发控制台）。
     *
     * <p>同内容 fan-out 派发给多个 Agent；逐项独立创建（每项自身独立事务），
     * 单项失败不影响其余；返回成功创建的列表（不含失败项）。</p>
     */
    @PostMapping("/batch")
    public R<List<SubTaskResponse>> createBatch(@Valid @RequestBody List<CreateSubTaskRequest> reqs) {
        if (reqs == null || reqs.isEmpty()) {
            return R.ok(List.of());
        }
        List<SubTaskService.BatchCreateItem> items = new ArrayList<>(reqs.size());
        for (CreateSubTaskRequest req : reqs) {
            SubTaskService.BatchCreateItem it = new SubTaskService.BatchCreateItem();
            it.setSubTask(toEntity(req));
            it.setAssignedAgentId(req.getAssignedAgent());
            items.add(it);
        }
        List<SubTask> created = subTaskService.createBatch(items);
        List<SubTaskResponse> resp = created.stream().map(this::toResponse).toList();
        log.info("子任务批量派发: requested={}, created={}", reqs.size(), resp.size());
        return R.ok(resp);
    }

    /**
     * 子任务执行时间线（v2.5 M4.5 派发控制台联调可视化）。
     *
     * <p>按 id 升序返回该子任务相关的所有 TaskTimeline 事件；不含系统级事件（如 agent_offline）。</p>
     */
    @GetMapping("/listTimelineBySubTaskId/{id}")
    public R<List<TaskTimelineItem>> listTimeline(@PathVariable("id") Long id) {
        List<TaskTimeline> rows = taskTimelineService.listBySubTaskId(id);
        List<TaskTimelineItem> items = rows.stream().map(this::toTimelineItem).toList();
        return R.ok(items);
    }

    /**
     * 子任务执行对话流（V28 对话流可观测）。
     *
     * <p>按 seq 升序返回执行产出全文与自动核验的 Prompt / 分析原文，
     * 来源由 toolName 区分；只做实体→DTO 映射，不含编排。</p>
     */
    @GetMapping("/listConversationBySubTaskId/{id}")
    public R<List<ConversationMessageItem>> listConversation(@PathVariable("id") Long id) {
        List<ConversationMessage> rows = conversationService.getMessages(id);
        List<ConversationMessageItem> items = rows.stream().map(this::toConversationItem).toList();
        return R.ok(items);
    }

    /** 从 CreateSubTaskRequest 装配 SubTask 实体（Controller 唯一装配点）。 */
    private SubTask toEntity(CreateSubTaskRequest req) {
        SubTask subTask = new SubTask();
        subTask.setTaskId(req.getTaskId());
        subTask.setModuleId(req.getModuleId());
        subTask.setTitle(req.getTitle());
        subTask.setContent(req.getDescription());
        subTask.setDeliverable(req.getDeliverable());
        subTask.setAcceptance(req.getAcceptance());
        subTask.setPriority(req.getPriority() != null ? req.getPriority() : "MEDIUM");
        subTask.setStatus(SubTaskStatus.PENDING);
        return subTask;
    }

    /** TaskTimeline 实体 → TaskTimelineItem DTO。 */
    private TaskTimelineItem toTimelineItem(TaskTimeline e) {
        TaskTimelineItem it = new TaskTimelineItem();
        it.setId(e.getId());
        it.setEventType(e.getEventType());
        it.setRole(e.getRole() != null ? e.getRole().name() : null);
        it.setAgentId(e.getAgentId());
        it.setPayload(e.getPayload());
        it.setCreateTime(e.getCreateTime());
        return it;
    }

    private ConversationMessageItem toConversationItem(ConversationMessage m) {
        ConversationMessageItem it = new ConversationMessageItem();
        it.setId(m.getId());
        it.setRole(m.getRole());
        it.setSenderType(m.getSenderType());
        it.setSenderId(m.getSenderId());
        it.setContent(m.getContent());
        it.setContentType(m.getContentType());
        it.setToolName(m.getToolName());
        it.setSeq(m.getSeq());
        it.setCreateTime(m.getCreateTime());
        return it;
    }

    /**
     * 子任务列表查询（主任务 / 状态 / 负责 Agent 组合过滤）。
     *
     * <p>双返回兼容：不传 page（或 page<=0）返回全量数组，保持 SKILL.md 外部 Agent
     * 不分页调用契约；传 page 返回 {@link PageResult} 分页结构（管理台真分页）。
     * 条件构造已按 §6.3 下沉至 {@link SubTaskService#list}。</p>
     */
    @GetMapping("/list")
    public R<?> list(
            @RequestParam(value = "taskId", required = false) Long taskId,
            @RequestParam(value = "status", required = false) String status,
            @RequestParam(value = "assignedAgent", required = false) Long assignedAgent,
            @RequestParam(value = "page", required = false) Integer page,
            @RequestParam(value = "pageSize", defaultValue = "20") int pageSize) {
        SubTaskStatus statusFilter = (status != null && !status.isBlank()) ? SubTaskStatus.valueOf(status) : null;
        IPage<SubTaskResponse> result = subTaskService
                .list(taskId, statusFilter, assignedAgent, page, pageSize)
                .convert(this::toResponse);
        attachTaskTitles(result.getRecords());
        attachAgentNames(result.getRecords());
        if (page == null || page <= 0) {
            return R.ok(result.getRecords());
        }
        return R.ok(PageResult.of(result));
    }

    @GetMapping("/getById/{id}")
    public R<SubTaskResponse> getById(@PathVariable("id") Long id) {
        SubTask subTask = subTaskService.getById(id);
        if (subTask == null) return R.fail("子任务不存在");
        SubTaskResponse response = toResponse(subTask);
        attachTaskTitles(List.of(response));
        attachAgentNames(List.of(response));
        return R.ok(response);
    }

    @PostMapping("/changeStatus")
    public R<Void> changeStatus(@RequestBody Map<String, Object> body) {
        Long subTaskId = Long.valueOf(body.get("subTaskId").toString());
        String newStatus = (String) body.get("newStatus");
        switch (SubTaskStatus.valueOf(newStatus)) {
            case ASSIGNED -> {
                Object agentId = body.get("agentId");
                if (agentId != null) {
                    subTaskService.claim(subTaskId, Long.valueOf(agentId.toString()));
                }
            }
            case IN_PROGRESS -> subTaskService.start(subTaskId);
            case REVIEW -> subTaskService.submit(subTaskId);
            case BLOCKED -> subTaskService.block(subTaskId);
            case DONE -> subTaskService.complete(subTaskId);
            case CANCELLED -> subTaskService.cancel(subTaskId);
            default -> log.warn("不支持的 change-status 操作: {}", newStatus);
        }
        return R.ok();
    }

    @PostMapping("/claimById/{id}")
    public R<Void> claim(@PathVariable("id") Long id, @RequestParam("agentId") Long agentId) {
        subTaskService.claim(id, agentId);
        return R.ok();
    }

    @PostMapping("/startById/{id}")
    public R<Void> start(@PathVariable("id") Long id) {
        subTaskService.start(id);
        return R.ok();
    }

    @PostMapping("/submitById/{id}")
    public R<Void> submit(@PathVariable("id") Long id) {
        subTaskService.submit(id);
        return R.ok();
    }

    @PostMapping("/completeById/{id}")
    public R<Void> complete(@PathVariable("id") Long id) {
        subTaskService.complete(id);
        return R.ok();
    }

    @PostMapping("/reworkById/{id}")
    public R<Void> rework(@PathVariable("id") Long id, @RequestBody ReworkRequest req) {
        subTaskService.rework(id, req.getReworkAgentId());
        return R.ok();
    }

    @PostMapping("/blockById/{id}")
    public R<Void> block(@PathVariable("id") Long id) {
        subTaskService.block(id);
        return R.ok();
    }

    @PostMapping("/reassignById/{id}")
    public R<Void> reassign(@PathVariable("id") Long id, @Valid @RequestBody ReassignRequest req) {
        subTaskDispatchService.dispatchBlockedSubTask(id, req.getAgentId());
        return R.ok();
    }

    /**
     * 死信人工兜底指派（V25）：将 DEAD_LETTER 子任务直接指派给指定 Agent。
     *
     * <p>重分配熔断（reassign_attempt_count 达阈值）后子任务进入 DEAD_LETTER 死信池，
     * 由人工确认目标 Agent 后调用本接口：熔断计数清零 + 直接 ASSIGNED。
     * 死信列表复用现有列表接口按 status=DEAD_LETTER 过滤。</p>
     */
    @PostMapping("/redispatchDeadLetterById/{id}")
    public R<Void> redispatchDeadLetter(@PathVariable("id") Long id,
                                        @Valid @RequestBody ReassignRequest req) {
        subTaskDispatchService.redispatchDeadLetter(id, req.getAgentId());
        return R.ok();
    }

    @PostMapping("/pauseById/{id}")
    public R<Void> pause(@PathVariable("id") Long id) {
        subTaskService.pause(id);
        return R.ok();
    }

    @PostMapping("/resumeById/{id}")
    public R<Void> resume(@PathVariable("id") Long id) {
        subTaskService.resume(id);
        return R.ok();
    }

    @PostMapping("/executeById/{id}")
    public R<Map<String, Object>> execute(@PathVariable("id") Long id) {
        requireAdmin();

        // 1. subTask 存在
        SubTask subTask = subTaskService.getById(id);
        if (subTask == null) {
            return R.fail("子任务不存在");
        }

        // 2. assignedAgent != null
        if (subTask.getAssignedAgentId() == null) {
            return R.fail("子任务未分配 Agent");
        }

        // 3. status 仅允许 ASSIGNED / REWORK / PAUSED
        SubTaskStatus status = subTask.getStatus();
        if (status != SubTaskStatus.ASSIGNED
                && status != SubTaskStatus.REWORK
                && status != SubTaskStatus.PAUSED) {
            return R.fail("子任务状态不允许执行: " + status);
        }

        // 4. 不存在 PENDING/RUNNING 执行记录，防重复发命令
        if (agentExecutionRecordService.hasPendingOrRunning(id)) {
            return R.fail("子任务已有进行中的执行记录，请勿重复触发");
        }

        ExecutionCommand command = executionCommandService.createAssignedCommand(
                id, subTask.getAssignedAgentId(), "admin-execute");

        return R.ok(Map.of(
                "recordId", command.getRecordId(),
                "eventId", command.getEventId(),
                "subTaskId", command.getSubTaskId(),
                "agentId", command.getAgentId(),
                "trigger", command.getTrigger()));
    }

    private void requireAdmin() {
        Object type = request.getAttribute(com.helloai.api.interceptor.AuthInterceptor.AUTH_TYPE_KEY);
        if (type == null || !"admin".equals(type.toString())) {
            throw new com.helloai.common.base.BizException(403, "admin only");
        }
    }

    @GetMapping("/listAvailable")
    public R<List<SubTaskResponse>> listAvailable() {
        List<SubTask> list = subTaskService.listAvailable();
        return R.ok(list.stream().map(this::toResponse).toList());
    }

    @GetMapping("/listMine")
    public R<List<SubTaskResponse>> listMine(@RequestParam("agentId") Long agentId) {
        List<SubTaskResponse> list = subTaskService.listMine(agentId).stream().map(this::toResponse).toList();
        attachTaskTitles(list);
        attachAgentNames(list);
        return R.ok(list);
    }

    /** 批量回填主任务标题（一次 listByIds 查询，避免逐条 N+1）。 */
    private void attachTaskTitles(List<SubTaskResponse> responses) {
        Set<Long> taskIds = responses.stream()
                .map(SubTaskResponse::getTaskId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        if (taskIds.isEmpty()) {
            return;
        }
        Map<Long, String> titleMap = new HashMap<>();
        for (Task task : taskService.listByIds(taskIds)) {
            titleMap.put(task.getId(), task.getTitle());
        }
        responses.forEach(r -> r.setTaskTitle(titleMap.get(r.getTaskId())));
    }

    /** 批量回填 Agent 名称（一次 listByIds 查询，避免逐条 N+1）。 */
    private void attachAgentNames(List<SubTaskResponse> responses) {
        Set<Long> agentIds = responses.stream()
                .map(SubTaskResponse::getAssignedAgent)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        if (agentIds.isEmpty()) {
            return;
        }
        Map<Long, String> nameMap = new HashMap<>();
        agentService.listByIds(agentIds).forEach(a -> nameMap.put(a.getId(), a.getName()));
        responses.forEach(r -> r.setAssignedAgentName(nameMap.get(r.getAssignedAgent())));
    }

    private SubTaskResponse toResponse(SubTask subTask) {
        SubTaskResponse response = new SubTaskResponse();
        response.setId(subTask.getId());
        response.setTaskId(subTask.getTaskId());
        response.setModuleId(subTask.getModuleId());
        response.setTitle(subTask.getTitle());
        response.setDeliverable(subTask.getDeliverable());
        response.setAcceptance(subTask.getAcceptance());
        response.setPriority(subTask.getPriority());
        response.setStatus(subTask.getStatus());
        response.setAssignedAgent(subTask.getAssignedAgentId());
        response.setContent(subTask.getContent());
        response.setReworkCount(subTask.getReworkCount());
        response.setDependsOn(subTask.dependsOnIdList());
        response.setDeadline(subTask.getDeadline());
        response.setCompletedAt(subTask.getCompleteTime());
        response.setCreateTime(subTask.getCreateTime());
        response.setUpdateTime(subTask.getUpdateTime());
        return response;
    }
}
